package cz.natsu.automation.engine

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Widgets
import cz.natsu.automation.R
import cz.natsu.automation.data.Block
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.trigger.HomeWidgets
import cz.natsu.automation.trigger.TriggerActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.sqrt

/** Blocks for widgets on the home screen (see HomeWidgets). */
object WidgetBlocks {
    const val SET = "widget_set"
    const val REFRESH = "widget_refresh"

    /** The widgets the run draws: the one being drawn, else all widgets of the automation. */
    private fun targets(ctx: RunContext): List<Int> {
        if (ctx.widgetPreview != null) return listOf(0)
        ctx.event?.values?.get("widget_id")?.toIntOrNull()?.let { return listOf(it) }
        val id = ctx.automationId ?: throw BlockException(tr("widget jde nastavit jen z uložené automatizace"))
        return HomeWidgets.widgetsOf(ctx.context, id)
            .ifEmpty { throw BlockException(tr("tahle automatizace nemá na ploše žádný widget")) }
    }

    /**
     * [html] drawn into a picture of [widthDp] × [heightDp] (CSS px = dp). The picture is made smaller
     * when it would not fit the limit Android has for a widget.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderHtml(context: Context, html: String, widthDp: Int, heightDp: Int, internet: Boolean): Bitmap =
        withContext(Dispatchers.Main) {
            val metrics = context.resources.displayMetrics
            val width = (widthDp * metrics.density).toInt().coerceAtLeast(1)
            val height = (heightDp * metrics.density).toInt().coerceAtLeast(1)
            // A widget may get a picture of at most 1.5× the screen; keep well under it.
            val limit = metrics.widthPixels.toLong() * metrics.heightPixels * 4
            val scale = minOf(1.0, sqrt(limit.toDouble() / (width.toLong() * height * 4))).toFloat()

            val web = WebView(context.applicationContext)
            try {
                web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                web.setBackgroundColor(Color.TRANSPARENT)
                web.isVerticalScrollBarEnabled = false
                web.isHorizontalScrollBarEnabled = false
                web.settings.javaScriptEnabled = true
                web.settings.blockNetworkLoads = !internet
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                val loaded = CompletableDeferred<Unit>()
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        loaded.complete(Unit)
                    }
                }
                web.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                web.layout(0, 0, width, height)
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                withTimeout(if (internet) 15_000 else 5_000) { loaded.await() }
                // Scripts that run on load and the first layout need a moment after "finished".
                delay(400)
                val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                web.draw(canvas)
                bitmap
            } finally {
                web.destroy()
            }
        }

    private fun tapIntent(ctx: RunContext, block: Block, widgetId: Int): PendingIntent? = when (ctx.choice(block, "tap")) {
        "none" -> null
        "run" -> {
            val id = ctx.choice(block, "tap_automation").toLongOrNull()
                ?: throw BlockException(tr("není vybraná automatizace pro klepnutí"))
            PendingIntent.getActivity(
                ctx.context, widgetId,
                TriggerActivity.runIntent(ctx.context, id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        else -> HomeWidgets.refreshIntent(ctx.context, widgetId)
    }

    val all = listOf(
        BlockDef(
            type = SET,
            summary = "Nastavit widget na HTML",
            title = "Nastavit widget",
            description = "Pošle obsah widgetu na ploše: HTML se vykreslí do obrázku přesně ve velikosti widgetu " +
                    "(1 CSS px = 1 dp; velikost vrátí blok Údaje z události). Stránka se nedá posouvat ani klikat, " +
                    "klepnutí na widget ho obnoví nebo spustí automatizaci. Platí pro widget, který se právě kreslí, " +
                    "jinak pro všechny widgety této automatizace.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Widgets,
            params = listOf(
                ParamDef.Text(
                    "html", "HTML widgetu", code = true,
                    placeholder = "<div style=\"background:#223;color:#fff;height:100vh;border-radius:24px\">Ahoj</div>",
                ).apply { hiddenInSummaryWhen = { true } },
                ParamDef.Choice(
                    "tap", "Po klepnutí", listOf(
                        ChoiceOption("refresh", "Obnovit widget", "obnovit"),
                        ChoiceOption("run", "Spustit automatizaci", "spustit"),
                        ChoiceOption("none", "Nic", "nic"),
                    )
                ),
                ParamDef.Automation("tap_automation", "Automatizace po klepnutí").apply {
                    hiddenWhen = { (it["tap"] as? cz.natsu.automation.data.ParamValue.Choice)?.value != "run" }
                },
                ParamDef.Toggle("internet", "Povolit internet (obrázky, písma)", summaryWhenOn = "s internetem"),
            ),
        ) { block ->
            val html = text(block, "html")
            if (html.isBlank()) throw BlockException(tr("chybí HTML widgetu"))
            val manager = AppWidgetManager.getInstance(context)
            for (widgetId in targets(this)) {
                val (width, height) = if (widgetPreview != null) {
                    (event?.values?.get("widget_width")?.toIntOrNull() ?: 180) to (event?.values?.get("widget_height")?.toIntOrNull() ?: 180)
                } else HomeWidgets.sizeDp(context, widgetId)
                val bitmap = try {
                    renderHtml(context, html, width, height, bool(block, "internet"))
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw BlockException(tr("HTML widgetu se nenačetlo včas"))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_automation)
                views.setViewVisibility(R.id.widget_message, View.GONE)
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_image, bitmap)
                widgetPreview?.let { preview ->
                    preview(views, bitmap.allocationByteCount)
                    return@BlockDef BlockResult.None
                }
                tapIntent(this, block, widgetId)?.let { views.setOnClickPendingIntent(R.id.widget_root, it) }
                manager.updateAppWidget(widgetId, views)
                HomeWidgets.markShown(context, widgetId)
            }
            BlockResult.None
        },

        BlockDef(
            type = REFRESH,
            summary = "Obnovit widget {automation}",
            title = "Obnovit widget",
            description = "Překreslí widgety na ploše, které ukazují zvolenou automatizaci (prázdné = tuhle): " +
                    "ta se spustí, jako by se widget kreslil. Hodí se po změně dat, třeba z jiné události.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Refresh,
            params = listOf(ParamDef.Automation("automation", "Automatizace s widgetem")),
        ) { block ->
            val id = choice(block, "automation").toLongOrNull() ?: automationId
                ?: throw BlockException(tr("není vybraná automatizace"))
            if (id == automationId && event?.label == HomeWidgets.LABEL) {
                throw BlockException(tr("widget se nemůže obnovit sám během svého vykreslení"))
            }
            HomeWidgets.refreshAutomation(context, id)
            BlockResult.None
        },
    )
}
