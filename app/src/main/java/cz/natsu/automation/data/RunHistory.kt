package cz.natsu.automation.data

import android.content.Context
import cz.natsu.automation.engine.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** One finished run of an automation outside the editor. */
@Serializable
data class RunRecord(
    val time: Long,
    val automationId: Long,
    val name: String,
    /** How it was started ("Sdílení", "Připojení k Wi-Fi", ...). */
    val trigger: String,
    val outcome: String,
    /** Error text; secrets are already masked by the runner. */
    val error: String? = null,
    val durationMs: Long = 0,
    val steps: Int = 0,
) {
    companion object {
        const val OK = "ok"
        const val ERROR = "error"
        const val CANCELLED = "cancelled"
    }
}

/**
 * The last runs started by triggers and events, so a run in the background that
 * failed can be found later (its toast is easy to miss). Only the outcome and the
 * error are kept – no values of the steps.
 */
object RunHistory {
    private const val FILE = "run_history.json"
    private const val MAX = 100
    private const val MAX_ERROR = 300

    private val serializer = ListSerializer(RunRecord.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun all(context: Context): List<RunRecord> = synchronized(lock) {
        try {
            BlockJson.json.decodeFromString(serializer, file(context).readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    /** Remembers the outcome of a run that started at [startedAt]; returns immediately. */
    fun record(context: Context, automation: AutomationEntity, trigger: String, startedAt: Long, result: RunResult) {
        val record = RunRecord(
            time = startedAt,
            automationId = automation.id,
            name = automation.name,
            trigger = trigger,
            outcome = when {
                result.cancelled -> RunRecord.CANCELLED
                result.success -> RunRecord.OK
                else -> RunRecord.ERROR
            },
            error = result.error?.take(MAX_ERROR),
            durationMs = System.currentTimeMillis() - startedAt,
            steps = result.log.count { !it.skipped },
        )
        val app = context.applicationContext
        scope.launch {
            synchronized(lock) {
                try {
                    val records = (listOf(record) + all(app)).take(MAX)
                    file(app).writeText(BlockJson.json.encodeToString(serializer, records))
                } catch (e: Exception) {
                    // History is only informative; a full disk must not break a run.
                }
            }
        }
    }
}
