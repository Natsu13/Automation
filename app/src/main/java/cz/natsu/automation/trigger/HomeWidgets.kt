package cz.natsu.automation.trigger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.MainActivity
import cz.natsu.automation.engine.NotificationBlock
import cz.natsu.automation.R
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.RunHistory
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.DeviceState
import cz.natsu.automation.engine.EventInfo
import cz.natsu.automation.engine.RunTrigger
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Widgets on the home screen. Every widget belongs to one automation that has the
 * "Vykreslení widgetu" event; the automation runs whenever the widget has to be drawn
 * and sends the content with the "Nastavit widget" block (see WidgetBlocks).
 */
object HomeWidgets {
    private const val TAG = "HomeWidgets"
    private const val PREFS = "home_widgets"
    /** How a run for a widget is shown ("Typ spuštění", run history). */
    const val LABEL = "Vykreslení widgetu"

    const val ACTION_REFRESH = "cz.natsu.automation.action.WIDGET_REFRESH"

    /** A tap on a part of a widget built from blocks; extras say what to do (see [tapIntent]). */
    const val ACTION_TAP = "cz.natsu.automation.action.WIDGET_TAP"
    const val EXTRA_TAP = "tap"
    const val EXTRA_INPUT = "input"
    const val EXTRA_AUTOMATION = "automation"
    const val EXTRA_LIST = "list"
    const val EXTRA_ITEM = "item"

    /** The list (1..) and item tapped last per widget, so the next drawing can scroll back to it. */
    private val scrolls = ConcurrentHashMap<Int, Pair<Int, Int>>()

    fun takeScroll(widgetId: Int): Pair<Int, Int>? = scrolls.remove(widgetId)

    private const val SIZE_NOTIFICATION = "widget_size"

    /**
     * Tells the user in a notification when the widget got big ([warn]) or too big to show ([max]);
     * only when that changes, not at every drawing. Going back under [warn] removes the notification.
     */
    suspend fun checkSize(context: Context, widgetId: Int, automationId: Long?, bytes: Int, warn: Int, max: Int) {
        val level = when {
            bytes > max -> 2
            bytes > warn -> 1
            else -> 0
        }
        val key = "size$widgetId"
        if (prefs(context).getInt(key, 0) == level) return
        prefs(context).edit().putInt(key, level).apply()
        val notifications = NotificationManagerCompat.from(context)
        if (level == 0) {
            notifications.cancel(SIZE_NOTIFICATION, widgetId)
            return
        }
        val name = automationId?.let { (context.applicationContext as AutomationApplication).repository.get(it)?.name }.orEmpty()
        NotificationBlock.ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, widgetId, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (level == 2) tr("Widget „%s“ je moc velký", name) else tr("Widget „%s“ je velký", name)
        val text = if (level == 2) {
            tr("%s kB, nejvýš jde %s kB, takže se nový obsah nezobrazí. Zmenši seznam nebo obrázky.", bytes / 1000, max / 1000)
        } else {
            tr("%s kB z nejvýš %s kB. Když ještě poroste, Android ho nemusí zvládnout ukázat – zmenši seznam nebo obrázky.", bytes / 1000, max / 1000)
        }
        val notification = NotificationCompat.Builder(context, NotificationBlock.CHANNEL_LOUD)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val shown = notifications.areNotificationsEnabled() && try {
            notifications.notify(SIZE_NOTIFICATION, widgetId, notification)
            true
        } catch (e: SecurityException) {
            false
        }
        // Notifications are not allowed: at least a short message, so the user learns about it.
        if (!shown) android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, "$title\n$text", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bind(context: Context, widgetId: Int, automationId: Long) =
        prefs(context).edit().putLong("w$widgetId", automationId).remove("shown$widgetId").apply()

    fun unbind(context: Context, widgetId: Int) =
        prefs(context).edit().remove("w$widgetId").remove("shown$widgetId").apply()

    fun automationOf(context: Context, widgetId: Int): Long? =
        prefs(context).getLong("w$widgetId", -1L).takeIf { it >= 0 }

    private fun allIds(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, AutomationWidgetProvider::class.java))

    /** Widgets on the home screen that show [automationId]. */
    fun widgetsOf(context: Context, automationId: Long): List<Int> =
        allIds(context).filter { automationOf(context, it) == automationId }

    /** Size of the widget in dp as it is shown now (the launcher gives a range). */
    fun sizeDp(context: Context, widgetId: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        return (if (width > 0) width else 110) to (if (height > 0) height else 110)
    }

    /** What the widget does on a tap: draw itself again. */
    fun refreshIntent(context: Context, widgetId: Int): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId,
        Intent(context, AutomationWidgetProvider::class.java).setAction(ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What a tap does, as extras: "refresh" (the widget's automation runs again with [input]) or "run" ([automationId] with [input]). */
    fun tapExtras(tap: String, input: String, automationId: Long?): Intent = Intent()
        .putExtra(EXTRA_TAP, tap).putExtra(EXTRA_INPUT, input)
        .apply { if (automationId != null) putExtra(EXTRA_AUTOMATION, automationId) }

    /** A tap outside a scrolling list; [code] tells the taps of one widget apart. */
    fun tapIntent(context: Context, widgetId: Int, code: Int, extras: Intent): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId * 1000 + code,
        Intent(context, AutomationWidgetProvider::class.java).setAction(ACTION_TAP)
            .setData(android.net.Uri.parse("automation-widget://$widgetId/$code"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtras(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The taps inside a scrolling list: the items fill in their extras, so it has to be mutable. */
    fun listTemplate(context: Context, widgetId: Int): PendingIntent = PendingIntent.getBroadcast(
        context, widgetId,
        Intent(context, AutomationWidgetProvider::class.java).setAction(ACTION_TAP)
            .setData(android.net.Uri.parse("automation-widget://$widgetId/list"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Handles [ACTION_TAP]. */
    fun onTap(context: Context, intent: Intent) {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val input = intent.getStringExtra(EXTRA_INPUT).orEmpty()
        val list = intent.getIntExtra(EXTRA_LIST, 0)
        if (list > 0 && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) scrolls[widgetId] = list to intent.getIntExtra(EXTRA_ITEM, 0)
        when (intent.getStringExtra(EXTRA_TAP)) {
            "refresh" -> if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) refresh(context, intArrayOf(widgetId), input)
            "run" -> {
                val id = intent.getLongExtra(EXTRA_AUTOMATION, -1L).takeIf { it >= 0 } ?: return
                val app = context.applicationContext as AutomationApplication
                app.repository.appScope.launch {
                    val automation = app.repository.get(id) ?: return@launch
                    if (!automation.startsByItself) return@launch
                    EventDispatcher.runNow(app, automation, input, tr("Widget")).join()
                }
            }
        }
    }

    /** Remembers that the widget got content, so a later failure keeps it instead of showing the error. */
    fun markShown(context: Context, widgetId: Int) = prefs(context).edit().putBoolean("shown$widgetId", true).apply()

    /** A short message in the widget (nothing set up, an error before the first content). */
    fun showMessage(
        context: Context, widgetId: Int, message: String, icon: String? = null,
        tap: PendingIntent? = refreshIntent(context, widgetId),
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_automation)
        views.setViewVisibility(R.id.widget_image, View.GONE)
        views.setViewVisibility(R.id.widget_message, View.VISIBLE)
        views.setTextViewText(R.id.widget_text, message)
        // The icon of the automation, so the user sees which one the widget belongs to.
        if (!icon.isNullOrBlank()) {
            views.setImageViewBitmap(
                R.id.widget_icon,
                cz.natsu.automation.engine.EmbeddedImage.fromIcon(cz.natsu.automation.ui.common.IconCatalog.icon(icon), android.graphics.Color.WHITE, 96),
            )
        }
        if (tap != null) views.setOnClickPendingIntent(R.id.widget_root, tap)
        AppWidgetManager.getInstance(context).updateAppWidget(widgetId, views)
    }

    private val jobs = ConcurrentHashMap<Int, Job>()

    /** Draws every widget of [automationId] again (after a change of the automation, or the "Obnovit widget" block). */
    fun refreshAutomation(context: Context, automationId: Long) =
        refresh(context, widgetsOf(context, automationId).toIntArray())

    /** Runs the automation of each widget so that it sends new content. A new request replaces one still running. */
    fun refresh(context: Context, widgetIds: IntArray, action: String = "") {
        val app = context.applicationContext as AutomationApplication
        for (widgetId in widgetIds) {
            jobs.remove(widgetId)?.cancel()
            jobs[widgetId] = app.repository.appScope.launch {
                try {
                    render(app, widgetId, action)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Widget $widgetId failed", e)
                }
            }
        }
    }

    private suspend fun render(context: Context, widgetId: Int, action: String) {
        val automationId = automationOf(context, widgetId)
        val repository = (context as AutomationApplication).repository
        val automation = automationId?.let { repository.get(it) }
        val problem = when {
            automationId == null -> tr("Widget nemá automatizaci – odeber ho a přidej znovu")
            automation == null -> tr("Automatizace už neexistuje")
            automation.needsReview -> tr("\"%s\" čeká na kontrolu – otevři ji a povol", automation.name)
            automation.paused -> tr("„%s“ je pozastavená", automation.name)
            !rendersWidget(automation) -> tr("\"%s\" nemá zapnutý spouštěč Widget na ploše", automation.name)
            else -> null
        }
        if (problem != null || automation == null) {
            showMessage(context, widgetId, problem ?: "", automation?.icon)
            return
        }
        val (width, height) = sizeDp(context, widgetId)
        val event = EventInfo(
            LABEL, mapOf(
                "widget_id" to widgetId.toString(),
                "widget_width" to width.toString(),
                "widget_height" to height.toString(),
                "dark_mode" to DeviceState.darkMode(context).toString(),
                "widget_action" to action,
                "widget_debug" to automation.widgetDebug.toString(),
            )
        )
        val startedAt = System.currentTimeMillis()
        val result = AutomationRunner.run(
            context, automation.blocks, action, ui = null, trigger = RunTrigger.Event,
            event = event, automationId = automation.id,
        )
        RunHistory.record(context, automation, event.label, startedAt, result)
        val shown = prefs(context).getBoolean("shown$widgetId", false)
        when {
            result.error != null && !shown -> showMessage(context, widgetId, "${automation.name}: ${result.error}", automation.icon)
            !shown && !result.cancelled -> showMessage(context, widgetId, tr("\"%s\" nenastavila obsah – přidej blok Nastavit widget", automation.name), automation.icon)
        }
    }

    fun rendersWidget(automation: AutomationEntity): Boolean = automation.triggerWidget && automation.startsByItself
}

/** The widget the user puts on the home screen; which automation it shows is chosen in [WidgetConfigActivity]. */
class AutomationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        // Not yet chosen = the configuration screen is still open; it draws the widget itself.
        HomeWidgets.refresh(context, appWidgetIds.filter { HomeWidgets.automationOf(context, it) != null }.toIntArray())
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        if (HomeWidgets.automationOf(context, appWidgetId) != null) HomeWidgets.refresh(context, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) HomeWidgets.unbind(context, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HomeWidgets.ACTION_REFRESH) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) HomeWidgets.refresh(context, intArrayOf(id))
            return
        }
        if (intent.action == HomeWidgets.ACTION_TAP) {
            HomeWidgets.onTap(context, intent)
            return
        }
        super.onReceive(context, intent)
    }
}
