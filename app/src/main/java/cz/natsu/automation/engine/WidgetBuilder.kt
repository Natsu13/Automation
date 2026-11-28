package cz.natsu.automation.engine

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Widgets
import androidx.core.widget.RemoteViewsCompat
import cz.natsu.automation.R
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.trigger.HomeWidgets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Widget z bloků": a home screen widget made of native parts (texts, images, buttons, rows,
 * a scrolling list). The group blocks open a part, the blocks inside add to it, and at the end
 * of "Widget z bloků" the whole tree is sent to the widget.
 */
object WidgetBuilder {
    const val BUILD = "widget_build"
    const val END_BUILD = "end_widget_build"
    const val BOX = "widget_box"
    const val END_BOX = "end_widget_box"
    const val LIST = "widget_list"
    const val END_LIST = "end_widget_list"

    val GROUPS = setOf(BUILD, BOX, LIST)

    /** One part of the widget; [props] are the resolved values of its block. */
    class Node(val type: String, val props: Map<String, String>, val children: MutableList<Node> = mutableListOf()) {
        fun json(): JsonObject = JsonObject(
            mapOf("type" to JsonPrimitive(type)) + props.filterValues { it.isNotEmpty() }.mapValues { JsonPrimitive(it.value) } +
                    (if (children.isEmpty()) emptyMap() else mapOf("children" to JsonArray(children.map { it.json() })))
        )
    }

    fun endTypeFor(type: String): String? = when (type) {
        BUILD -> END_BUILD
        BOX -> END_BOX
        LIST -> END_LIST
        else -> null
    }

    /** Adds [node] to the part that is open now. */
    private fun add(ctx: RunContext, node: Node) {
        val parent = ctx.widgetStack?.lastOrNull() ?: throw BlockException(tr("blok patří dovnitř bloku Widget z bloků"))
        parent.children += node
    }

    /**
     * Runs the steps inside a group block ([body] returns true when they all finished). Returns
     * the layout as JSON for "Widget z bloků" (after it was sent), else null.
     */
    suspend fun group(ctx: RunContext, block: Block, body: suspend () -> Boolean): String? {
        val node = Node(block.type, props(ctx, block))
        if (block.type == BUILD) {
            val outer = ctx.widgetStack
            ctx.widgetStack = mutableListOf(node)
            try {
                if (!body()) return null
            } finally {
                ctx.widgetStack = outer
            }
            val bytes = send(ctx, node)
            return JsonObject(node.json() + ("bytes" to JsonPrimitive(bytes))).toString()
        }
        val stack = ctx.widgetStack ?: throw BlockException(tr("blok patří dovnitř bloku Widget z bloků"))
        if (block.type == LIST && stack.any { it.type == LIST }) throw BlockException(tr("seznam ve widgetu nemůže být v jiném seznamu"))
        if (block.type == LIST && countLists(stack.first()) >= MAX_LISTS) throw BlockException(tr("widget může mít nejvýš %s posouvací seznamy", MAX_LISTS))
        add(ctx, node)
        stack += node
        try {
            body()
        } finally {
            stack.removeAt(stack.lastIndex)
        }
        return null
    }

    /** The values of all parameters of [block], resolved (variables filled in). */
    private suspend fun props(ctx: RunContext, block: Block): Map<String, String> {
        val def = Blocks.get(block.type) ?: return emptyMap()
        return def.params.associate { param ->
            param.key to when (param) {
                is ParamDef.Text -> ctx.text(block, param.key)
                is ParamDef.Choice, is ParamDef.Automation -> ctx.choice(block, param.key)
                is ParamDef.Number -> formatNumber(ctx.number(block, param.key).toDouble())
                is ParamDef.Toggle -> ctx.bool(block, param.key).toString()
            }
        }
    }

    private fun countLists(node: Node): Int = (if (node.type == LIST) 1 else 0) + node.children.sumOf { countLists(it) }

    /** Size of [views] as it travels to the launcher, in bytes (with pictures and list items). */
    private fun sizeOf(views: RemoteViews): Int {
        val parcel = android.os.Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.dataSize()
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Sends the widget; returns its size in bytes. Android passes it to the launcher in one message of
     * at most about 1 MB (shared with other messages), so a big one warns and a too big one is not sent.
     */
    private suspend fun send(ctx: RunContext, root: Node): Int {
        val context = ctx.context
        val targets = if (ctx.widgetPreview != null) listOf(0) else ctx.event?.values?.get("widget_id")?.toIntOrNull()?.let { listOf(it) }
            ?: ctx.automationId?.let { HomeWidgets.widgetsOf(context, it) }.orEmpty()
        if (targets.isEmpty()) throw BlockException(tr("tahle automatizace nemá na ploše žádný widget"))
        val dark = ctx.event?.values?.get("dark_mode")?.toBooleanStrictOrNull() ?: DeviceState.darkMode(context)
        ctx.widgetPreview?.let { preview ->
            val renderer = Renderer(context, 0, dark)
            val views = renderer.root(root)
            val bytes = sizeOf(views) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0 else renderer.itemBytes
            preview(views, bytes)
            return bytes
        }
        // Notifications about the size only when the user switched them on for the widget (for debugging).
        val debug = ctx.event?.values?.get("widget_debug") == "true"
        val manager = AppWidgetManager.getInstance(context)
        var largest = 0
        for (widgetId in targets) {
            val renderer = Renderer(context, widgetId, dark)
            val views = renderer.root(root)
            // Android 12+ carries the list items inside the views; older ones hand them over separately.
            val bytes = sizeOf(views) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0 else renderer.itemBytes
            largest = maxOf(largest, bytes)
            if (debug) HomeWidgets.checkSize(context, widgetId, ctx.automationId, bytes, WARN_BYTES, MAX_BYTES)
            if (bytes > MAX_BYTES) {
                throw BlockException(tr("widget je moc velký: %s kB, nejvýš jde %s kB – zmenši seznam nebo obrázky", bytes / 1000, MAX_BYTES / 1000))
            }
            manager.updateAppWidget(widgetId, views)
            HomeWidgets.markShown(context, widgetId)
        }
        return largest
    }

    /** Turns a tree of [Node]s into RemoteViews for one widget. */
    private class Renderer(val context: Context, val widgetId: Int, val dark: Boolean) {
        private val density = context.resources.displayMetrics.density
        private var code = 0
        /** Items of the scrolling lists by the id of the list; set on the root views, since a nested one may not have an adapter. */
        private val lists = mutableListOf<Pair<Int, RemoteViewsCompat.RemoteCollectionItems>>()

        /** Size of the list items in bytes. */
        var itemBytes = 0
            private set

        /** The list (1..) and item being built, so a tap in it can say where it was. */
        private var currentList = 0
        private var currentItem = 0
        private val textColor = if (dark) Color.WHITE else 0xFF1C1B1F.toInt()
        private val surface = if (dark) 0xFF26252B.toInt() else 0xFFF3EFF7.toInt()
        private val accent = if (dark) 0xFFBFC6FF.toInt() else 0xFF3F51B5.toInt()

        private fun px(dp: Float) = (dp * density).toInt()
        private fun color(text: String?, default: Int): Int = text?.takeIf { it.isNotBlank() }?.let { ImageTools.color(it) } ?: default
        private fun number(text: String?, default: Float) = text?.replace(',', '.')?.toFloatOrNull() ?: default

        private fun gravity(align: String?): Int = when (align) {
            "center" -> Gravity.CENTER
            "end" -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.START or Gravity.CENTER_VERTICAL
        }

        suspend fun root(node: Node): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.wb_root)
            background(views, node.props, surface)
            val padding = px(number(node.props["padding"], 12f))
            views.setViewPadding(R.id.wb_content, padding, padding, padding, padding)
            views.setInt(R.id.wb_content, "setGravity", gravity(node.props["align"]).let { if (it == Gravity.CENTER) it else it and Gravity.HORIZONTAL_GRAVITY_MASK })
            fill(views, node.children, inList = false, path = "r")
            val scroll = HomeWidgets.takeScroll(widgetId)
            for ((number, entry) in lists.withIndex()) {
                val (listId, items) = entry
                views.setPendingIntentTemplate(listId, HomeWidgets.listTemplate(context, widgetId))
                RemoteViewsCompat.setRemoteAdapter(context, views, widgetId, listId, items)
                // Older Android builds the list anew (back at the top): scroll to the item that was tapped.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && scroll?.first == number + 1) views.setScrollPosition(listId, scroll.second)
            }
            return views
        }

        /**
         * Puts the parts of [children] into wb_content. The launcher reuses the views of the last update, so the
         * old parts are removed first; on Android 12+ parts in the same place (stable id) are kept and only
         * updated, so a list stays where it was scrolled to.
         */
        private suspend fun fill(views: RemoteViews, children: List<Node>, inList: Boolean, path: String) {
            views.removeAllViews(R.id.wb_content)
            for ((index, child) in children.withIndex()) {
                val place = "$path.$index"
                val part = view(child, inList, place)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !inList) {
                    views.addStableView(R.id.wb_content, part, "$place:${child.type}:${child.props["size"]}:${child.props["direction"]}".hashCode())
                } else {
                    views.addView(R.id.wb_content, part)
                }
            }
        }

        private fun background(views: RemoteViews, props: Map<String, String>, default: Int?) {
            val color = props["background"]?.takeIf { it.isNotBlank() }?.let { ImageTools.color(it) } ?: default
            if (color == null) {
                views.setViewVisibility(R.id.wb_bg, View.GONE)
                return
            }
            views.setViewVisibility(R.id.wb_bg, View.VISIBLE)
            views.setImageViewResource(R.id.wb_bg, if (props["rounded"] == "false") R.drawable.wb_shape_square else R.drawable.wb_shape_round)
            views.setInt(R.id.wb_bg, "setColorFilter", color)
            if (Color.alpha(color) < 255) views.setInt(R.id.wb_bg, "setImageAlpha", Color.alpha(color))
        }

        /** Makes [viewId] react to a tap as [props] say ("tap", "tap_input", "tap_automation"). */
        private fun tap(views: RemoteViews, viewId: Int, props: Map<String, String>, inList: Boolean) {
            val tap = props["tap"].orEmpty()
            if (tap != "refresh" && tap != "run") return
            val automation = props["tap_automation"]?.toLongOrNull()
            if (tap == "run" && automation == null) throw BlockException(tr("není vybraná automatizace pro klepnutí"))
            val extras = HomeWidgets.tapExtras(tap, props["tap_input"].orEmpty(), automation)
            if (inList) views.setOnClickFillInIntent(viewId, extras.putExtra(HomeWidgets.EXTRA_LIST, currentList).putExtra(HomeWidgets.EXTRA_ITEM, currentItem))
            else views.setOnClickPendingIntent(viewId, HomeWidgets.tapIntent(context, widgetId, ++code, extras))
        }

        private fun sized(prefix: String, props: Map<String, String>): Int = when ("${prefix}_${props["size"] ?: "wrap"}") {
            "col_match" -> R.layout.wb_col_match
            "col_fill" -> R.layout.wb_col_fill
            "row_wrap" -> R.layout.wb_row_wrap
            "row_match" -> R.layout.wb_row_match
            "row_fill" -> R.layout.wb_row_fill
            "text_match" -> R.layout.wb_text_match
            "text_fill" -> R.layout.wb_text_fill
            "text_wrap" -> R.layout.wb_text_wrap
            "button_match" -> R.layout.wb_button_match
            "button_fill" -> R.layout.wb_button_fill
            "button_wrap" -> R.layout.wb_button_wrap
            else -> R.layout.wb_col_wrap
        }

        private fun styled(text: String, bold: Boolean): CharSequence =
            if (!bold) text else SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

        suspend fun view(node: Node, inList: Boolean, path: String): RemoteViews {
            val p = node.props
            return when (node.type) {
                BOX -> {
                    val views = RemoteViews(context.packageName, sized(if (p["direction"] == "row") "row" else "col", p))
                    background(views, p, null)
                    val padding = px(number(p["padding"], 0f))
                    views.setViewPadding(R.id.wb_content, padding, padding, padding, padding)
                    val align = gravity(p["align"])
                    views.setInt(R.id.wb_content, "setGravity", if (p["direction"] == "row") align else if (align == Gravity.CENTER) Gravity.CENTER_HORIZONTAL else align and Gravity.HORIZONTAL_GRAVITY_MASK)
                    tap(views, R.id.wb_box, p, inList)
                    fill(views, node.children, inList, path)
                    views
                }
                LIST -> list(node)
                TEXT -> {
                    val views = RemoteViews(context.packageName, sized("text", p))
                    views.setTextViewText(R.id.wb_text, styled(p["text"].orEmpty(), p["bold"] == "true"))
                    views.setTextViewTextSize(R.id.wb_text, TypedValue.COMPLEX_UNIT_SP, number(p["text_size"], 14f))
                    views.setTextColor(R.id.wb_text, color(p["color"], textColor))
                    views.setInt(R.id.wb_text, "setGravity", gravity(p["align"]))
                    number(p["lines"], 0f).toInt().takeIf { it > 0 }?.let { views.setInt(R.id.wb_text, "setMaxLines", it) }
                    tap(views, R.id.wb_text, p, inList)
                    views
                }
                BUTTON -> {
                    val views = RemoteViews(context.packageName, sized("button", p))
                    views.setTextViewText(R.id.wb_text, p["text"].orEmpty())
                    val background = color(p["background"], accent)
                    views.setInt(R.id.wb_bg, "setColorFilter", background)
                    val light = (Color.red(background) * 299 + Color.green(background) * 587 + Color.blue(background) * 114) / 1000 > 150
                    views.setTextColor(R.id.wb_text, color(p["color"], if (light) 0xFF1C1B1F.toInt() else Color.WHITE))
                    tap(views, R.id.wb_box, p, inList)
                    views
                }
                IMAGE -> {
                    val views = RemoteViews(context.packageName, R.layout.wb_image)
                    views.setImageViewBitmap(R.id.wb_image, image(p["image"].orEmpty(), number(p["height"], 48f), p["tint"]))
                    tap(views, R.id.wb_image, p, inList)
                    views
                }
                PROGRESS -> {
                    val views = RemoteViews(context.packageName, R.layout.wb_progress)
                    val max = number(p["max"], 100f).coerceAtLeast(1f)
                    val value = number(p["value"], 0f).coerceIn(0f, max)
                    views.setProgressBar(R.id.wb_progress, 1000, (value / max * 1000).toInt(), false)
                    views
                }
                SPACE -> {
                    val views = RemoteViews(context.packageName, if (p["fill"] == "true") R.layout.wb_space_fill else R.layout.wb_space)
                    val size = px(number(p["space"], 8f))
                    views.setViewPadding(R.id.wb_space, size, size, 0, 0)
                    views
                }
                else -> throw BlockException(tr("neznámá část widgetu: %s", node.type))
            }
        }

        private suspend fun image(source: String, heightDp: Float, tint: String?): Bitmap {
            val loaded = EmbeddedImage.bitmap(source) ?: ImageTools.load(context, source).bitmap
            val height = px(heightDp).coerceIn(1, 1024)
            val width = (loaded.width.toLong() * height / loaded.height.coerceAtLeast(1)).toInt().coerceIn(1, 2048)
            val scaled = Bitmap.createScaledBitmap(loaded, width, height, true)
            return tint?.takeIf { it.isNotBlank() }?.let { ImageTools.tint(scaled, ImageTools.color(it)) } ?: scaled
        }

        private suspend fun list(node: Node): RemoteViews {
            if (lists.size >= MAX_LISTS) throw BlockException(tr("widget může mít nejvýš %s posouvací seznamy", MAX_LISTS))
            val (layout, listId) = LIST_VIEWS[lists.size]
            currentList = lists.size + 1
            val views = RemoteViews(context.packageName, layout)
            val spacing = px(number(node.props["spacing"], 6f))
            val items = RemoteViewsCompat.RemoteCollectionItems.Builder().setHasStableIds(false)
            for ((index, child) in node.children.withIndex()) {
                currentItem = index
                val item = RemoteViews(context.packageName, R.layout.wb_list_item)
                // The gap is padding under each item: a widget cannot set the divider height of a list.
                if (index < node.children.lastIndex) item.setViewPadding(R.id.wb_item, 0, 0, 0, spacing)
                // Item views are reused: without emptying, the parts of earlier items would stay in.
                item.removeAllViews(R.id.wb_item)
                item.addView(R.id.wb_item, view(child, inList = true, path = "l$currentList.$index"))
                items.addItem(index.toLong(), item)
                itemBytes += sizeOf(item)
            }
            // Every layout used inside the items counts as a kind of view; a few to spare.
            items.setViewTypeCount(LAYOUT_KINDS)
            lists += listId to items.build()
            return views
        }
    }

    private const val TEXT = "widget_text"
    private const val BUTTON = "widget_button"
    private const val IMAGE = "widget_image"
    private const val PROGRESS = "widget_progress"
    private const val SPACE = "widget_space"
    private const val MAX_LISTS = 4
    private val LIST_VIEWS = listOf(
        R.layout.wb_list_1 to R.id.wb_list_1, R.layout.wb_list_2 to R.id.wb_list_2,
        R.layout.wb_list_3 to R.id.wb_list_3, R.layout.wb_list_4 to R.id.wb_list_4,
    )

    /** From this size the user is warned that the widget may not show; above [MAX_BYTES] it is not sent. */
    const val WARN_BYTES = 250_000
    const val MAX_BYTES = 500_000
    private const val LAYOUT_KINDS = 24

    private fun sizeParam() = ParamDef.Choice(
        "size", "Šířka", listOf(
            ChoiceOption("wrap", "Podle obsahu", "podle obsahu"),
            ChoiceOption("match", "Celá šířka", "celá šířka"),
            ChoiceOption("fill", "Vyplnit zbylé místo", "vyplnit"),
        )
    ).apply { hiddenInSummaryWhen = { true } }

    private fun alignParam(label: String = "Zarovnání") = ParamDef.Choice(
        "align", label, listOf(
            ChoiceOption("start", "Vlevo / nahoru", "vlevo"),
            ChoiceOption("center", "Na střed", "na střed"),
            ChoiceOption("end", "Vpravo / dolů", "vpravo"),
        )
    ).apply { hiddenInSummaryWhen = { true } }

    private fun isTap(values: Map<String, ParamValue>, vararg taps: String) = (values["tap"] as? ParamValue.Choice)?.value in taps

    /** What a tap on the part does; [none] = it may also do nothing. */
    private fun tapParams(none: Boolean): List<ParamDef> = listOf(
        ParamDef.Choice(
            "tap", "Po klepnutí", listOfNotNull(
                ChoiceOption("none", "Nic", "nic").takeIf { none },
                ChoiceOption("refresh", "Překreslit widget se vstupem", "překreslit"),
                ChoiceOption("run", "Spustit automatizaci se vstupem", "spustit"),
            )
        ).apply { hiddenInSummaryWhen = { true } },
        ParamDef.Text("tap_input", "Vstup po klepnutí", placeholder = "třeba dalsi, prepnout, id položky").apply {
            hiddenWhen = { !isTap(it, "refresh", "run") }
            hiddenInSummaryWhen = { true }
        },
        ParamDef.Automation("tap_automation", "Automatizace po klepnutí").apply {
            hiddenWhen = { !isTap(it, "run") }
            hiddenInSummaryWhen = { true }
        },
    )

    private fun colorParam(key: String, label: String) = ParamDef.Text(key, label, placeholder = "automaticky", colorPicker = true)
        .apply { hiddenInSummaryWhen = { true } }

    private fun element(ctx: RunContext, type: String, block: Block, props: Map<String, String>): BlockResult {
        add(ctx, Node(type, props))
        return BlockResult.None
    }

    val all = listOf(
        BlockDef(
            type = BUILD,
            summary = "Widget z bloků",
            title = "Widget z bloků",
            description = "Postaví widget na ploše z nativních částí: bloky Widget: text, tlačítko, obrázek, řádek / sloupec, " +
                    "posouvací seznam… dej dovnitř. Na konci skupiny se widget pošle na plochu (do toho, který se kreslí, " +
                    "jinak do všech widgetů této automatizace). Klepnutí na tlačítko může widget překreslit se vstupem – " +
                    "automatizace ho dostane jako Vstup a podle něj může ukázat něco jiného.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Widgets,
            kind = BlockKind.Scope,
            params = listOf(
                colorParam("background", "Pozadí"),
                ParamDef.Toggle("rounded", "Zaoblené rohy", default = true),
                ParamDef.Number("padding", "Okraj", 0f, 48f, 12f, unit = "dp").apply { hiddenInSummaryWhen = { true } },
                alignParam(),
            ),
            outputName = "Rozvržení widgetu",
        ),
        BlockDef(
            type = END_BUILD, summary = "Konec widgetu", title = "Konec widgetu", description = "",
            category = BlockCategory.Other, icon = Icons.Rounded.Widgets, kind = BlockKind.End, showInLibrary = false,
        ),

        BlockDef(
            type = BOX,
            summary = "Widget: {direction}",
            title = "Widget: řádek / sloupec",
            description = "Skupina částí widgetu vedle sebe (řádek) nebo pod sebou (sloupec), s vlastním pozadím a okrajem. " +
                    "Klepnutí na ni může widget překreslit nebo spustit automatizaci.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.ViewAgenda,
            kind = BlockKind.Scope,
            params = listOf(
                ParamDef.Choice(
                    "direction", "Směr", listOf(
                        ChoiceOption("row", "Řádek (vedle sebe)", "řádek"),
                        ChoiceOption("column", "Sloupec (pod sebou)", "sloupec"),
                    )
                ),
                sizeParam(),
                alignParam(),
                colorParam("background", "Pozadí"),
                ParamDef.Toggle("rounded", "Zaoblené rohy", default = true),
                ParamDef.Number("padding", "Okraj", 0f, 48f, 0f, unit = "dp").apply { hiddenInSummaryWhen = { true } },
            ) + tapParams(none = true),
        ),
        BlockDef(
            type = END_BOX, summary = "Konec řádku / sloupce", title = "Konec řádku / sloupce", description = "",
            category = BlockCategory.Other, icon = Icons.Rounded.ViewAgenda, kind = BlockKind.End, showInLibrary = false,
        ),

        BlockDef(
            type = LIST,
            summary = "Widget: posouvací seznam",
            title = "Widget: posouvací seznam",
            description = "Seznam, který jde ve widgetu posouvat prstem. Každý blok uvnitř je jedna položka – víc částí " +
                    "v jedné položce dej do Widget: řádek / sloupec. Položky z dat udělá blok Opakovat nebo Pro každou " +
                    "položku uvnitř. Widget může mít až 4 seznamy.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.ViewList,
            kind = BlockKind.Scope,
            params = listOf(ParamDef.Number("spacing", "Mezera mezi položkami", 0f, 32f, 6f, unit = "dp")),
        ),
        BlockDef(
            type = END_LIST, summary = "Konec seznamu", title = "Konec seznamu", description = "",
            category = BlockCategory.Other, icon = Icons.Rounded.ViewList, kind = BlockKind.End, showInLibrary = false,
        ),

        BlockDef(
            type = TEXT,
            summary = "Widget: text {text}",
            title = "Widget: text",
            description = "Text ve widgetu: velikost, barva, tučně, zarovnání a nejvýš kolik řádků (zbytek se zkrátí na …).",
            category = BlockCategory.Other,
            icon = Icons.Rounded.TextFields,
            params = listOf(
                ParamDef.Text("text", "Text", defaultRef = VarRef.Input),
                ParamDef.Number("text_size", "Velikost písma", 8f, 64f, 14f, unit = "sp").apply { hiddenInSummaryWhen = { true } },
                colorParam("color", "Barva"),
                ParamDef.Toggle("bold", "Tučně", summaryWhenOn = "tučně"),
                alignParam(),
                ParamDef.Number("lines", "Nejvýš řádků (0 = bez omezení)", 0f, 20f, 0f).apply { hiddenInSummaryWhen = { true } },
                sizeParam(),
            ) + tapParams(none = true),
        ) { block -> element(this, TEXT, block, props(this, block)) },

        BlockDef(
            type = BUTTON,
            summary = "Widget: tlačítko {text}",
            title = "Widget: tlačítko",
            description = "Tlačítko ve widgetu. Klepnutí překreslí widget se zadaným vstupem (automatizace widgetu ho dostane " +
                    "jako Vstup), nebo spustí jinou automatizaci.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.SmartButton,
            params = listOf(ParamDef.Text("text", "Text tlačítka", default = "OK")) + tapParams(none = false) + listOf(
                colorParam("background", "Barva tlačítka"),
                colorParam("color", "Barva textu"),
                sizeParam(),
            ),
        ) { block -> element(this, BUTTON, block, props(this, block)) },

        BlockDef(
            type = IMAGE,
            summary = "Widget: obrázek {image}",
            title = "Widget: obrázek",
            description = "Obrázek nebo ikona ve widgetu v zadané výšce (šířka podle poměru stran). Zdroj může být vložený obrázek, " +
                    "soubor nebo adresa na internetu.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Image,
            params = listOf(
                ParamDef.Text("image", "Obrázek", imagePicker = true),
                ParamDef.Number("height", "Výška", 8f, 400f, 48f, unit = "dp"),
                colorParam("tint", "Přebarvit na"),
            ) + tapParams(none = true),
        ) { block -> element(this, IMAGE, block, props(this, block)) },

        BlockDef(
            type = PROGRESS,
            summary = "Widget: průběh {value} z {max}",
            title = "Widget: průběh",
            description = "Vodorovná lišta průběhu přes celou šířku, třeba baterie nebo splněné úkoly.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.LinearScale,
            params = listOf(
                ParamDef.Text("value", "Hodnota", defaultRef = VarRef.Input),
                ParamDef.Text("max", "Maximum", default = "100"),
            ),
        ) { block -> element(this, PROGRESS, block, props(this, block)) },

        BlockDef(
            type = SPACE,
            summary = "Widget: mezera {space}",
            title = "Widget: mezera",
            description = "Volné místo mezi částmi widgetu. Vyplnit zbytek = odsune další části na konec řádku nebo dolů.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.SpaceBar,
            params = listOf(
                ParamDef.Number("space", "Velikost", 0f, 64f, 8f, unit = "dp"),
                ParamDef.Toggle("fill", "Vyplnit zbylé místo", summaryWhenOn = "a vyplnit zbytek"),
            ),
        ) { block -> element(this, SPACE, block, props(this, block)) },
    )
}
