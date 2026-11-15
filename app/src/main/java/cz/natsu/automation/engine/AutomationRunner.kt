package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.content.ClipboardManager
import android.content.Context
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    else value.take(LOG_PREVIEW) + tr("\n… (zkráceno, celkem %s znaků)", value.length)

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
    /** How deep this run is in "Spustit automatizaci" calls (0 = started directly). */
    val depth: Int = 0,
) {
    // Branches of "Souběžně" run at the same time, so the state of a run is thread-safe.
    val variables: MutableMap<String, String> = ConcurrentHashMap()
    val outputs: MutableMap<String, String> = ConcurrentHashMap()

    /** Loops whose "end" just jumped back to them (next round, not a fresh start). */
    val loopAgain: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Per-loop state (list and position, round number) by block id. */
    val loopState: MutableMap<String, Any> = ConcurrentHashMap()
    /** Current round (from 1) of every running loop, by block id. */
    val loopRound: MutableMap<String, Int> = ConcurrentHashMap()
    /** Id of a step -> id of the innermost loop it is in. */
    var loopOf: Map<String, String> = emptyMap()
        internal set

    /** Value a "Vyber podle" is choosing by, by the group id it shares with its branches. */
    val switchValues: MutableMap<String, String> = ConcurrentHashMap()

    /** Secret values used in this run; they are masked in the log. */
    private val secretValues: MutableSet<String> = ConcurrentHashMap.newKeySet()

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

    /**
     * Runs the parameter [key] of [block] as a script ([Script]) and returns its value.
     * Variable chips in the code are passed as values, never pasted into the code, and
     * the script reads and writes the variables of this run.
     */
    suspend fun script(block: Block, key: String): Any? {
        val chips = mutableListOf<String>()
        val code = buildString {
            when (val value = paramOf(block, key)) {
                is ParamValue.Rich -> for (segment in value.segments) when (segment) {
                    is Segment.Text -> append(segment.text.filter { it != Script.CHIP_START && it != Script.CHIP_END })
                    is Segment.Var -> {
                        chips += resolve(segment.ref)
                        append(Script.CHIP_START).append(chips.size - 1).append(Script.CHIP_END)
                    }
                }
                else -> append(resolve(value))
            }
        }
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        return Script.run(code, object : Script.Host {
            override fun get(name: String): Any? = when {
                name in variables -> variables[name]
                name == "vstup" || name == "input" -> input ?: ""
                // The other built-in variables of the editor, under the names "Show as script" writes.
                name == "clipboard" || name == "schranka" -> kotlinx.coroutines.runBlocking { resolve(VarRef.Clipboard) }
                name == "trigger" || name == "spusteni" -> kotlinx.coroutines.runBlocking { resolve(VarRef.Trigger) }
                else -> Script.MISSING
            }

            override fun set(name: String, value: Any?) {
                variables[name] = Script.text(value)
            }

            override fun chip(index: Int): String = chips[index]
            override fun active(): Boolean = job?.isActive ?: true

            override fun call(name: String, args: List<Any?>): Any? {
                // blok("typ", …) reaches every block, also those named like a built-in function.
                // Values of the event that started the run, and secrets (masked in the log like everywhere else).
                if (name == "event" || name == "udalost") {
                    return kotlinx.coroutines.runBlocking { resolve(VarRef.Event(Script.text(args.firstOrNull()))) }
                }
                if (name == "secret" || name == "tajne") {
                    return kotlinx.coroutines.runBlocking { resolve(VarRef.Secret(Script.text(args.firstOrNull()))) }
                }
                val explicit = name == "blok" || name == "block"
                val type = if (explicit) Script.text(args.firstOrNull()) else name
                val def = Blocks.get(type)
                    ?: if (explicit) throw BlockException(tr("blok \"%s\" neexistuje", type)) else return Script.MISSING
                return callBlock(def, if (explicit) args.drop(1) else args, job)
            }
        })
    }

    /**
     * Runs one block for a script. Its parameters are given by name in an object
     * (`{url: "…", method: "POST"}`) or one after another in the block's own order.
     */
    private fun callBlock(def: BlockDef, args: List<Any?>, job: kotlinx.coroutines.Job?): Any? {
        if (def.kind != BlockKind.Normal || def.type in setOf("return", "loop_control", "loop_index", Blocks.LAUNCH_CONDITION)) {
            throw BlockException(tr("blok \"%s\" řídí běh kroků a ze skriptu volat nejde", def.title))
        }
        val named = args.singleOrNull() as? Map<*, *>
        val given: List<Pair<ParamDef, Any?>> = if (named != null) {
            named.map { (key, value) ->
                val param = def.param(key.toString()) ?: throw BlockException(
                    tr("%s: neznámý parametr \"%s\" (má: %s)", def.type, key, def.params.joinToString { it.key })
                )
                param to value
            }
        } else {
            if (args.size > def.params.size) {
                throw BlockException(tr("%s: moc hodnot (má parametry: %s)", def.type, def.params.joinToString { it.key }))
            }
            def.params.zip(args)
        }
        val params = given.associate { (param, value) ->
            val text = Script.text(value)
            param.key to when (param) {
                is ParamDef.Text -> richText(text)
                is ParamDef.Choice -> ParamValue.Choice(
                    param.options.firstOrNull {
                        it.value.equals(text, true) || it.label.equals(text, true) || it.short.equals(text, true)
                    }?.value ?: throw BlockException(
                        tr("%s.%s: \"%s\" není možnost (%s)", def.type, param.key, text, param.options.joinToString { it.value })
                    )
                )
                is ParamDef.Number -> ParamValue.Number(
                    Script.numberOf(value)?.toFloat() ?: throw BlockException(tr("%s.%s: \"%s\" není číslo", def.type, param.key, text))
                )
                is ParamDef.Toggle -> ParamValue.Bool(Script.truthy(value))
                is ParamDef.Automation -> ParamValue.Choice(text)
            }
        }
        val block = def.newBlock().let { it.copy(params = it.params + params) }
        // The script runs on a background thread; the block may suspend (network, dialogs), so wait for it here.
        val result = kotlinx.coroutines.runBlocking(job ?: kotlin.coroutines.EmptyCoroutineContext) {
            def.execute(this@RunContext, block)
        }
        return when (result) {
            BlockResult.None -> null
            is BlockResult.Output -> result.value
            is BlockResult.Condition -> result.passed
            is BlockResult.Stop, is BlockResult.LoopJump ->
                throw BlockException(tr("blok \"%s\" ze skriptu volat nejde", def.title))
        }
    }

    suspend fun resolve(ref: VarRef): String = when (ref) {
        VarRef.Input -> input ?: ""
        VarRef.Clipboard -> readClipboard()
        VarRef.CurrentDate -> SimpleDateFormat("d. M. yyyy H:mm", Locale.getDefault()).format(Date())
        // Shown in the language of the app; comparisons accept both languages (Equivalents).
        VarRef.Trigger -> tr(event?.label ?: trigger.label)
        is VarRef.Event -> event?.values?.get(ref.key) ?: DeviceState.live(context, ref.key)
        is VarRef.BlockOutput -> outputs[ref.blockId] ?: ""
        is VarRef.Named -> variables[ref.name] ?: ""
        is VarRef.Secret -> (SecretStore.get(context, ref.name)
            ?: throw BlockException(tr("tajná hodnota \"%s\" neexistuje (Nastavení → Tajné hodnoty)", ref.name)))
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

    /** How a range of steps ended. */
    private sealed interface Outcome {
        /** The last step of the range was done; the run goes on after it. */
        data object Done : Outcome
        /** "Vrátit výsledek": the whole run ends with [output]. */
        class Stop(val output: String?) : Outcome
        class Failed(val message: String, val requirement: Requirement? = null) : Outcome
        data object Cancelled : Outcome
    }

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
        /** Nesting of "Spustit automatizaci" calls. */
        depth: Int = 0,
    ): RunResult = withContext(Dispatchers.Default) {
        val ctx = RunContext(context.applicationContext, input, ui, trigger, event, automationId, depth)
        val structure = Structure.analyze(blocks)
        if (!structure.valid) {
            return@withContext RunResult(
                null, emptyList(), tr("Podmínky, opakování nebo Zkusit nejsou správně uzavřené (Konec).")
            )
        }
        ctx.loopOf = structure.loopOf.entries.associate { (step, loop) -> blocks[step].id to blocks[loop].id }

        val run = Run(blocks, ctx, structure, onStep, afterStep)
        val outcome = run.execute(0, blocks.size)
        val log = synchronized(run.log) { run.log.toList() }
        when (outcome) {
            Outcome.Done -> RunResult(null, log)
            is Outcome.Stop -> RunResult(outcome.output, log)
            is Outcome.Failed -> RunResult(null, log, ctx.mask(outcome.message), outcome.requirement)
            Outcome.Cancelled -> RunResult(null, log, cancelled = true)
        }
    }

    /** One run of an automation; [execute] runs a range of its steps (the whole list, or one parallel branch). */
    private class Run(
        val blocks: List<Block>,
        val ctx: RunContext,
        val structure: Structure,
        val onStep: (String) -> Unit,
        val afterStep: (suspend (LogEntry, RunContext) -> Unit)?,
    ) {
        val log: MutableList<LogEntry> = Collections.synchronizedList(mutableListOf())
        private val steps = AtomicInteger()

        private fun shown(value: String?) = logPreview(ctx.mask(value))
        private fun kindAt(index: Int) = Blocks.get(blocks[index].type)?.kind

        private suspend fun record(entry: LogEntry) {
            log += entry
            afterStep?.invoke(entry, ctx)
        }

        /** Runs the steps [from] until [until] (exclusive). */
        suspend fun execute(from: Int, until: Int): Outcome {
            val tries = ArrayDeque<TryFrame>()
            /** Index of the "Jinak pokud" that is to be evaluated next (the condition before it failed). */
            var pendingBranch = -1

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
                record(LogEntry(tryBlock.id, Blocks.get(tryBlock.type)?.title ?: "", output = shown(tr("zachyceno: %s", message))))
                return (structure.elseOf[frame.start] ?: structure.endOf[frame.start] ?: (blocks.size - 1)) + 1
            }

            var pc = from
            while (pc < until) {
                val count = steps.incrementAndGet()
                if (count > MAX_STEPS) {
                    return Outcome.Failed(tr("Akce udělala přes milion kroků (nekonečné opakování?) a byla zastavena."))
                }
                if (count % 500 == 0) kotlinx.coroutines.yield()

                val block = blocks[pc]
                val def = Blocks.get(block.type)
                if (def == null) {
                    pc++
                    continue
                }
                val fixed = def.kind == BlockKind.Else || def.kind == BlockKind.End ||
                        def.kind == BlockKind.ElseIf || def.kind == BlockKind.Branch

                // Switched-off step: skip it (an "if" / loop / "try" with everything up to its end).
                if (block.disabled && !fixed) {
                    record(LogEntry(block.id, def.title, output = tr("vypnuto – přeskočeno"), skipped = true))
                    pc = if (def.kind.isGroupStart) (structure.endOf[pc] ?: pc) + 1 else pc + 1
                    continue
                }

                // "Jinak pokud" is evaluated only when the condition before it has just failed;
                // reached from the steps above it, that branch is done and the "if" ends.
                if (def.kind == BlockKind.ElseIf) {
                    if (pendingBranch != pc) {
                        pc = (structure.endOf[pc] ?: blocks.size - 1) + 1
                        continue
                    }
                    pendingBranch = -1
                }

                onStep(block.id)

                // Reaching "Otherwise" / "Když selže" means the branch before it has finished.
                if (def.kind == BlockKind.Else) {
                    val owner = structure.endOf[pc]?.let { structure.startOf[it] }
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
                // A branch separator is only passed by a range that starts before it; nothing to do.
                if (def.kind == BlockKind.Branch) {
                    pc++
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

                /** Error of this step: the message and whether it already names the step. */
                var failure: Triple<String, Requirement?, Boolean>? = null

                if (def.kind == BlockKind.ParallelStart) {
                    val end = structure.endOf[pc] ?: (blocks.size - 1)
                    val bounds = listOf(pc) + structure.branchesOf[pc].orEmpty() + end
                    val ranges = bounds.zipWithNext { a, b -> (a + 1) to b }
                    record(LogEntry(block.id, def.title, output = tr("větví: %s", ranges.size)))
                    val outcomes: List<Outcome> = if (afterStep != null) {
                        // Step-by-step: one branch after another, so each step can be looked at.
                        buildList {
                            for ((a, b) in ranges) {
                                val outcome = execute(a, b)
                                add(outcome)
                                if (outcome != Outcome.Done) break
                            }
                        }
                    } else {
                        coroutineScope { ranges.map { (a, b) -> async { execute(a, b) } }.awaitAll() }
                    }
                    outcomes.firstOrNull { it is Outcome.Stop }?.let { return it }
                    outcomes.firstOrNull { it == Outcome.Cancelled }?.let { return it }
                    val failed = outcomes.firstNotNullOfOrNull { it as? Outcome.Failed }
                    if (failed == null) {
                        pc = end + 1
                        continue
                    }
                    failure = Triple(failed.message, failed.requirement, true)
                } else {
                    try {
                        val result = def.execute(ctx, block)
                        // The "next round" mark is used up; entering the loop later again
                        // (e.g. inside an outer loop) is a fresh start counted from 1.
                        if (def.kind == BlockKind.WhileStart) ctx.loopAgain.remove(block.id)

                        val entry = when (result) {
                            BlockResult.None -> LogEntry(block.id, def.title)
                            is BlockResult.Output -> LogEntry(block.id, def.title, output = shown(result.value))
                            is BlockResult.Condition -> LogEntry(
                                block.id, def.title,
                                output = shown(result.output) ?: if (result.passed) tr("splněno") else tr("nesplněno")
                            )
                            is BlockResult.Stop -> LogEntry(block.id, def.title, output = shown(result.output))
                            is BlockResult.LoopJump -> LogEntry(
                                block.id, def.title, output = if (result.exit) tr("konec opakování") else tr("další kolo")
                            )
                        }
                        if (result is BlockResult.Output) ctx.outputs[block.id] = result.value
                        if (result is BlockResult.Condition && result.passed && result.output != null) {
                            ctx.outputs[block.id] = result.output
                        }
                        record(entry)

                        when (result) {
                            BlockResult.None -> pc++
                            is BlockResult.Output -> pc++
                            is BlockResult.Condition -> {
                                val nextBranch = structure.nextBranch[pc]
                                pc = if (result.passed) {
                                    pc + 1
                                } else if (nextBranch != null) {
                                    pendingBranch = nextBranch
                                    nextBranch
                                } else {
                                    val target = structure.elseOf[pc] ?: structure.endOf[pc] ?: (blocks.size - 1)
                                    target + 1
                                }
                            }
                            is BlockResult.Stop -> return Outcome.Stop(result.output)
                            is BlockResult.LoopJump -> {
                                val loop = structure.loopOf[pc]
                                if (loop == null) {
                                    failure = Triple(tr("krok není uvnitř opakování"), null, false)
                                } else if (loop < from) {
                                    failure = Triple(tr("opakování, které začalo mimo souběžnou větev, z ní nejde ukončit"), null, false)
                                } else {
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
                        }
                    } catch (e: RunCancelledException) {
                        log += LogEntry(block.id, def.title, output = tr("zrušeno"))
                        return Outcome.Cancelled
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: BlockException) {
                        failure = Triple(e.message ?: "chyba", e.requirement, false)
                    } catch (e: SecurityException) {
                        failure = Triple(tr("chybí oprávnění (%s)", e.message), def.requirement, false)
                    } catch (e: Exception) {
                        failure = Triple(e.message ?: e.javaClass.simpleName, null, false)
                    }
                }

                val failed = failure
                if (failed != null) {
                    val (message, requirement, named) = failed
                    val full = if (named) message else "${tr(def.title)}: $message"
                    val entry = LogEntry(block.id, def.title, error = ctx.mask(message))
                    log += entry
                    val next = caught(full) ?: return Outcome.Failed(full, requirement)
                    // A caught error pauses step-by-step runs too, so it can be seen on the step.
                    afterStep?.invoke(entry, ctx)
                    pc = next
                }
            }
            return Outcome.Done
        }
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
