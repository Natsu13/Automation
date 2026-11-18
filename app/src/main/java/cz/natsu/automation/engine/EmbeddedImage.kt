package cz.natsu.automation.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import java.io.ByteArrayOutputStream

/**
 * An image stored right inside the automation as a `data:image/png;base64,…` text.
 * It travels with the automation (export, import, backup) and every image block
 * takes it like a file.
 */
object EmbeddedImage {
    /** Longest side of an image chosen in the editor; enough for a notification icon, small to store. */
    const val MAX_SIDE = 256

    /** Size an icon from the catalog is drawn at. */
    const val ICON_SIZE = 192

    fun isEmbedded(text: String): Boolean = text.trimStart().startsWith("data:image/", ignoreCase = true)

    /** Bytes and type (image/png…) of a data: text. */
    fun decode(text: String): Pair<ByteArray, String> {
        val trimmed = text.trim()
        val comma = trimmed.indexOf(',')
        if (!trimmed.startsWith("data:", true) || comma < 0) throw BlockException(cz.natsu.automation.i18n.tr("vložený obrázek je poškozený"))
        val header = trimmed.substring(5, comma)
        val mime = header.substringBefore(';').ifBlank { "image/png" }
        val body = trimmed.substring(comma + 1)
        val bytes = if (header.endsWith(";base64", true)) {
            try {
                Base64.decode(body, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw BlockException(cz.natsu.automation.i18n.tr("vložený obrázek je poškozený"))
            }
        } else java.net.URLDecoder.decode(body, "UTF-8").toByteArray()
        return bytes to mime
    }

    fun bitmap(text: String): Bitmap? = runCatching {
        val bytes = decode(text).first
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    /** Shrinks to [MAX_SIDE] and stores as PNG (keeps transparency) or JPG when that is smaller. */
    fun encode(source: Bitmap, maxSide: Int = MAX_SIDE): String {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else source
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        if (!bitmap.hasAlpha()) {
            val jpg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            if (jpg.size < png.size) return "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP)
        }
        return "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
    }

    /** Draws a vector icon in one [color] on a transparent square. */
    fun fromIcon(icon: ImageVector, color: Int = Color.WHITE, size: Int = ICON_SIZE): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(size / icon.viewportWidth, size / icon.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        drawNode(canvas, icon.root, paint)
        return bitmap
    }

    private fun drawNode(canvas: Canvas, node: VectorNode, paint: Paint) {
        when (node) {
            is VectorGroup -> {
                canvas.save()
                canvas.translate(node.translationX + node.pivotX, node.translationY + node.pivotY)
                canvas.rotate(node.rotation)
                canvas.scale(node.scaleX, node.scaleY)
                canvas.translate(-node.pivotX, -node.pivotY)
                for (child in node) drawNode(canvas, child, paint)
                canvas.restore()
            }
            is VectorPath -> {
                if (node.fill == null) return
                val path = PathParser().addPathNodes(node.pathData).toPath().apply { fillType = node.pathFillType }
                val alpha = (node.fillAlpha * 255).toInt().coerceIn(0, 255)
                val solid = (node.fill as? SolidColor)?.value?.toArgb()
                paint.alpha = if (solid != null) (Color.alpha(solid) * alpha / 255) else alpha
                canvas.drawPath(path.asAndroidPath(), paint)
            }
        }
    }
}
