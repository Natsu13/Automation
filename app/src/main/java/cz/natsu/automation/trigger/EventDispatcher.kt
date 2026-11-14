package cz.natsu.automation.trigger

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.EventTrigger
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.AudioHardening
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.EventInfo
import cz.natsu.automation.engine.RunTrigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the automations that react to a system event.
 *
 * Automations without dialogs or clipboard reading run right here in the
 * background. The rest open the invisible [TriggerActivity] (needs "display over
 * other apps"), because Android only lets the focused app show dialogs and read
 * the clipboard.
 */
object EventDispatcher {
    private const val TAG = "EventDispatcher"

    /** Blocks that need a window: dialogs, reading the clipboard, opening other apps. */
    private val windowBlocks = setOf("ask_text", "choose", "show_text", "get_clipboard", "share", "web", "open_url", "pick_file", "show_images")

    /**
     * Android 17+ silently ignores volume and ringer changes from an app in the
     * background (unless it has a "while in use" foreground service, which ours
     * started from the background does not get). With a visible window they work.
     */
    private val audioBlocks = setOf("volume", "ringer")

    /**
     * Fires [type]. [matches] can narrow which of the automation's events of this
     * type apply (e.g. the battery threshold). Returns the job, so a receiver can wait for it.
     */
    fun fire(
        context: Context,
        type: EventType,
        values: Map<String, String> = emptyMap(),
        matches: (EventTrigger) -> Boolean = { true },
    ): Job {
        val app = context.applicationContext
        val repository = (app as AutomationApplication).repository
        return repository.appScope.launch {
            val event = EventInfo(type.title, values)
            val input = inputOf(type, values)
            val candidates = repository.withEvent(type).filter { (_, events) -> events.any(matches) }
            if (candidates.isEmpty()) return@launch
            UsageMeter.countEvent(app)
            for ((automation, _) in candidates) runIfConditions(app, automation, input, event)
            UsageMeter.sample(app)
        }
    }

    /** Fires [type] for one automation only (a planned time event). */
    fun fireFor(context: Context, automation: AutomationEntity, type: EventType): Job {
        val app = context.applicationContext
        val repository = (app as AutomationApplication).repository
        return repository.appScope.launch {
            UsageMeter.countEvent(app)
            runIfConditions(app, automation, "", EventInfo(type.title))
            UsageMeter.sample(app)
        }
    }

    private suspend fun runIfConditions(context: Context, automation: AutomationEntity, input: String, event: EventInfo) {
        val ok = AutomationRunner.conditionsMet(
            context, automation.conditions, automation.conditionsAny, input, RunTrigger.Event, event
        )
        if (!ok) return
        UsageMeter.countRun(context)
        run(context, automation, input, event)
    }

    private suspend fun run(context: Context, automation: AutomationEntity, input: String, event: EventInfo) {
        val blocks = automation.blocks
        if (needsWindow(blocks) && ClipboardMonitor.canOverlay(context)) {
            Handler(Looper.getMainLooper()).post {
                try {
                    context.startActivity(TriggerActivity.eventIntent(context, automation.id, input, event))
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot open the run window", e)
                }
            }
            return
        }
        val result = AutomationRunner.run(context, blocks, input, ui = null, trigger = RunTrigger.Event, event = event)
        if (!result.success && !result.cancelled) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "${automation.name}: ${result.error}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** What "Vstup" contains for an event. */
    private fun inputOf(type: EventType, values: Map<String, String>): String = when (type) {
        EventType.WifiConnected, EventType.WifiDisconnected -> values["wifi_name"]
        EventType.BluetoothConnected, EventType.BluetoothDisconnected -> values["bt_device"]
        EventType.Notification -> values["notif_text"]
        EventType.DndChanged -> values["dnd"]
        EventType.PowerConnected, EventType.PowerDisconnected,
        EventType.BatteryBelow, EventType.BatteryAbove -> values["battery"]
        else -> null
    } ?: ""

    fun needsWindow(blocks: List<Block>): Boolean = blocks.any { block ->
        !block.disabled && (block.type in windowBlocks ||
                (block.type in audioBlocks && AudioHardening.active) ||
                block.params.values.any { value ->
            value is ParamValue.Rich && value.segments.any { (it as? Segment.Var)?.ref == VarRef.Clipboard }
        })
    }
}
