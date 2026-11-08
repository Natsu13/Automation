package cz.natsu.automation.trigger

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/**
 * Buttons of the "Oznámení s tlačítky" block that need no window: copying text
 * (Android lets apps write the clipboard from the background, only reading is
 * restricted) and closing the notification.
 */
class NotificationButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        NotificationManagerCompat.from(context).cancel(id)
        val text = intent.getStringExtra(EXTRA_COPY) ?: return
        // Copying from our own notification must not start the clipboard automations.
        ClipboardMonitor.markOwnWrite(text)
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Automatizace", text))
        // Android 13+ shows its own confirmation of a copy.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Zkopírováno", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_ID = "cz.natsu.automation.extra.NOTIFICATION_ID"
        private const val EXTRA_COPY = "cz.natsu.automation.extra.COPY"

        /** [copy] = text to put into the clipboard; null only closes the notification. */
        fun intent(context: Context, notificationId: Int, copy: String?): Intent =
            Intent(context, NotificationButtonReceiver::class.java)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                .apply { if (copy != null) putExtra(EXTRA_COPY, copy) }
    }
}
