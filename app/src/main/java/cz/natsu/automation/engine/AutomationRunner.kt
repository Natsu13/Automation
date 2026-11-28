package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr
import cz.natsu.automation.data.HomeCards
import cz.natsu.automation.data.LastRun
import cz.natsu.automation.data.RunRegistry
import kotlinx.coroutines.ensureActive

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
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

/** Names of the "Jeden po druhém" locks the running steps are inside of. */
private class HeldLocks(val names: Set<String>) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<HeldLocks>
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
    /** Identifies the run: cards on the home screen belong to it and their buttons can stop it. */
    val runId: String = java.util.UUID.randomUUID().toString(),
) {
    // Branches of "Souběžně" run at the same time, so the state of a run is thread-safe.
    var variables: MutableMap<String, String> = ConcurrentHashMap()
        private set
    val outputs: MutableMap<String, String> = ConcurrentHashMap()

    /** Output of the step that ran last here; the result of a round of a parallel loop. */
    @Volatile
    var lastOutput: String? = null

    /**
     * The state for one round of a loop whose rounds run at the same time. Named variables
     * and secrets are shared with the run; step outputs and loop positions are a copy, so
     * every round has its own item and position and the rounds do not overwrite each other.
     */
    fun fork(): RunContext = RunContext(context, input, ui, trigger, event, automationId, depth, runId).also {
        it.variables = variables
        it.secretValues = secretValues
        it.locks = locks
        it.outputs.putAll(outputs)
        it.loopAgain.addAll(loopAgain)
        it.loopState.putAll(loopState)
        it.loopRound.putAll(loopRound)
        it.loopOf = loopOf
        it.switchValues.putAll(switchValues)
        it.htmlWindow = htmlWindow
        it.widgetStack = widgetStack
        it.widgetPreview = widgetPreview
    }

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

    /** Locks of "Jeden po druhém" by name; shared by all rounds and branches of the run. */
    private var locks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    /**
     * Runs [body] while nothing else in this run is inside a lock called [name]: rounds and
     * branches that run at the same time take turns here. Steps already inside the same
     * lock go straight on, so a lock inside itself does not wait for itself forever.
     */
    suspend fun <T> locked(name: String, body: suspend () -> T): T {
        val held = currentCoroutineContext()[HeldLocks]?.names.orEmpty()
        if (name in held) return body()
        return locks.getOrPut(name) { Mutex() }.withLock {
            withContext(HeldLocks(held + name)) { body() }
        }
    }

    /** Secret values used in this run; they are masked in the log. */
    private var secretValues: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Hides [value] in the log from now on (a password typed in, a key made from it). */
    fun markSecret(value: String) {
        if (value.length >= 3) secretValues += value
    }

    /** The "Okno s HTML" the steps run inside of, if any ("Poslat do okna" talks to it). */
    @Volatile
    var htmlWindow: HtmlWindow? = null

    /**
     * Set for a preview in the editor: the widget blocks hand the finished widget (and its size in bytes)
     * here instead of sending it to the home screen.
     */
    @Volatile
    var widgetPreview: ((android.widget.RemoteViews, Int) -> Unit)? = null

    /** Parts of a widget being built by "Widget z bloků"; the last one is open (see WidgetBuilder). */
    @Volatile
    var widgetStack: MutableList<WidgetBuilder.Node>? = null

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
                // Drawing into images: img_new, img_rect, img_text… (each returns a new picture file).
                ImageScript.call(context, name, args).let { if (it !== Script.MISSING) return it }
                val explicit = name == "blok" || name == "block"
                val type = if (explicit) Script.text(args.firstOrNull()) else name
                val def = Blocks.get(type)
                    ?: if (explicit) throw BlockException(tr("blok \"%s\" neexistuje", type)) else return Script.MISSING
                return callBlock(def, if (explicit) args.drop(1) else args, job)
            }

            override fun htmlWindow(args: List<Any?>, handle: (request: String) -> Any?): Any? {
                val block = blockFor(Blocks.get(HtmlBlocks.WINDOW)!!, args)
                // Runs on the script's thread: the page's calls are handled here too, one after another.
                return kotlinx.coroutines.runBlocking(job ?: kotlin.coroutines.EmptyCoroutineContext) {
                    HtmlBlocks.open(this@RunContext, block) { request -> Script.text(handle(request)) }
                }
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
        val block = blockFor(def, args)
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

    /** A block of [def] with the values of a script call: by name in an object, or one after another. */
    private fun blockFor(def: BlockDef, args: List<Any?>): Block {
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
        return def.newBlock().let { it.copy(params = it.params + params) }
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
        /**
         * "Ukončit / přeskočit kolo" of the loop at index [loop], which started before the
         * range: the range ends here and whoever ran it does the jump.
         */
        class Jump(val loop: Int, val exit: Boolean) : Outcome
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
        /** A preview of a widget in the editor (see [RunContext.widgetPreview]). */
        widgetPreview: ((android.widget.RemoteViews, Int) -> Unit)? = null,
    ): RunResult = withContext(Dispatchers.Default) {
        val ctx = RunContext(context.applicationContext, input, ui, trigger, event, automationId, depth)
        ctx.widgetPreview = widgetPreview
        val structure = Structure.analyze(blocks)
        if (!structure.valid) {
            return@withContext RunResult(
                null, emptyList(), tr("Podmínky, opakování nebo Zkusit nejsou správně uzavřené (Konec).")
            )
        }
        ctx.loopOf = structure.loopOf.entries.associate { (step, loop) -> blocks[step].id to blocks[loop].id }

        automationId?.let { LastRun.mark(ctx.context, it) }
        val run = Run(blocks, ctx, structure, onStep, afterStep)
        // Registered, so a button of a card on the home screen can stop the run.
        val outcome = kotlinx.coroutines.coroutineScope {
            val work = async { run.execute(0, blocks.size) }
            RunRegistry.register(ctx.runId, work)
            try {
                work.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Stopped from outside (the Stop button of the editor): pass it on; stopped by a card: a cancelled run.
                ensureActive()
                Outcome.Cancelled
            } finally {
                RunRegistry.unregister(ctx.runId)
                HomeCards.runEnded(ctx.context, ctx.runId)
            }
        }
        val log = synchronized(run.log) { run.log.toList() }
        when (outcome) {
            Outcome.Done -> RunResult(null, log)
            is Outcome.Stop -> RunResult(outcome.output, log)
            is Outcome.Failed -> RunResult(null, log, ctx.mask(outcome.message), outcome.requirement)
            Outcome.Cancelled -> RunResult(null, log, cancelled = true)
            is Outcome.Jump -> RunResult(null, log, tr("krok není uvnitř opakování"))
        }
    }

    /** One run of an automation; [execute] runs a range of its steps (the whole list, one parallel branch or round, the inside of a group). */
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

        private suspend fun record(entry: LogEntry, ctx: RunContext) {
            log += entry
            afterStep?.invoke(entry, ctx)
        }

        /** Runs the steps [from] until [until] (exclusive); [ctx] = the state they work with (a round of a parallel loop has its own). */
        suspend fun execute(from: Int, until: Int, ctx: RunContext = this.ctx): Outcome {
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
                    var wait = (ctx.number(tryBlock, "delay") * 1000).toLong()
                    // "Prodlužovat pauzu": 2 s, 4 s, 8 s … so a server that is down gets time to recover.
                    if (ctx.bool(tryBlock, "backoff")) wait = wait shl (frame.attempt - 2).coerceIn(0, 10)
                    if (wait > 0) kotlinx.coroutines.delay(wait)
                    return frame.start + 1
                }
                tries.removeLast()
                ctx.outputs[tryBlock.id] = message
                record(LogEntry(tryBlock.id, Blocks.get(tryBlock.type)?.title ?: "", output = shown(tr("zachyceno: %s", message))), ctx)
                return (structure.elseOf[frame.start] ?: structure.endOf[frame.start] ?: (blocks.size - 1)) + 1
            }

            var pc = from

            /**
             * A step asks for the next round ([exit] = the end) of the loop at index [loop].
             * A loop that started before this range is not ours to jump in: the range ends
             * with the returned outcome and whoever ran it goes on from there.
             */
            fun jumpTo(loop: Int, exit: Boolean): Outcome? {
                if (loop < from) return Outcome.Jump(loop, exit)
                val end = structure.endOf[loop] ?: (blocks.size - 1)
                // Leaving the loop's body also leaves any "Zkusit" inside it.
                tries.removeAll { it.start > loop }
                if (exit) {
                    ctx.loopState.remove(blocks[loop].id)
                    pc = end + 1
                } else {
                    pc = end
                }
                return null
            }

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
                    record(LogEntry(block.id, def.title, output = tr("vypnuto – přeskočeno"), skipped = true), ctx)
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
                    record(LogEntry(block.id, def.title, output = tr("větví: %s", ranges.size)), ctx)
                    val outcomes: List<Outcome> = if (afterStep != null) {
                        // Step-by-step: one branch after another, so each step can be looked at.
                        buildList {
                            for ((a, b) in ranges) {
                                val outcome = execute(a, b, ctx)
                                add(outcome)
                                if (outcome != Outcome.Done) break
                            }
                        }
                    } else {
                        coroutineScope { ranges.map { (a, b) -> async { execute(a, b, ctx) } }.awaitAll() }
                    }
                    outcomes.firstOrNull { it is Outcome.Stop }?.let { return it }
                    outcomes.firstOrNull { it == Outcome.Cancelled }?.let { return it }
                    val failed = outcomes.firstNotNullOfOrNull { it as? Outcome.Failed }
                    if (failed != null) {
                        failure = Triple(failed.message, failed.requirement, true)
                    } else if (outcomes.any { it is Outcome.Jump }) {
                        failure = Triple(tr("opakování, které začalo mimo souběžnou větev, z ní nejde ukončit"), null, false)
                    } else {
                        pc = end + 1
                        continue
                    }
                } else if (def.kind == BlockKind.WhileStart && DataBlocks.isParallel(ctx, block)) {
                    // A loop with "Souběžně" on: all rounds run at the same time, each with its own
                    // copy of the step outputs (its item, its position), so the rounds do not mix.
                    val end = structure.endOf[pc] ?: (blocks.size - 1)
                    val body = pc + 1
                    val items = try {
                        DataBlocks.parallelItems(ctx, block)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = Triple(e.message ?: e.javaClass.simpleName, (e as? BlockException)?.requirement, false)
                        null
                    }
                    if (items != null) {
                        record(LogEntry(block.id, def.title, output = tr("souběžně, kol: %s", items.size)), ctx)
                        val rounds = items.mapIndexed { index, item ->
                            ctx.fork().also {
                                it.outputs[block.id] = item
                                it.loopRound[block.id] = index + 1
                            }
                        }
                        val loopAt = pc
                        /** A round that does not just finish (or skip to its end) ends the loop for all rounds. */
                        fun endsLoop(outcome: Outcome) =
                            outcome != Outcome.Done && !(outcome is Outcome.Jump && outcome.loop == loopAt && !outcome.exit)

                        val results = java.util.concurrent.atomic.AtomicReferenceArray<Outcome?>(rounds.size)
                        /** Index of the round that ended the loop: an error, "Ukončit", "Vrátit výsledek". */
                        val ender = AtomicInteger(-1)
                        if (afterStep != null) {
                            // Step-by-step: one round after another, so each step can be looked at.
                            for ((index, round) in rounds.withIndex()) {
                                val outcome = execute(body, end, round)
                                results.set(index, outcome)
                                if (endsLoop(outcome)) {
                                    ender.set(index)
                                    break
                                }
                            }
                        } else {
                            val gate = Semaphore(ctx.number(block, "limit").toInt().coerceAtLeast(1))
                            val gap = (ctx.number(block, "gap") * 1000).toLong()
                            coroutineScope {
                                val jobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
                                rounds.forEachIndexed { index, round ->
                                    // Undispatched: the rounds queue up for their turn in the order of the items.
                                    jobs += launch(start = CoroutineStart.UNDISPATCHED) {
                                        if (gap > 0) kotlinx.coroutines.delay(gap * index)
                                        gate.withPermit {
                                            // On to a thread of its own, so the rounds really run side by side.
                                            kotlinx.coroutines.yield()
                                            // The loop has ended while this round waited for its turn.
                                            if (ender.get() >= 0) return@withPermit
                                            val outcome = execute(body, end, round)
                                            results.set(index, outcome)
                                            // The rest has nothing to finish for: stop it instead of waiting for it.
                                            if (endsLoop(outcome) && ender.compareAndSet(-1, index)) {
                                                for (job in jobs) job.cancel()
                                            }
                                        }
                                    }
                                }
                                // Rounds started after the loop was ended were not there to be stopped.
                                if (ender.get() >= 0) for (job in jobs) job.cancel()
                            }
                        }

                        val endedBy = ender.get()
                        val ending = if (endedBy >= 0) results.get(endedBy) else null
                        if (ending is Outcome.Stop || ending == Outcome.Cancelled) return ending!!
                        if (ending is Outcome.Failed) {
                            failure = Triple(ending.message, ending.requirement, true)
                        } else {
                            // After the loop its steps keep the values of the last round, as in a loop one
                            // by one – or of the round that ended it ("the first one that found it").
                            (if (endedBy >= 0) rounds[endedBy] else rounds.lastOrNull())?.let { last ->
                                for (i in body until end) last.outputs[blocks[i].id]?.let { ctx.outputs[blocks[i].id] = it }
                            }
                            val into = ctx.text(block, "collect").trim()
                            if (into.isNotEmpty()) {
                                // A round stopped before its end has no result: its place stays empty.
                                ctx.variables[into] = JsonTools.fromList(
                                    rounds.mapIndexed { index, round -> if (results.get(index) == null) "" else round.lastOutput ?: "" }
                                )
                            }
                            // "Ukončit" inside a round ended this loop; a jump further out is passed on.
                            if (ending is Outcome.Jump && ending.loop != loopAt) {
                                jumpTo(ending.loop, ending.exit)?.let { return it }
                            } else {
                                pc = end + 1
                            }
                            continue
                        }
                    }
                } else if (def.kind == BlockKind.Scope) {
                    // "Jeden po druhém", "Časový limit": the steps inside run as a range of their own, under the block's rule.
                    val end = structure.endOf[pc] ?: (blocks.size - 1)
                    val inside = pc + 1
                    try {
                        record(LogEntry(block.id, def.title), ctx)
                        val outcome = if (block.type == HtmlBlocks.WINDOW) {
                            // The steps inside run once for every call of the page; their result is the answer.
                            val closedWith = HtmlBlocks.open(ctx, block) { request ->
                                ctx.outputs[block.id] = request
                                ctx.lastOutput = null
                                when (val step = execute(inside, end, ctx)) {
                                    Outcome.Done -> ctx.lastOutput ?: ""
                                    is Outcome.Stop -> step.output ?: ""
                                    is Outcome.Failed -> throw BlockException(step.message)
                                    Outcome.Cancelled -> throw RunCancelledException()
                                    is Outcome.Jump -> throw BlockException(tr("opakování, které začalo mimo okno, z něj nejde ukončit"))
                                }
                            }
                            ctx.outputs[block.id] = closedWith
                            ctx.lastOutput = closedWith
                            record(LogEntry(block.id, def.title, output = shown(tr("zavřeno: %s", closedWith))), ctx)
                            Outcome.Done
                        } else if (block.type in WidgetBuilder.GROUPS) {
                            // Parts of a widget: the steps inside add to the part, "Widget z bloků" sends it at the end.
                            var inner: Outcome = Outcome.Done
                            WidgetBuilder.group(ctx, block) { inner = execute(inside, end, ctx); inner == Outcome.Done }?.let {
                                ctx.outputs[block.id] = it
                                ctx.lastOutput = it
                            }
                            inner
                        } else {
                            ControlBlocks.scoped(ctx, block, stepping = afterStep != null) { execute(inside, end, ctx) }
                        }
                        when (outcome) {
                            Outcome.Done -> pc = end + 1
                            is Outcome.Stop, Outcome.Cancelled -> return outcome
                            is Outcome.Jump -> jumpTo(outcome.loop, outcome.exit)?.let { return it }
                            is Outcome.Failed -> failure = Triple(outcome.message, outcome.requirement, true)
                        }
                    } catch (e: RunCancelledException) {
                        log += LogEntry(block.id, def.title, output = tr("zrušeno"))
                        return Outcome.Cancelled
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failure = Triple(e.message ?: e.javaClass.simpleName, (e as? BlockException)?.requirement, false)
                    }
                    if (failure == null) continue
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
                        if (result is BlockResult.Output) {
                            ctx.outputs[block.id] = result.value
                            ctx.lastOutput = result.value
                        }
                        if (result is BlockResult.Condition && result.passed && result.output != null) {
                            ctx.outputs[block.id] = result.output
                        }
                        record(entry, ctx)

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
                                } else {
                                    jumpTo(loop, result.exit)?.let { return it }
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
        /** The automation the conditions belong to ("Hodin od posledního spuštění"). */
        automationId: Long? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        val active = conditions.filterNot { it.disabled }
        if (active.isEmpty()) return@withContext true
        val ctx = RunContext(context.applicationContext, input, null, trigger, event, automationId)
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
