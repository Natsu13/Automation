package cz.natsu.automation.trigger

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import cz.natsu.automation.data.HomeCard
import cz.natsu.automation.data.HomeCards

/**
 * The end of a countdown on a card that should then disappear ("Po doběhnutí kartu odebrat").
 * The card draws the time left itself; running automations at given moments is the job of
 * the block "Naplánovat spuštění" ([cz.natsu.automation.data.ScheduledRuns]).
 */
object CardTimers {
    private const val EXTRA_CARD = "card"
    private const val EXTRA_END = "end"

    private fun intent(context: Context, card: String) =
        Intent(context, CardTimerReceiver::class.java).setAction("cz.natsu.automation.CARD_END.$card")

    private fun requestCode(card: String) = "card_end:$card".hashCode()

    fun schedule(context: Context, card: HomeCard) {
        cancel(context, card.id)
        val end = card.countdownEnd ?: return
        if (!card.removeAtEnd) return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context, requestCode(card.id),
            intent(context, card.id).putExtra(EXTRA_CARD, card.id).putExtra(EXTRA_END, end),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = end.coerceAtLeast(System.currentTimeMillis() + 500)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
        }
    }

    fun cancel(context: Context, card: String) {
        PendingIntent.getBroadcast(
            context, requestCode(card), intent(context, card),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let {
            context.getSystemService(AlarmManager::class.java)?.cancel(it)
            it.cancel()
        }
    }

    internal fun fire(context: Context, intent: Intent) {
        val app = context.applicationContext
        HomeCards.load(app)
        val id = intent.getStringExtra(EXTRA_CARD) ?: return
        // Only the countdown that planned the alarm: a card shown again since does not count.
        val card = HomeCards.get(id)?.takeIf { it.countdownEnd == intent.getLongExtra(EXTRA_END, -1L) } ?: return
        if (card.removeAtEnd) HomeCards.remove(app, id)
    }
}

/** The end of a countdown on a card. */
class CardTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        CardTimers.fire(context, intent)
    }
}
