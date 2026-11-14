package cz.natsu.automation.engine

import android.content.ClipboardManager
import android.content.Context
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Values are shown in the log in full only up to this length. API responses can be
 * huge (e.g. an uploaded image echoed back as base64) and laying out such a text
 * freezes the UI. The step's real output (used by other steps) is never shortened.
 */
private const val LOG_PREVIEW = 4000

fun logPreview(value: String?): String? =
    if (value == null || value.length <= LOG_PREVIEW) value
    else value.take(LOG_PREVIEW) + "\n… (zkráceno, celkem ${value.length} znaků)"

data class LogEntry(
    val blockId: String,
    val title: String,
    val output: String? = null,
    val error: String? = null,
    /** The step is switched off and was skipped. */
    val skipped: Boolean = false,
)

data class RunResult(
    /** Value from the "Return result" block, if the automation reached one. */
    val output: String?,
    val log: List<LogEntry>,
    val error: String? = null,
    val requirement: Requirement? = null,
    /** The user cancelled a dialog; not an error. */
    val cancelled: Boolean = false,
) {
    val success: Boolean get() = error == null
}

/** How an automation was started; [label] is the value of the "Typ spuštění" variable. */
enum class RunTrigger(val label: String) {
    Manual("Ručně"),
    Share("Sdílení"),
    ProcessText("Výběr textu"),
    Clipboard("Schránka"),
    Tile("Dlaždice"),
    /** A button of a notification shown by the "Oznámení s tlačítky" block. */
    NotificationButton("Tlačítko oznámení"),
    /** Started by a system event; the "Typ spuštění" value is the event's title. */
    Event("Událost"),
}

/** State of one automation run, available to blocks while they execute. */
class RunContext(
    val context: Context,
    val input: String?,
    /** Dialogs for interactive blocks; null when nobody can answer them. */
    val ui: RunUi? = null,
    val trigger: RunTrigger = RunTrigger.Manual,
    /** The system event that started the run, if any. */
    val event: EventInfo? = null,
    /** Automation being run (stored values belong to it); null for a run without one. */
    val automationId: Long? = null,
) {
    val variables = mutableMapOf<String, String>()
    val outputs = mutableMapOf<String, String>()

    /** Loops whose "end" just jumped back to them (next round, not a fresh start). */
    val loopAgain = mutableSetOf<String>()
    /** Per-loop state (list and position, round number) by block id. */
    val loopState = mutableMapOf<String, Any>()
    /** Current round (from 1) of every running loop, by block id. */
    val loopRound = mutableMapOf<String, Int>()
    /** Id of the innermost loop the current step is in, or null. */
    var currentLoop: String? = null
        internal set

    /** Secret values used in this run; they are masked in the log. */
    private val secretValues = mutableSetOf<String>()

    /** [text] with every secret value used so far replaced by dots. */
    fun mask(text: String?): String? {
        if (text == null || secretValues.isEmpty()) return text
        var masked: String = text
        for (secret in secretValues) if (secret.length >= 3) masked = masked.replace(secret, "••••••")
        return masked
    }

    private fun paramOf(block: Block, key: String): ParamValue? =
        block.params[key] ?: Blocks.get(block.type)?.param(key)?.defaultValue()

    suspend fun text(block: Block, key: String): String = resolve(paramOf(block, key))

    fun choice(block: Block, key: String): String =
        (paramOf(block, key) as? ParamValue.Choice)?.value ?: ""

    fun number(block: Block, key: String): Float =
        (paramOf(block, key) as? ParamValue.Number)?.value ?: 0f

    fun bool(block: Block, key: String): Boolean =
        (paramOf(block, key) as? ParamValue.Bool)?.value ?: false

    suspend fun resolve(value: ParamValue?): String = when (value) {
        null -> ""
        is ParamValue.Rich -> buildString {
            for (segment in value.segments) {
                when (segment) {
                    is Segment.Text -> append(segment.text)
                    is Segment.Var -> append(resolve(segment.ref))
                }
            }
        }
        is ParamValue.Choice -> value.value
        is ParamValue.Number -> formatNumber(value.value.toDouble())
        is ParamValue.Bool -> value.value.toString()
    }

    suspend fun resolve(ref: VarRef): String = when (ref) {
        VarRef.Input -> input ?: ""
        VarRef.Clipboard -> readClipboard()
        VarRef.CurrentDate -> SimpleDateFormat("d. M. yyyy H:mm", Locale.getDefault()).format(Date())
        VarRef.Trigger -> event?.label ?: trigger.label
        is VarRef.Event -> event?.values?.get(ref.key) ?: DeviceState.live(context, ref.key)
        is VarRef.BlockOutput -> outputs[ref.blockId] ?: ""
        is VarRef.Named -> variables[ref.name] ?: ""
        is VarRef.Secret -> (SecretStore.get(context, ref.name)
            ?: throw BlockException("tajná hodnota \"${ref.name}\" neexistuje (Nastavení → Tajné hodnoty)"))
            .also { secretValues += it }
    }

    /**
     * Android only lets the app with window focus read the clipboard, so this works
     * from the editor and from the trigger activity, not from the background.
     */
    suspend fun readClipboard(): String = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = clipboard?.primaryClip
        if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString() ?: ""
    }
}

fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) {
        value.toLong().toString()
    } else {
        value.toString()
    }

object AutomationRunner {
    /**
     * Safety net against endless loops. High enough for loops over thousands of
     * items; the run also yields regularly, so "Stop" always works.
     */
    private const val MAX_STEPS = 1_000_000

    /** A "Zkusit" block whose steps are running; [attempt] counts from 1. */
    private class TryFrame(val start: Int, var attempt: Int = 1)

    suspend fun run(
        context: Context,
        blocks: List<Block>,
        input: String?,
        ui: RunUi? = null,
        trigger: RunTrigger = RunTrigger.Manual,
        event: EventInfo? = null,
        onStep: (blockId: String) -> Unit = {},
        /** Called after every executed step; step-by-step runs suspend here until "next". */
        afterStep: (suspend (LogEntry, RunContext) -> Unit)? = null,
        /** The automation the blocks belong to; its stored values are separate from other automations'. */
        automationId: Long? = null,
    ): RunResult = withContext(Dispatchers.Default) {
        val ctx = RunContext(context.applicationContext, input, ui, trigger, event, automationId)
        val log = mutableListOf<LogEntry>()
        val structure = Structure.analyze(blocks)
        val tries = ArrayDeque<TryFrame>()

        if (!structure.valid) {
            return@withContext RunResult(null, log, "Podmínky, opakování nebo Zkusit nejsou správně uzavřené (Konec).")
        }

        fun shown(value: String?) = logPreview(ctx.mask(value))
        fun kindAt(index: Int) = Blocks.get(blocks[index].type)?.kind

        /**
         * A step failed. Inside "Zkusit" the error is caught: the steps are tried
         * again (while attempts are left) or the run continues in "Když selže".
         * Returns where to continue, or null when nothing catches the error.
         */
        suspend fun caught(message: String): Int? {
            val frame = tries.lastOrNull() ?: return null
            val tryBlock = blocks[frame.start]
            val attempts = ctx.number(tryBlock, "attempts").toInt().coerceAtLeast(1)
            if (frame.attempt < attempts) {
                frame.attempt++
                val wait = (ctx.number(tryBlock, "delay") * 1000).toLong()
                if (wait > 0) kotlinx.coroutines.delay(wait)
                return frame.start + 1
            }
            tries.removeLast()
            ctx.outputs[tryBlock.id] = message
            val entry = LogEntry(tryBlock.id, Blocks.get(tryBlock.type)?.title ?: "", output = shown("zachyceno: $message"))
            log += entry
            afterStep?.invoke(entry, ctx)
            return (structure.elseOf[frame.start] ?: structure.endOf[frame.start] ?: (blocks.size - 1)) + 1
        }

        var pc = 0
        var steps = 0
        while (pc < blocks.size) {
            if (++steps > MAX_STEPS) {
                return@withContext RunResult(null, log, "Akce udělala přes milion kroků (nekonečné opakování?) a byla zastavena.")
            }
            if (steps % 500 == 0) kotlinx.coroutines.yield()

            val block = blocks[pc]
            val def = Blocks.get(block.type)
            if (def == null) {
                pc++
                continue
            }
            ctx.currentLoop = structure.loopOf[pc]?.let { blocks[it].id }

            // Switched-off step: skip it (an "if" / loop / "try" with everything up to its end).
            if (block.disabled && def.kind != BlockKind.Else && def.kind != BlockKind.End) {
                val entry = LogEntry(block.id, def.title, output = "vypnuto – přeskočeno", skipped = true)
                log += entry
                afterStep?.invoke(entry, ctx)
                pc = if (def.kind.isGroupStart) (structure.endOf[pc] ?: pc) + 1 else pc + 1
                continue
            }

            onStep(block.id)

            // Reaching "Otherwise" / "Když selže" means the branch before it has finished.
            if (def.kind == BlockKind.Else) {
                val owner = structure.elseOf.entries.firstOrNull { it.value == pc }?.key
                if (owner != null && kindAt(owner) == BlockKind.TryStart) tries.removeAll { it.start == owner }
                pc = (structure.endOf[pc] ?: blocks.size - 1) + 1
                continue
            }
            // End of a loop jumps back to its start, which decides about the next round.
            if (def.kind == BlockKind.End) {
                val start = structure.startOf[pc]
                val startKind = start?.let { kindAt(it) }
                if (startKind == BlockKind.TryStart) tries.removeAll { it.start == start }
                pc = if (start != null && startKind == BlockKind.WhileStart) {
                    ctx.loopAgain += blocks[start].id
                    start
                } else {
                    pc + 1
                }
                continue
            }

            if (def.kind == BlockKind.WhileStart) {
                ctx.loopRound[block.id] = if (block.id in ctx.loopAgain) (ctx.loopRound[block.id] ?: 0) + 1 else 1
            }
            if (def.kind == BlockKind.TryStart) {
                tries.removeAll { it.start == pc }
                tries.addLast(TryFrame(pc))
                ctx.outputs[block.id] = ""
            }

            val failure: Pair<String, Requirement?>? = try {
                val result = def.execute(ctx, block)

                val entry = when (result) {
                    BlockResult.None -> LogEntry(block.id, def.title)
                    is BlockResult.Output -> LogEntry(block.id, def.title, output = shown(result.value))
                    is BlockResult.Condition -> LogEntry(
                        block.id, def.title,
                        output = shown(result.output) ?: if (result.passed) "splněno" else "nesplněno"
                    )
                    is BlockResult.Stop -> LogEntry(block.id, def.title, output = shown(result.output))
                    is BlockResult.LoopJump -> LogEntry(block.id, def.title, output = if (result.exit) "konec opakování" else "další kolo")
                }
                if (result is BlockResult.Output) ctx.outputs[block.id] = result.value
                if (result is BlockResult.Condition && result.passed && result.output != null) ctx.outputs[block.id] = result.output
                log += entry
                afterStep?.invoke(entry, ctx)

                when (result) {
                    BlockResult.None -> pc++
                    is BlockResult.Output -> pc++
                    is BlockResult.Condition -> {
                        pc = if (result.passed) {
                            pc + 1
                        } else {
                            val target = structure.elseOf[pc] ?: structure.endOf[pc] ?: (blocks.size - 1)
                            target + 1
                        }
                    }
                    is BlockResult.Stop -> return@withContext RunResult(result.output, log)
                    is BlockResult.LoopJump -> {
                        val loop = structure.loopOf[pc]
                            ?: return@withContext RunResult(null, log, "${def.title}: krok není uvnitř opakování")
                        val end = structure.endOf[loop] ?: (blocks.size - 1)
                        // Leaving the loop's body also leaves any "Zkusit" inside it.
                        tries.removeAll { it.start > loop }
                        if (result.exit) {
                            ctx.loopState.remove(blocks[loop].id)
                            pc = end + 1
                        } else {
                            pc = end
                        }
                    }
                }
                null
            } catch (e: RunCancelledException) {
                log += LogEntry(block.id, def.title, output = "zrušeno")
                return@withContext RunResult(null, log, cancelled = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BlockException) {
                (e.message ?: "chyba") to e.requirement
            } catch (e: SecurityException) {
                "chybí oprávnění (${e.message})" to def.requirement
            } catch (e: Exception) {
                (e.message ?: e.javaClass.simpleName) to null
            }

            if (failure != null) {
                val (message, requirement) = failure
                val entry = LogEntry(block.id, def.title, error = ctx.mask(message))
                log += entry
                val next = caught("${def.title}: $message")
                    ?: return@withContext RunResult(null, log, ctx.mask("${def.title}: $message"), requirement)
                // A caught error pauses step-by-step runs too, so it can be seen on the step.
                afterStep?.invoke(entry, ctx)
                pc = next
            }
        }

        RunResult(null, log)
    }

    /**
     * Launch conditions: whether an automation should be offered for this start at all.
     * No conditions = always. A condition that cannot be evaluated counts as not met.
     */
    suspend fun conditionsMet(
        context: Context,
        conditions: List<Block>,
        matchAny: Boolean,
        input: String?,
        trigger: RunTrigger,
        event: EventInfo? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        val active = conditions.filterNot { it.disabled }
        if (active.isEmpty()) return@withContext true
        val ctx = RunContext(context.applicationContext, input, null, trigger, event)
        val results = active.map { condition ->
            try {
                Blocks.evaluateCondition(ctx, condition)
            } catch (e: Exception) {
                false
            }
        }
        if (matchAny) results.any { it } else results.all { it }
    }
}
