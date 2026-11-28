package cz.natsu.automation.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cz.natsu.automation.AutomationApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One run planned by "Naplánovat spuštění". */
@Serializable
data class ScheduledRun(
    /** The name of the plan; a new plan with the same name replaces it. */
    val id: String,
    val at: Long,
    val automationId: Long,
    val input: String = "",
    /** Planned against the countdown of this card: dropped when the card goes or gets another countdown. */
    val cardId: String? = null,
    val cardEnd: Long? = null,
)

/**
 * Runs planned for a moment: in a while, at a time, or at a point of a card's countdown.
 * Each one is an alarm, so it runs also while the app is closed; the list is saved, so the
 * alarms can be planned again after a reboot.
 */
object ScheduledRuns {
    private const val PREFS = "scheduled_runs"
    private const val KEY = "runs"
    private const val EXTRA_ID = "id"
    private const val EXTRA_AT = "at"

    /** The label of a planned run ("Typ spuštění"). */
    const val LABEL = "Naplánované spuštění"

    private val serializer = ListSerializer(ScheduledRun.serializer())
    private val lock = Any()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<ScheduledRun> = synchronized(lock) {
        try {
            BlockJson.json.decodeFromString(serializer, prefs(context).getString(KEY, "[]") ?: "[]")
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, runs: List<ScheduledRun>) {
        prefs(context).edit().putString(KEY, BlockJson.json.encodeToString(serializer, runs)).apply()
    }

    private fun intent(context: Context, id: String) =
        Intent(context, ScheduledRunReceiver::class.java).setAction("cz.natsu.automation.SCHEDULED.$id")

    private fun alarm(context: Context, run: ScheduledRun) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, run.id.hashCode(),
            intent(context, run.id).putExtra(EXTRA_ID, run.id).putExtra(EXTRA_AT, run.at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // One planned while the phone was off runs as soon as it can.
        val time = run.at.coerceAtLeast(System.currentTimeMillis() + 500)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    private fun unalarm(context: Context, id: String) {
        PendingIntent.getBroadcast(
            context, id.hashCode(), intent(context, id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it)
            it.cancel()
        }
    }

    /** Plans [run], replacing a plan with the same name. */
    fun add(context: Context, run: ScheduledRun) {
        synchronized(lock) { write(context, all(context).filter { it.id != run.id } + run) }
        alarm(context, run)
    }

    /** Cancels the plan called [id]; false when there was none. */
    fun cancel(context: Context, id: String): Boolean {
        val had = synchronized(lock) {
            val runs = all(context)
            write(context, runs.filter { it.id != id })
            runs.any { it.id == id }
        }
        unalarm(context, id)
        return had
    }

    fun dropForCard(context: Context, cardId: String) {
        for (run in all(context).filter { it.cardId == cardId }) cancel(context, run.id)
    }

    /** Plans the alarms again (they are gone after a reboot); harmless when they are still there. */
    fun rescheduleAll(context: Context) {
        for (run in all(context)) alarm(context, run)
    }

    /** The alarm of a plan went off: runs its automation, unless the plan changed or its card is gone. */
    internal fun fire(context: Context, intent: Intent): Job? {
        val app = context.applicationContext
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        val run = all(app).firstOrNull { it.id == id && it.at == intent.getLongExtra(EXTRA_AT, -1L) } ?: return null
        synchronized(lock) { write(app, all(app).filter { it.id != id }) }
        if (run.cardId != null) {
            HomeCards.load(app)
            if (HomeCards.get(run.cardId)?.countdownEnd != run.cardEnd) return null
        }
        val repository = (app as AutomationApplication).repository
        return repository.appScope.launch {
            val automation = repository.get(run.automationId)?.takeIf { it.startsByItself } ?: return@launch
            cz.natsu.automation.trigger.EventDispatcher.runNow(app, automation, run.input, LABEL).join()
        }
    }
}

/** A planned run is due. */
class ScheduledRunReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val job = ScheduledRuns.fire(context, intent) ?: return
        val pending = goAsync()
        // The run goes on in the app scope; the receiver only keeps the process up for a while.
        (context.applicationContext as AutomationApplication).repository.appScope.launch {
            try {
                withTimeoutOrNull(9_000) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }
}
