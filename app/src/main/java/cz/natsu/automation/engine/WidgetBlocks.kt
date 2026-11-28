package cz.natsu.automation.engine

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** A tappable element of the HTML (data-tap / data-run), in CSS px from the top left corner. */
    class Hotspot(val x: Float, val y: Float, val width: Float, val height: Float, val tap: String?, val run: String?, val input: String?) {
        fun contains(px: Float, py: Float) = px >= x && px < x + width && py >= y && py < y + height
    }

    class Rendered(val bitmap: Bitmap, val hotspots: List<Hotspot>)

    /** How many tappable elements a widget can have (each is a view and a PendingIntent the launcher keeps). */
    const val MAX_HOTSPOTS = 24

    /** Finds the elements with data-tap or data-run and where they are drawn. */
    private val FIND_HOTSPOTS = """
        JSON.stringify(Array.from(document.querySelectorAll('[data-tap],[data-run]')).slice(0, $MAX_HOTSPOTS).map(function (e) {
          var r = e.getBoundingClientRect();
          return { x: r.left, y: r.top, w: r.width, h: r.height,
            tap: e.getAttribute('data-tap'), run: e.getAttribute('data-run'), input: e.getAttribute('data-input') };
        }).filter(function (h) { return h.w > 0 && h.h > 0; }))
    """.trimIndent()

    private fun hotspots(result: String?): List<Hotspot> = try {
        // evaluateJavascript gives the value as JSON: here a JSON string that holds the JSON list.
        val list = JsonTools.parseOrNull(result ?: "")?.let { (it as? JsonPrimitive)?.content }?.let { JsonTools.parseOrNull(it) } as? JsonArray
        list.orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun num(key: String) = (o[key] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Hotspot(num("x"), num("y"), num("w"), num("h"), str("tap"), str("run"), str("input"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderHtml(context: Context, html: String, widthDp: Int, heightDp: Int, internet: Boolean): Rendered =
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
                val found = CompletableDeferred<String?>()
                web.evaluateJavascript(FIND_HOTSPOTS) { found.complete(it) }
                val spots = hotspots(withTimeoutOrNull(2_000) { found.await() })
                val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(scale, scale)
                web.draw(canvas)
                Rendered(bitmap, spots)
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

    /**
     * Makes the tappable elements of the HTML react: on Android 12+ a view is laid exactly over each one;
     * older Android cannot place a view, so a grid of 8 × 8 cells takes the element under the middle of each cell.
     * Elsewhere the tap of the whole widget ([fallback]) applies.
     */
    private suspend fun wireHotspots(
        ctx: RunContext, views: RemoteViews, widgetId: Int, spots: List<Hotspot>,
        widthDp: Int, heightDp: Int, fallback: PendingIntent?,
    ) {
        if (spots.isEmpty()) return
        val repository = (ctx.context.applicationContext as? cz.natsu.automation.AutomationApplication)?.repository
        val all = repository?.getAll().orEmpty()
        val intents = spots.mapIndexed { index, spot ->
            val extras = if (spot.run != null) {
                val name = spot.run.trim()
                val target = name.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } }
                    ?: all.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: throw BlockException(tr("data-run: automatizace \"%s\" neexistuje", name))
                HomeWidgets.tapExtras("run", spot.input ?: spot.tap.orEmpty(), target.id)
            } else {
                HomeWidgets.tapExtras("refresh", spot.input ?: spot.tap.orEmpty(), null)
            }
            HomeWidgets.tapIntent(ctx.context, widgetId, index + 1, extras)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views.setViewVisibility(R.id.widget_hotspots, View.VISIBLE)
            for ((index, spot) in spots.withIndex()) {
                val id = HOTSPOT_IDS[index]
                views.setViewVisibility(id, View.VISIBLE)
                views.setViewLayoutWidth(id, spot.width, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(id, spot.height, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, spot.x, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, spot.y, TypedValue.COMPLEX_UNIT_DIP)
                views.setOnClickPendingIntent(id, intents[index])
            }
        } else {
            views.setViewVisibility(R.id.widget_grid, View.VISIBLE)
            for (cell in 0 until GRID * GRID) {
                val cx = (cell % GRID + 0.5f) * widthDp / GRID
                val cy = (cell / GRID + 0.5f) * heightDp / GRID
                // The last element wins, as in the page: it is drawn on top.
                val hit = spots.indices.lastOrNull { spots[it].contains(cx, cy) }
                val intent = if (hit != null) intents[hit] else fallback
                if (intent != null) views.setOnClickPendingIntent(CELL_IDS[cell], intent)
            }
        }
    }

    private const val GRID = 8
    private val HOTSPOT_IDS = listOf(
        R.id.widget_hot_0, R.id.widget_hot_1, R.id.widget_hot_2, R.id.widget_hot_3, R.id.widget_hot_4, R.id.widget_hot_5,
        R.id.widget_hot_6, R.id.widget_hot_7, R.id.widget_hot_8, R.id.widget_hot_9, R.id.widget_hot_10, R.id.widget_hot_11,
        R.id.widget_hot_12, R.id.widget_hot_13, R.id.widget_hot_14, R.id.widget_hot_15, R.id.widget_hot_16, R.id.widget_hot_17,
        R.id.widget_hot_18, R.id.widget_hot_19, R.id.widget_hot_20, R.id.widget_hot_21, R.id.widget_hot_22, R.id.widget_hot_23,
    )
    private val CELL_IDS = listOf(
        R.id.widget_cell_0, R.id.widget_cell_1, R.id.widget_cell_2, R.id.widget_cell_3, R.id.widget_cell_4, R.id.widget_cell_5, R.id.widget_cell_6, R.id.widget_cell_7,
        R.id.widget_cell_8, R.id.widget_cell_9, R.id.widget_cell_10, R.id.widget_cell_11, R.id.widget_cell_12, R.id.widget_cell_13, R.id.widget_cell_14, R.id.widget_cell_15,
        R.id.widget_cell_16, R.id.widget_cell_17, R.id.widget_cell_18, R.id.widget_cell_19, R.id.widget_cell_20, R.id.widget_cell_21, R.id.widget_cell_22, R.id.widget_cell_23,
        R.id.widget_cell_24, R.id.widget_cell_25, R.id.widget_cell_26, R.id.widget_cell_27, R.id.widget_cell_28, R.id.widget_cell_29, R.id.widget_cell_30, R.id.widget_cell_31,
        R.id.widget_cell_32, R.id.widget_cell_33, R.id.widget_cell_34, R.id.widget_cell_35, R.id.widget_cell_36, R.id.widget_cell_37, R.id.widget_cell_38, R.id.widget_cell_39,
        R.id.widget_cell_40, R.id.widget_cell_41, R.id.widget_cell_42, R.id.widget_cell_43, R.id.widget_cell_44, R.id.widget_cell_45, R.id.widget_cell_46, R.id.widget_cell_47,
        R.id.widget_cell_48, R.id.widget_cell_49, R.id.widget_cell_50, R.id.widget_cell_51, R.id.widget_cell_52, R.id.widget_cell_53, R.id.widget_cell_54, R.id.widget_cell_55,
        R.id.widget_cell_56, R.id.widget_cell_57, R.id.widget_cell_58, R.id.widget_cell_59, R.id.widget_cell_60, R.id.widget_cell_61, R.id.widget_cell_62, R.id.widget_cell_63,
    )

    val all = listOf(
        BlockDef(
            type = SET,
            summary = "Nastavit widget na HTML",
            title = "Nastavit widget",
            description = "Pošle obsah widgetu na ploše: HTML se vykreslí do obrázku přesně ve velikosti widgetu " +
                    "(1 CSS px = 1 dp; velikost vrátí blok Údaje z události). Stránka se nedá posouvat. Prvek s data-tap=\"hodnota\" " +
                    "jde na ploše klepnout: widget se překreslí a automatizace dostane hodnotu jako Vstup; data-run=\"Název " +
                    "automatizace\" (s data-input) spustí jinou automatizaci. Jinde platí Po klepnutí. Platí pro widget, " +
                    "který se právě kreslí, jinak pro všechny widgety této automatizace.",
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
                val rendered = try {
                    renderHtml(context, html, width, height, bool(block, "internet"))
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw BlockException(tr("HTML widgetu se nenačetlo včas"))
                }
                val views = RemoteViews(context.packageName, R.layout.widget_automation)
                views.setViewVisibility(R.id.widget_message, View.GONE)
                views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_image, rendered.bitmap)
                val fallback = if (widgetPreview == null) tapIntent(this, block, widgetId) else null
                fallback?.let { views.setOnClickPendingIntent(R.id.widget_root, it) }
                if (widgetPreview == null) wireHotspots(this, views, widgetId, rendered.hotspots, width, height, fallback)
                // What really travels to the launcher (the picture goes as shared memory, not in the message),
                // the same measure as for "Widget z bloků".
                val bytes = WidgetBuilder.sizeOf(views)
                widgetPreview?.let { preview ->
                    preview(views, bytes)
                    return@BlockDef BlockResult.None
                }
                if (event?.values?.get("widget_debug") == "true") {
                    HomeWidgets.checkSize(context, widgetId, automationId, bytes, WidgetBuilder.WARN_BYTES, WidgetBuilder.MAX_BYTES)
                }
                if (bytes > WidgetBuilder.MAX_BYTES) {
                    throw BlockException(tr("widget je moc velký: %s kB, nejvýš jde %s kB – zmenši seznam nebo obrázky", bytes / 1000, WidgetBuilder.MAX_BYTES / 1000))
                }
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
