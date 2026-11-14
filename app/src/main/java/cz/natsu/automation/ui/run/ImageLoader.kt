package cz.natsu.automation.ui.run

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Small image loader for the "Ukázat obrázky" dialog: https://, content:// and file:// sources. */
object ImageLoader {
    private const val MAX_BYTES = 25 * 1024 * 1024
    private val cache = LruCache<String, Bitmap>(24)

    suspend fun load(context: Context, source: String, maxSize: Int = 1440): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(source)?.let { return@withContext it.asImageBitmap() }
        val bytes = try {
            read(context, source.trim())
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        cache.put(source, bitmap)
        bitmap.asImageBitmap()
    }

    private fun read(context: Context, source: String): ByteArray? {
        val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
        return when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            "file" -> File(uri.path ?: return null).takeIf { it.length() < MAX_BYTES }?.readBytes()
            "http", "https" -> {
                // Android blocks plain http, so try https instead.
                val url = URL(source.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://"))
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    conn.setRequestProperty("User-Agent", "Automation (Android)")
                    if (conn.responseCode >= 400) return null
                    conn.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            if (out.size() > MAX_BYTES) return null
                        }
                        out.toByteArray()
                    }
                } finally {
                    conn.disconnect()
                }
            }
            else -> null
        }
    }
}
