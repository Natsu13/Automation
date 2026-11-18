package cz.natsu.automation.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.runBlocking

/**
 * Drawing into images from the Script block. Every function takes an image (a stored
 * data:image, a file, an https:// address or the output of an earlier step), draws
 * one thing and returns a new PNG file, so the calls chain:
 *
 * ```
 * ikona = img_scale(obrazek, 0.7)
 * platno = img_new(192, 192)
 * platno = img_draw(platno, ikona, 29, 0)
 * vrat img_bar(platno, 16, 160, 160, 20, volno, "zelená")
 * ```
 *
 * Coordinates and sizes are in pixels from the top left corner; colours are names
 * (bílá, red…) or #RRGGBB / #AARRGGBB, like everywhere in the app.
 */
object ImageScript {
    /** Names (English and Czech) of every function, for the help and the AI guide. */
    val names = setOf(
        "img_new", "obr_novy", "img_size", "obr_velikost", "img_scale", "obr_zmen", "img_resize", "obr_rozmer",
        "img_crop", "obr_orizni", "img_draw", "obr_vloz", "img_rect", "obr_obdelnik", "img_border", "obr_ramecek",
        "img_line", "obr_cara", "img_circle", "obr_kruh", "img_arc", "obr_oblouk", "img_text", "obr_text",
        "img_tint", "obr_obarvi", "img_fill", "obr_vypln", "img_bar", "obr_lista",
    )

    /** Pictures this run just made, so the next call does not read the file back. */
    private val recent = LruCache<String, Bitmap>(8)

    /** The result of [call], or [Script.MISSING] when [name] is not an image function. */
    fun call(context: Context, name: String, args: List<Any?>): Any? {
        if (name !in names) return Script.MISSING
        val a = Args(name, args)
        val result: Bitmap = when (name) {
            "img_new", "obr_novy" -> {
                a.need(2..3)
                val bitmap = Bitmap.createBitmap(a.size(0), a.size(1), Bitmap.Config.ARGB_8888)
                if (a.has(2)) bitmap.eraseColor(a.color(2))
                bitmap
            }
            "img_size", "obr_velikost" -> {
                a.need(1..1)
                val image = load(context, a.text(0))
                return linkedMapOf<String, Any?>("width" to image.width.toDouble(), "height" to image.height.toDouble())
            }
            "img_scale", "obr_zmen" -> {
                a.need(2..2)
                val image = load(context, a.text(0))
                val factor = a.num(1).toFloat()
                if (factor <= 0f || factor > 20f) a.fail(tr("zvětšení má být větší než 0 a nejvýš 20"))
                scaled(image, (image.width * factor).toInt(), (image.height * factor).toInt())
            }
            "img_resize", "obr_rozmer" -> {
                a.need(2..3)
                val image = load(context, a.text(0))
                val width = a.size(1)
                // Without a height the image keeps its proportions.
                val height = if (a.has(2)) a.size(2) else (image.height * width.toFloat() / image.width).toInt().coerceAtLeast(1)
                scaled(image, width, height)
            }
            "img_crop", "obr_orizni" -> {
                a.need(5..5)
                val image = load(context, a.text(0))
                val x = a.int(1).coerceIn(0, image.width - 1)
                val y = a.int(2).coerceIn(0, image.height - 1)
                Bitmap.createBitmap(image, x, y, a.size(3).coerceAtMost(image.width - x), a.size(4).coerceAtMost(image.height - y))
            }
            "img_draw", "obr_vloz" -> {
                a.need(4..6)
                val base = copy(load(context, a.text(0)))
                val over = load(context, a.text(1))
                val x = a.float(2)
                val y = a.float(3)
                val width = if (a.has(4)) a.float(4) else over.width.toFloat()
                // Only a width keeps the proportions of the inserted picture.
                val height = if (a.has(5)) a.float(5) else over.height * width / over.width
                Canvas(base).drawBitmap(over, null, RectF(x, y, x + width, y + height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                base
            }
            "img_rect", "obr_obdelnik" -> {
                a.need(6..7)
                draw(context, a) { canvas ->
                    val paint = fill(a.color(5))
                    canvas.drawRoundRect(rect(a, 1), a.optFloat(6), a.optFloat(6), paint)
                }
            }
            "img_border", "obr_ramecek" -> {
                a.need(3..4)
                draw(context, a) { canvas ->
                    val width = a.float(2)
                    val half = width / 2f
                    val box = RectF(half, half, canvas.width - half, canvas.height - half)
                    canvas.drawRoundRect(box, a.optFloat(3), a.optFloat(3), stroke(a.color(1), width))
                }
            }
            "img_line", "obr_cara" -> {
                a.need(6..7)
                draw(context, a) { canvas ->
                    val paint = stroke(a.color(5), if (a.has(6)) a.float(6) else 2f).apply { strokeCap = Paint.Cap.ROUND }
                    canvas.drawLine(a.float(1), a.float(2), a.float(3), a.float(4), paint)
                }
            }
            "img_circle", "obr_kruh" -> {
                a.need(5..6)
                draw(context, a) { canvas ->
                    // Without a line width the circle is filled.
                    val paint = if (a.has(5) && a.float(5) > 0f) stroke(a.color(4), a.float(5)) else fill(a.color(4))
                    canvas.drawCircle(a.float(1), a.float(2), a.float(3), paint)
                }
            }
            "img_arc", "obr_oblouk" -> {
                a.need(7..8)
                draw(context, a) { canvas ->
                    val cx = a.float(1); val cy = a.float(2); val r = a.float(3)
                    val percent = a.num(4).toFloat().coerceIn(0f, 100f)
                    val width = a.float(6)
                    val box = RectF(cx - r, cy - r, cx + r, cy + r)
                    if (a.has(7)) canvas.drawArc(box, 0f, 360f, false, stroke(a.color(7), width))
                    // A ring that fills clockwise from the top.
                    canvas.drawArc(box, -90f, 360f * percent / 100f, false, stroke(a.color(5), width).apply { strokeCap = Paint.Cap.ROUND })
                }
            }
            "img_text", "obr_text" -> {
                a.need(6..7)
                draw(context, a) { canvas ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = a.color(5)
                        textSize = a.float(4).coerceAtLeast(1f)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = when (if (a.has(6)) a.text(6).lowercase() else "center") {
                            "left", "vlevo" -> Paint.Align.LEFT
                            "right", "vpravo" -> Paint.Align.RIGHT
                            else -> Paint.Align.CENTER
                        }
                    }
                    // y is the middle of the text, so it is easy to place it in a box.
                    val metrics = paint.fontMetrics
                    canvas.drawText(a.text(1), a.float(2), a.float(3) - (metrics.ascent + metrics.descent) / 2f, paint)
                }
            }
            "img_tint", "obr_obarvi" -> {
                a.need(2..2)
                ImageTools.tint(load(context, a.text(0)), a.color(1))
            }
            "img_fill", "obr_vypln" -> {
                a.need(3..5)
                val image = load(context, a.text(0))
                val percent = a.num(1).toFloat().coerceIn(0f, 100f) / 100f
                val direction = if (a.has(3)) a.text(3).lowercase() else "up"
                val w = image.width.toFloat()
                val h = image.height.toFloat()
                // The part that is "done" in the colour, the rest in the second colour (or as it was).
                val done = when (direction) {
                    "down", "dolu", "dolů" -> RectF(0f, 0f, w, h * percent)
                    "right", "vpravo" -> RectF(0f, 0f, w * percent, h)
                    "left", "vlevo" -> RectF(w * (1 - percent), 0f, w, h)
                    else -> RectF(0f, h * (1 - percent), w, h)
                }
                val result = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                val rest = if (a.has(4)) Paint().apply { colorFilter = PorterDuffColorFilter(a.color(4), PorterDuff.Mode.SRC_IN) } else Paint()
                canvas.drawBitmap(image, 0f, 0f, rest)
                canvas.save()
                canvas.clipRect(done)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                canvas.drawBitmap(image, 0f, 0f, Paint().apply { colorFilter = PorterDuffColorFilter(a.color(2), PorterDuff.Mode.SRC_IN) })
                canvas.restore()
                result
            }
            "img_bar", "obr_lista" -> {
                a.need(7..9)
                draw(context, a) { canvas ->
                    val box = rect(a, 1)
                    val percent = a.num(5).toFloat().coerceIn(0f, 100f) / 100f
                    val radius = if (a.has(8)) a.float(8) else box.height() / 2f
                    canvas.drawRoundRect(box, radius, radius, fill(if (a.has(7)) a.color(7) else 0x55FFFFFF))
                    if (percent > 0f) {
                        canvas.save()
                        canvas.clipRect(box.left, box.top, box.left + box.width() * percent, box.bottom)
                        canvas.drawRoundRect(box, radius, radius, fill(a.color(6)))
                        canvas.restore()
                    }
                }
            }
            else -> return Script.MISSING
        }
        val path = runBlocking { ImageTools.save(context, result, ImageTools.Format.PNG, 100, "obrazek") }
        recent.put(path, result)
        return path
    }

    private fun load(context: Context, source: String): Bitmap =
        recent.get(source.trim()) ?: runBlocking { ImageTools.load(context, source).bitmap }

    private fun copy(bitmap: Bitmap): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    private fun scaled(bitmap: Bitmap, width: Int, height: Int): Bitmap =
        Bitmap.createScaledBitmap(bitmap, width.coerceIn(1, 4096), height.coerceIn(1, 4096), true)

    /** A copy of the first argument with something drawn on it. */
    private inline fun draw(context: Context, a: Args, block: (Canvas) -> Unit): Bitmap {
        val bitmap = copy(load(context, a.text(0)))
        block(Canvas(bitmap))
        return bitmap
    }

    private fun rect(a: Args, from: Int): RectF {
        val x = a.float(from); val y = a.float(from + 1)
        return RectF(x, y, x + a.float(from + 2), y + a.float(from + 3))
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width.coerceAtLeast(0.5f)
    }

    private class Args(val name: String, val values: List<Any?>) {
        fun fail(message: String): Nothing = throw BlockException("$name: $message")

        fun need(range: IntRange) {
            if (values.size !in range) {
                val count = if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}"
                fail(tr("počet hodnot v závorce má být %s, je %s", count, values.size))
            }
        }

        /** Given and not empty (nic or "" counts as left out). */
        fun has(i: Int) = i < values.size && values[i] != null && Script.text(values[i]).isNotEmpty()

        fun text(i: Int) = Script.text(values.getOrNull(i))
        fun num(i: Int): Double = Script.numberOf(values.getOrNull(i)) ?: fail(tr("„%s“ není číslo", text(i).take(30)))
        fun float(i: Int) = num(i).toFloat()
        fun int(i: Int) = num(i).toInt()
        fun optFloat(i: Int) = if (has(i)) float(i) else 0f
        fun size(i: Int): Int = int(i).also { if (it !in 1..4096) fail(tr("rozměr má být 1 až 4096 px, je %s", it)) }
        fun color(i: Int): Int = ImageTools.colorOrNull(text(i)) ?: fail(tr("„%s“ není barva (např. červená, #FF9800)", text(i)))
    }
}
