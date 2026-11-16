package cz.natsu.automation.trigger

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import cz.natsu.automation.AutomationApplication
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Countdowns of "Oznámení s tlačítky". The notification draws the running time itself;
 * here is only when each countdown ends (for "Zbývající čas odpočtu") and the alarm
 * that runs an automation at that moment, if the block asks for one.
 */
object NotificationTimers {
    private const val PREFS = "notification_timers"
    private const val EXTRA_ID = "id"
    private const val EXTRA_END = "end"
    private const val EXTRA_AUTOMATION = "automation"
    private const val EXTRA_INPUT = "input"

    /** The label of a run started by a finished countdown ("Typ spuštění"). */
    const val LABEL = "Konec odpočtu v oznámení"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notifications with the same name replace each other, whichever automation shows them. */
    fun idOf(name: String): Int = "name:$name".hashCode()

    /** "90", "1:30" or "1:00:00" as seconds; null = not a time. */
    fun parse(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            val number = part.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (number < 0) return null
            seconds = seconds * 60 + number
        }
        return seconds.toLong().coerceAtMost(7 * 24 * 3600L)
    }

    private fun pending(context: Context, id: Int, intent: Intent, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, id, intent, flags or PendingIntent.FLAG_IMMUTABLE)

    private fun intent(context: Context, id: Int) =
        Intent(context, NotificationTimerReceiver::class.java).setAction("cz.natsu.automation.TIMER.$id")

    /** The countdown of notification [id] ends at [end]; [automation] (if any) runs then with [input]. */
    fun set(context: Context, id: Int, end: Long, automation: Long?, input: String) {
        clear(context, id)
        prefs(context).edit().putLong("end_$id", end).apply()
        automation ?: return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pending(
            context, id,
            intent(context, id).putExtra(EXTRA_ID, id).putExtra(EXTRA_END, end)
                .putExtra(EXTRA_AUTOMATION, automation).putExtra(EXTRA_INPUT, input),
            PendingIntent.FLAG_UPDATE_CURRENT,
        ) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        }
    }

    /** Forgets the countdown of notification [id] and what was to run at its end. */
    fun clear(context: Context, id: Int) {
        prefs(context).edit().remove("end_$id").apply()
        pending(context, id, intent(context, id), PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it)
            it.cancel()
        }
    }

    /** Seconds left of the countdown of notification [id]; 0 when it has none or it is over. */
    fun remaining(context: Context, id: Int, now: Long = System.currentTimeMillis()): Long =
        ((prefs(context).getLong("end_$id", 0L) - now + 999) / 1000).coerceAtLeast(0)

    /** Whether the alarm in [intent] still belongs to a countdown that is running and on the screen. */
    internal fun finish(context: Context, intent: Intent): Triple<Int, Long, String>? {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        if (prefs(context).getLong("end_$id", 0L) != intent.getLongExtra(EXTRA_END, -1L)) return null
        prefs(context).edit().remove("end_$id").apply()
        // A notification that was swiped away or closed by one of its buttons is a cancelled countdown.
        val shown = context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications?.any { it.id == id } == true
        if (!shown) return null
        NotificationManagerCompat.from(context).cancel(id)
        return Triple(id, intent.getLongExtra(EXTRA_AUTOMATION, -1L), intent.getStringExtra(EXTRA_INPUT).orEmpty())
    }
}

/** The countdown of a notification ended: closes it and runs the automation chosen in the block. */
class NotificationTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val (_, automationId, input) = NotificationTimers.finish(app, intent) ?: return
        val repository = (app as AutomationApplication).repository
        val pending = goAsync()
        repository.appScope.launch {
            try {
                val automation = repository.get(automationId)?.takeIf { !it.needsReview } ?: return@launch
                // The run goes on in the app scope; the receiver just stops waiting.
                withTimeoutOrNull(9_000) { EventDispatcher.runNow(app, automation, input, NotificationTimers.LABEL).join() }
            } finally {
                pending.finish()
            }
        }
    }
}
