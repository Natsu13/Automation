package cz.natsu.automation.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** One line of the debug log: an event that came, or what an automation did with it. */
@Serializable
data class EventLogEntry(
    val time: Long,
    /** Title of the event ("Přiložení NFC štítku"). */
    val event: String,
    /** [EventLog.CAME], [EventLog.STARTED] or [EventLog.SKIPPED]. */
    val kind: String,
    /** How many automations wait for the event ([EventLog.CAME]). */
    val waiting: Int = 0,
    /** Name of the automation ([EventLog.STARTED], [EventLog.SKIPPED]). */
    val automation: String = "",
    /** What the event brought ("nfc_id" to "04:A2:…"). */
    val values: Map<String, String> = emptyMap(),
)

/**
 * Debug log of events, off unless the user switches it on in the settings: every event
 * that reached the app, what it brought, how many automations wait for it and which of
 * them started. Stays in the phone; the values can be private (a text message, a notification).
 */
object EventLog {
    const val CAME = "came"
    const val STARTED = "started"
    const val SKIPPED = "skipped"

    private const val FILE = "event_log.json"
    private const val PREFS = "event_log"
    private const val KEY_ENABLED = "enabled"
    private const val MAX = 300
    private const val MAX_VALUE = 500

    private val serializer = ListSerializer(EventLogEntry.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    @Volatile
    private var enabled: Boolean? = null

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun isEnabled(context: Context): Boolean = enabled
        ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false).also { enabled = it }

    /** Switching the log off also throws away what it holds. */
    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) clear(context)
    }

    fun all(context: Context): List<EventLogEntry> = synchronized(lock) {
        try {
            BlockJson.json.decodeFromString(serializer, file(context).readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    /** An event reached the app; [waiting] automations react to it. */
    fun came(context: Context, event: String, values: Map<String, String>, waiting: Int) =
        add(context, EventLogEntry(System.currentTimeMillis(), event, CAME, waiting = waiting, values = values))

    /** [automation] started for the event, or its launch conditions did not hold ([started] = false). */
    fun decided(context: Context, event: String, automation: String, started: Boolean) =
        add(context, EventLogEntry(System.currentTimeMillis(), event, if (started) STARTED else SKIPPED, automation = automation))

    private fun add(context: Context, entry: EventLogEntry) {
        if (!isEnabled(context)) return
        val app = context.applicationContext
        val short = entry.copy(values = entry.values.mapValues { it.value.take(MAX_VALUE) })
        scope.launch {
            synchronized(lock) {
                try {
                    file(app).writeText(BlockJson.json.encodeToString(serializer, (listOf(short) + all(app)).take(MAX)))
                } catch (e: Exception) {
                    // Only a debug aid; a full disk must not break an event.
                }
            }
        }
    }

    /** The whole log as plain text, newest first, for copying. */
    fun text(entries: List<EventLogEntry>): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        return entries.joinToString("\n") { entry ->
            format.format(java.util.Date(entry.time)) + "  " + when (entry.kind) {
                CAME -> "EVENT ${entry.event}  waiting=${entry.waiting}" +
                        entry.values.entries.joinToString("") { "\n    ${it.key} = ${it.value}" }
                STARTED -> "  STARTED ${entry.automation}  (${entry.event})"
                else -> "  CONDITIONS NOT MET ${entry.automation}  (${entry.event})"
            }
        }
    }
}
