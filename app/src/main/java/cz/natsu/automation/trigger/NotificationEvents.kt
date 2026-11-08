package cz.natsu.automation.trigger

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.EventType
import kotlinx.coroutines.launch

/** "Příchozí oznámení" event: needs notification access, granted by the user in system settings. */
object NotificationEvents {
    fun hasAccess(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    private fun component(context: Context) = ComponentName(context, NotificationEventService::class.java)

    /** Binds the listener again once an automation uses the event (it unbinds itself when none does). */
    fun sync(context: Context, all: List<AutomationEntity>) {
        val used = all.any { automation -> automation.events.any { it.type == EventType.Notification.key } }
        if (used && hasAccess(context)) {
            try {
                NotificationListenerService.requestRebind(component(context))
            } catch (_: Exception) {
            }
        }
    }
}

class NotificationEventService : NotificationListenerService() {
    private var lastKey: String? = null
    private var lastAt = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        val repository = (application as AutomationApplication).repository
        repository.appScope.launch {
            // Nobody reacts to notifications: don't stay bound (saves battery).
            if (EventType.Notification !in repository.usedEventTypes()) requestUnbind()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        if (sbn.isOngoing || notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // Apps often re-post the same notification; react once.
        val key = "${sbn.key}|$title|$text"
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastAt < 5_000) return
        lastKey = key
        lastAt = now

        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }
        EventDispatcher.fire(
            this, EventType.Notification,
            mapOf(
                "notif_app" to app,
                "notif_package" to sbn.packageName,
                "notif_title" to title,
                "notif_text" to text,
            )
        )
    }
}
