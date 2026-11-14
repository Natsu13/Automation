package cz.natsu.automation.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PhotoFilter
import cz.natsu.automation.data.ParamValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/**
 * Image blocks. Each one does a single thing and returns a new file (file://),
 * so they chain: Vybrat obrázek → Upravit (zmenšit) → Upravit (převést) → HTTP.
 */
object ImageBlocks {
    private fun choiceNot(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value !in values
    }

    private val sourceHint = "výstup bloku Vybrat soubor, stažený soubor nebo https:// adresa"

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = "image_edit",
            summary = "Obrázek: {op} {max_size}{angle}{flip}{format}",
            title = "Upravit obrázek",
            description = "Jedna úprava obrázku: zmenšit, otočit, převrátit, oříznout na čtverec, nebo převést " +
                    "na JPG / PNG / WEBP. Vrátí nový soubor – další úpravu přidáš dalším blokem.",
            category = BlockCategory.Images,
            icon = Icons.Rounded.PhotoFilter,
            params = listOf(
                ParamDef.Text("source", "Obrázek", placeholder = sourceHint),
                ParamDef.Choice(
                    "op", "Úprava", listOf(
                        ChoiceOption("resize", "Zmenšit", "zmenšit na"),
                        ChoiceOption("rotate", "Otočit", "otočit o"),
                        ChoiceOption("flip", "Převrátit (zrcadlit)", "převrátit"),
                        ChoiceOption("square", "Oříznout na čtverec (střed)", "oříznout na čtverec"),
                        ChoiceOption("convert", "Převést formát", "převést na"),
                    )
                ),
                ParamDef.Number("max_size", "Delší strana nejvýš", 100f, 4000f, 1600f, step = 100f, unit = "px").apply {
                    hiddenWhen = choiceNot("op", "resize")
                },
                ParamDef.Choice(
                    "angle", "Otočit o", listOf(
                        ChoiceOption("90", "90° doprava", "90°"),
                        ChoiceOption("180", "180°", "180°"),
                        ChoiceOption("270", "90° doleva", "-90°"),
                    )
                ).apply { hiddenWhen = choiceNot("op", "rotate") },
                ParamDef.Choice(
                    "flip", "Směr", listOf(
                        ChoiceOption("horizontal", "Vodorovně (zleva doprava)", "vodorovně"),
                        ChoiceOption("vertical", "Svisle (shora dolů)", "svisle"),
                    )
                ).apply { hiddenWhen = choiceNot("op", "flip") },
                ParamDef.Choice(
                    "format", "Formát", listOf(
                        ChoiceOption("jpeg", "JPG", "JPG"),
                        ChoiceOption("png", "PNG (bez ztráty)", "PNG"),
                        ChoiceOption("webp", "WEBP", "WEBP"),
                    )
                ).apply { hiddenWhen = choiceNot("op", "convert") },
                ParamDef.Number("quality", "Kvalita", 10f, 100f, 85f, step = 5f, unit = "%").apply {
                    hiddenWhen = { choiceNot("op", "convert")(it) || (it["format"] as? ParamValue.Choice)?.value == "png" }
                },
            ),
            outputName = "Upravený obrázek",
        ) { block ->
            val image = ImageTools.load(context, text(block, "source"))
            val bitmap = image.bitmap
            val result: Bitmap
            var format = image.format
            var quality = 90
            when (choice(block, "op")) {
                "rotate" -> result = ImageTools.transform(bitmap) { postRotate(choice(block, "angle").toFloatOrNull() ?: 90f) }
                "flip" -> result = ImageTools.transform(bitmap) {
                    if (choice(block, "flip") == "vertical") postScale(1f, -1f) else postScale(-1f, 1f)
                }
                "square" -> {
                    val side = minOf(bitmap.width, bitmap.height)
                    result = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                }
                "convert" -> {
                    result = bitmap
                    format = ImageTools.Format.of(choice(block, "format"))
                    quality = number(block, "quality").toInt()
                }
                else -> {
                    val max = number(block, "max_size").toInt().coerceAtLeast(1)
                    val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height))
                    result = if (scale < 1f) {
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt().coerceAtLeast(1),
                            (bitmap.height * scale).toInt().coerceAtLeast(1),
                            true,
                        )
                    } else bitmap
                }
            }
            BlockResult.Output(ImageTools.save(context, result, format, quality, image.name))
        },

        BlockDef(
            type = "image_info",
            summary = "Zjistit o obrázku {what}",
            title = "Informace o obrázku",
            description = "Šířka, výška, velikost souboru a typ obrázku – třeba pro podmínku \"je větší než\".",
            category = BlockCategory.Images,
            icon = Icons.Rounded.ImageSearch,
            params = listOf(
                ParamDef.Text("source", "Obrázek", placeholder = sourceHint),
                ParamDef.Choice(
                    "what", "Co zjistit", listOf(
                        ChoiceOption("all", "Vše jako JSON (width, height, bytes, type, name)", "vše"),
                        ChoiceOption("width", "Šířku (px)", "šířku"),
                        ChoiceOption("height", "Výšku (px)", "výšku"),
                        ChoiceOption("bytes", "Velikost souboru (bajty)", "velikost"),
                        ChoiceOption("kb", "Velikost souboru (kB)", "velikost v kB"),
                        ChoiceOption("type", "Typ (image/jpeg…)", "typ"),
                    )
                ),
            ),
            outputName = "Údaj o obrázku",
        ) { block ->
            val info = ImageTools.info(context, text(block, "source"))
            val result = when (choice(block, "what")) {
                "width" -> info.width.toString()
                "height" -> info.height.toString()
                "bytes" -> info.bytes.toString()
                "kb" -> formatNumber(Math.round(info.bytes / 102.4) / 10.0)
                "type" -> info.mime
                else -> JsonObject(
                    mapOf(
                        "width" to JsonPrimitive(info.width),
                        "height" to JsonPrimitive(info.height),
                        "bytes" to JsonPrimitive(info.bytes),
                        "type" to JsonPrimitive(info.mime),
                        "name" to JsonPrimitive(info.name),
                    )
                ).toString()
            }
            BlockResult.Output(result)
        },
    )
}

object ImageTools {
    enum class Format(val extension: String) {
        JPEG("jpg"),
        PNG("png"),
        WEBP("webp");

        val compress: Bitmap.CompressFormat
            get() = when (this) {
                JPEG -> Bitmap.CompressFormat.JPEG
                PNG -> Bitmap.CompressFormat.PNG
                WEBP -> if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                }
            }

        companion object {
            fun of(value: String) = when (value) {
                "png" -> PNG
                "webp" -> WEBP
                else -> JPEG
            }

            fun ofMime(mime: String?) = when (mime) {
                "image/png" -> PNG
                "image/webp" -> WEBP
                else -> JPEG
            }
        }
    }

    class Loaded(val bitmap: Bitmap, val format: Format, val name: String)
    class Info(val width: Int, val height: Int, val bytes: Long, val mime: String, val name: String)

    /** Biggest side a loaded image may have, so a huge photo does not run out of memory. */
    private const val MAX_DECODE = 4096

    /** A local file (content://, file://) or an https:// address downloaded first. */
    private suspend fun local(context: Context, source: String): HttpBlocks.Source {
        val trimmed = source.trim().removePrefix("@")
        if (trimmed.isEmpty()) throw BlockException("obrázek není zadaný")
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            val response = HttpBlocks.request(context, "GET", trimmed, emptyList(), null, saveToFile = true)
            if (response.code >= 400) throw BlockException("obrázek nejde stáhnout (chyba ${response.code})")
            return HttpBlocks.openSource(context, response.file ?: throw BlockException("obrázek nejde stáhnout"))
        }
        return HttpBlocks.openSource(context, trimmed)
    }

    suspend fun load(context: Context, source: String): Loaded = withContext(Dispatchers.IO) {
        val file = local(context, source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw BlockException("soubor není obrázek")
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE || bounds.outHeight / sample > MAX_DECODE) sample *= 2
        val decoded = file.open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw BlockException("obrázek nejde načíst")
        // Photos are often stored sideways with an EXIF note how to turn them.
        val orientation = runCatching {
            file.open().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val upright = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> transform(decoded) { postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> transform(decoded) { postRotate(180f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform(decoded) { postRotate(270f) }
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform(decoded) { postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform(decoded) { postScale(1f, -1f) }
            else -> decoded
        }
        Loaded(upright, Format.ofMime(bounds.outMimeType ?: file.mime), file.name)
    }

    fun transform(bitmap: Bitmap, change: Matrix.() -> Unit): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply(change), true)

    /** Saves into the app cache (old files are cleaned up after a day); returns file://. */
    suspend fun save(context: Context, bitmap: Bitmap, format: Format, quality: Int, name: String): String =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "images").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
            // Keep the original name (without an earlier "-abc123" suffix) so uploads have a sensible file name.
            val base = name.substringBeforeLast('.').replace(Regex("-[0-9a-f]{6}$"), "")
                .replace(Regex("[^\\w\\-]+"), "_").take(40).ifBlank { "obrazek" }
            val file = File(dir, "$base-${UUID.randomUUID().toString().take(6)}.${format.extension}")
            file.outputStream().use { bitmap.compress(format.compress, quality.coerceIn(1, 100), it) }
            Uri.fromFile(file).toString()
        }

    suspend fun info(context: Context, source: String): Info = withContext(Dispatchers.IO) {
        val file = local(context, source)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var bytes = 0L
        file.open().use { input ->
            val counting = object : java.io.FilterInputStream(input) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytes += it }
            }
            BitmapFactory.decodeStream(counting, null, bounds)
            // Count the rest of the file too.
            val buffer = ByteArray(64 * 1024)
            while (counting.read(buffer, 0, buffer.size) > 0) Unit
        }
        if (bounds.outWidth <= 0) throw BlockException("soubor není obrázek")
        Info(bounds.outWidth, bounds.outHeight, bytes, bounds.outMimeType ?: file.mime, file.name)
    }
}
