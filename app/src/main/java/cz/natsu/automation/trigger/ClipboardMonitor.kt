package cz.natsu.automation.trigger

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Reacting to clipboard changes while the app is in the background.
 *
 * Since Android 10 only the focused app (or the keyboard) may read the clipboard,
 * and clipboard listeners of background apps are not called. When the clipboard
 * changes, the system logs "Denying clipboard access to <package>" for every
 * registered listener it skipped. With READ_LOGS (granted once over ADB) the
 * [BackgroundService] watches the log for that line and opens the invisible
 * [TriggerActivity], which gets focus, reads the clipboard and runs the
 * automations. Opening an activity from the background needs "display over other apps".
 */
object ClipboardMonitor {
    private const val PREFS = "settings"
    private const val KEY_ENABLED = "clipboard_monitor"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        sync(context)
    }

    fun hasLogAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun canOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun ready(context: Context): Boolean = hasLogAccess(context) && canOverlay(context)

    /** Whether the clipboard part of the background service should run. */
    fun active(context: Context): Boolean = isEnabled(context) && ready(context)

    /**
     * Starts or stops the background service to match the settings. Safe to call often.
     * [fromForeground] = the app is on screen (lets a background-started log reader get full access).
     */
    fun sync(context: Context, fromForeground: Boolean = false) = BackgroundService.sync(context, fromForeground)

    /** Whether the service is running and reading the log. */
    val running: Boolean get() = BackgroundService.logReaderAlive

    // ---------- de-duplication ----------

    @Volatile
    private var lastText: String? = null

    /** Returns true the first time a clipboard text is seen. */
    @Synchronized
    fun isNew(text: String): Boolean {
        if (text == lastText) return false
        lastText = text
        return true
    }

    /** Called when the app itself writes to the clipboard, so that the change doesn't start automations. */
    @Synchronized
    fun markOwnWrite(text: CharSequence?) {
        lastText = text?.toString()
    }
}
