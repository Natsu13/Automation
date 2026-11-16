package cz.natsu.automation.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.EventTrigger
import cz.natsu.automation.data.EventType
import cz.natsu.automation.engine.Locator
import cz.natsu.automation.engine.Sun
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Time events ("V určený čas", "Opakovaně", sunrise and sunset) planned as system alarms – the app
 * does not need to run in between. Only the next occurrence of each event is
 * planned; it plans the following one when it fires.
 */
object EventAlarms {
    private const val PREFS = "alarms"
    private const val KEY_PLANNED = "planned"
    const val EXTRA_AUTOMATION = "automation"
    const val EXTRA_EVENT = "event"
    const val MIN_INTERVAL_MINUTES = 15

    private val timeTypes = setOf(EventType.TimeDaily, EventType.TimeInterval, EventType.Sunrise, EventType.Sunset)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Plans every time event of [all] automations and cancels alarms of removed ones. */
    fun scheduleAll(context: Context, all: List<AutomationEntity>) {
        val prefs = prefs(context)
        val planned = mutableSetOf<String>()
        for (automation in all) {
            for (event in automation.activeEvents) {
                val type = EventType.of(event.type)
                if (type !in timeTypes) continue
                planned += event.id
                val keep = prefs.getLong("next_${event.id}", 0L).takeIf {
                    // An interval keeps its rhythm when the automation is edited.
                    type == EventType.TimeInterval && it > System.currentTimeMillis() &&
                            prefs.getString("cfg_${event.id}", null) == event.param("minutes")
                }
                schedule(context, automation.id, event, keep)
            }
        }
        val edit = prefs.edit()
        for (old in prefs.getStringSet(KEY_PLANNED, emptySet()).orEmpty() - planned) {
            alarmManager(context).cancel(pendingIntent(context, 0, old))
            edit.remove("next_$old").remove("cfg_$old")
        }
        edit.putStringSet(KEY_PLANNED, planned).apply()
    }

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(context: Context, automationId: Long, eventId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            Intent(context, EventAlarmReceiver::class.java)
                .setAction("cz.natsu.automation.ALARM.$eventId")
                .putExtra(EXTRA_AUTOMATION, automationId)
                .putExtra(EXTRA_EVENT, eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Plans the next occurrence of [event]; [at] overrides the computed time. */
    fun schedule(context: Context, automationId: Long, event: EventTrigger, at: Long? = null) {
        val time = at ?: nextTime(event) ?: return
        val manager = alarmManager(context)
        val intent = pendingIntent(context, automationId, event.id)
        // Inexact alarms may come up to ~45 min late; exact ones (USE_EXACT_ALARM) are cheap at >= 15 min.
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        if (exact) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        }
        prefs(context).edit()
            .putLong("next_${event.id}", time)
            .putString("cfg_${event.id}", event.param("minutes"))
            .apply()
    }

    /** When [event] fires next, or null when it never does (no day chosen). */
    fun nextTime(event: EventTrigger, now: Long = System.currentTimeMillis()): Long? =
        when (val type = EventType.of(event.type)) {
            EventType.Sunrise, EventType.Sunset -> Locator.parse(event.param("place"))?.let { (lat, lon) ->
                Sun.next(lat, lon, rise = type == EventType.Sunrise, now = now)
            }
            EventType.TimeInterval -> {
                val minutes = (event.param("minutes").toIntOrNull() ?: 60).coerceAtLeast(MIN_INTERVAL_MINUTES)
                now + TimeUnit.MINUTES.toMillis(minutes.toLong())
            }
            EventType.TimeDaily -> {
                val (hour, minute) = parseTime(event.param("time")) ?: (7 to 0)
                val days = event.param("days").mapNotNull { it.digitToIntOrNull() }.filter { it in 1..7 }.toSet()
                if (days.isEmpty()) null else {
                    val calendar = Calendar.getInstance().apply {
                        timeInMillis = now
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    var result: Long? = null
                    for (i in 0..7) {
                        if (calendar.timeInMillis > now && isoDay(calendar) in days) {
                            result = calendar.timeInMillis
                            break
                        }
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                    }
                    result
                }
            }
            else -> null
        }

    /** 1 = Monday … 7 = Sunday. */
    private fun isoDay(calendar: Calendar): Int = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1

    fun parseTime(text: String): Pair<Int, Int>? {
        val parts = text.split(':')
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour to minute else null
    }

    fun nextPlanned(context: Context, eventId: String): Long? =
        prefs(context).getLong("next_$eventId", 0L).takeIf { it > 0 }
}

/** A planned time event is due: run its automation and plan the next occurrence. */
class EventAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val automationId = intent.getLongExtra(EventAlarms.EXTRA_AUTOMATION, -1L)
        val eventId = intent.getStringExtra(EventAlarms.EXTRA_EVENT) ?: return
        val app = context.applicationContext
        val repository = (app as AutomationApplication).repository
        val pending = goAsync()
        repository.appScope.launch {
            try {
                val automation = repository.get(automationId) ?: return@launch
                val event = automation.activeEvents.firstOrNull { it.id == eventId } ?: return@launch
                val type = EventType.of(event.type) ?: return@launch
                EventAlarms.schedule(app, automation.id, event)
                withTimeout(9_000) {
                    EventDispatcher.fireFor(app, automation, type).join()
                }
            } catch (_: TimeoutCancellationException) {
                // The run goes on in the app scope; the receiver just stops waiting.
            } finally {
                pending.finish()
            }
        }
    }
}
