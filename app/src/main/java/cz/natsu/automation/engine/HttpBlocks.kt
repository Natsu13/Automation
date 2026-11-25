package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Http
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.UUID

/** HTTP / API requests, files and images. */
object HttpBlocks {
    private const val MAX_RESPONSE = 20 * 1024 * 1024

    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    private fun choiceNot(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value !in values
    }

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = "http_request",
            summary = "{method} {url}{only_body}{save_file}",
            title = "HTTP požadavek (API)",
            description = "Zavolá API: GET, POST, PUT, PATCH, DELETE s hlavičkami a tělem (JSON, formulář, " +
                    "soubory / obrázky přes multipart). Vrátí objekt odpovědi {status, ok, headers, body}, " +
                    "z něj pak vytáhneš co potřebuješ (např. body.items[*].url) blokem Získat z JSON.",
            category = BlockCategory.Data,
            icon = Icons.Rounded.Http,
            params = listOf(
                ParamDef.Choice(
                    "method", "Metoda", listOf(
                        ChoiceOption("GET", "GET – načíst"),
                        ChoiceOption("POST", "POST – odeslat", "POST"),
                        ChoiceOption("PUT", "PUT – nahradit", "PUT"),
                        ChoiceOption("PATCH", "PATCH – upravit", "PATCH"),
                        ChoiceOption("DELETE", "DELETE – smazat", "DELETE"),
                    ).map { it.copy(short = it.value) }
                ),
                ParamDef.Text("url", "Adresa", placeholder = "https://api.example.com/items"),
                ParamDef.Text("headers", "Hlavičky (klíč: hodnota na řádek)", placeholder = "Authorization: Bearer …"),
                ParamDef.Choice(
                    "body_type", "Tělo", listOf(
                        ChoiceOption("none", "Žádné"),
                        ChoiceOption("json", "JSON"),
                        ChoiceOption("form", "Formulář (klíč=hodnota)"),
                        ChoiceOption("multipart", "Multipart – text i soubory / obrázky"),
                        ChoiceOption("text", "Vlastní text"),
                        ChoiceOption("file", "Celý soubor"),
                    )
                ).apply { hiddenWhen = choiceIs("method", "GET") },
                ParamDef.Text("body", "Obsah těla", placeholder = "{\"title\": \"Ahoj\"}").apply {
                    hiddenWhen = { choiceIs("method", "GET")(it) || choiceNot("body_type", "json", "text")(it) }
                },
                ParamDef.Text(
                    "fields", "Pole (klíč=hodnota na řádek, soubor jako klíč=@soubor)",
                    placeholder = "name=Jan\nphoto=@content://…"
                ).apply {
                    hiddenWhen = { choiceIs("method", "GET")(it) || choiceNot("body_type", "form", "multipart")(it) }
                },
                ParamDef.Text("file", "Soubor", placeholder = "výstup bloku Vybrat soubor").apply {
                    hiddenWhen = { choiceIs("method", "GET")(it) || choiceNot("body_type", "file")(it) }
                },
                ParamDef.Text("content_type", "Content-Type", default = "text/plain; charset=utf-8", plain = true).apply {
                    hiddenWhen = { choiceIs("method", "GET")(it) || choiceNot("body_type", "text")(it) }
                },
                ParamDef.Toggle("only_body", "Vrátit jen tělo odpovědi (místo celého objektu)", summaryWhenOn = " → jen tělo"),
                ParamDef.Toggle("save_file", "Uložit odpověď jako soubor (např. obrázek)", summaryWhenOn = " do souboru"),
                ParamDef.Toggle("fail", "Chyba, když server vrátí 4xx / 5xx", default = true),
            ),
            outputName = "Odpověď API",
        ) { block ->
            val url = text(block, "url").trim()
            if (url.isEmpty()) throw BlockException(tr("adresa je prázdná"))
            val method = choice(block, "method").ifEmpty { "GET" }
            val body: RequestBody? = if (method == "GET") null else when (choice(block, "body_type")) {
                "json" -> {
                    val text = text(block, "body")
                    if (text.isNotBlank()) JsonTools.parse(text)
                    RequestBody.Bytes(text.toByteArray(), "application/json; charset=utf-8")
                }
                "text" -> RequestBody.Bytes(text(block, "body").toByteArray(), text(block, "content_type"))
                "form" -> RequestBody.Bytes(
                    JsonTools.pairs(text(block, "fields"), charArrayOf('=', ':')).joinToString("&") { (k, v) ->
                        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                    }.toByteArray(),
                    "application/x-www-form-urlencoded"
                )
                "multipart" -> RequestBody.Multipart(JsonTools.pairs(text(block, "fields"), charArrayOf('=', ':')))
                "file" -> RequestBody.FileBody(text(block, "file").trim().removePrefix("@"))
                else -> null
            }
            val headers = JsonTools.pairs(text(block, "headers"), charArrayOf(':'))
            // Blocks saved before the toggles existed have an "output" choice instead.
            val legacy = (block.params["output"] as? ParamValue.Choice)?.value
                ?.takeIf { "only_body" !in block.params && "save_file" !in block.params }
            val saveFile = if (legacy != null) legacy == "file" else bool(block, "save_file")
            val onlyBody = if (legacy != null) legacy == "body" || legacy == "file" else bool(block, "only_body")
            val response = request(context, method, url, headers, body, saveToFile = saveFile)
            if (bool(block, "fail") && response.code >= 400) {
                val detail = response.text.trim().take(200)
                throw BlockException(tr("server vrátil chybu %s", response.code) + if (detail.isNotEmpty()) ": $detail" else "")
            }
            val result = when {
                !onlyBody -> responseObject(response).toString()
                saveFile -> response.file ?: ""
                else -> response.text
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "pick_file",
            needsWindow = true,
            summary = "Vybrat {kind}",
            title = "Vybrat soubor / obrázek",
            description = "Nechá vybrat obrázek nebo soubor. Výstup jde upravit blokem Upravit obrázek, " +
                    "poslat v HTTP požadavku (pole=@výstup) nebo zobrazit blokem Ukázat obrázky.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.AttachFile,
            params = listOf(
                ParamDef.Choice(
                    "kind", "Co vybrat", listOf(
                        ChoiceOption("image/*", "Obrázek", "obrázek"),
                        ChoiceOption("video/*", "Video", "video"),
                        ChoiceOption("*/*", "Jakýkoli soubor", "soubor"),
                    )
                ),
            ),
            outputName = "Vybraný soubor",
        ) { block ->
            val ui = ui ?: throw BlockException(tr("výběr souboru potřebuje okno (spusť akci ručně)"))
            val picked = ui.pickFile(choice(block, "kind").ifEmpty { "*/*" }) ?: throw RunCancelledException()
            BlockResult.Output(picked)
        },

        BlockDef(
            type = "show_images",
            needsWindow = true,
            summary = "Ukázat obrázky {images}",
            title = "Ukázat obrázky",
            description = "Zobrazí obrázky z adres (JSON pole nebo jedna na řádek), vybraných nebo stažených souborů. " +
                    "Může nechat jeden vybrat.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Collections,
            params = listOf(
                ParamDef.Text("title", "Nadpis", default = "Obrázky"),
                ParamDef.Text("images", "Obrázky", defaultRef = VarRef.Input),
                ParamDef.Toggle("pick", "Nechat jeden vybrat", summaryWhenOn = "a vybrat"),
            ),
            outputName = "Vybraný obrázek",
        ) { block ->
            val images = JsonTools.toList(text(block, "images"))
            if (images.isEmpty()) throw BlockException(tr("žádné obrázky k zobrazení"))
            val ui = ui ?: throw BlockException(tr("obrázky jde ukázat jen v okně"))
            val picked = ui.showImages(text(block, "title"), images, bool(block, "pick"))
                ?: throw RunCancelledException()
            BlockResult.Output(picked)
        },

        BlockDef(
            type = "show_web",
            needsWindow = true,
            summary = "Ukázat web {url}",
            title = "Ukázat web v okně",
            description = "Otevře webovou stránku v okně přímo v aplikaci a počká, až ji zavřeš. " +
                    "Výstupem je adresa, na které jsi skončil – hodí se třeba po přihlášení, které tě přesměruje. " +
                    "Skript se pustí v každé načtené stránce; zavolá-li done(hodnota), okno se zavře a výstupem je ta hodnota.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Language,
            params = listOf(
                ParamDef.Text("url", "Adresa stránky", defaultRef = VarRef.Input, placeholder = "https://…"),
                ParamDef.Text("title", "Nadpis", placeholder = "nepovinný").apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text(
                    "script", "JavaScript po načtení stránky", code = true,
                    placeholder = "if (location.pathname == '/hotovo') done(document.title)",
                ).apply { hiddenInSummaryWhen = { true } },
                HtmlBlocks.displayParam(),
            ),
            outputName = "Výsledek nebo adresa",
        ) { block ->
            var url = text(block, "url").trim()
            if (url.isEmpty()) throw BlockException(tr("chybí adresa stránky"))
            if (!url.contains("://")) url = "https://$url"
            if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
                throw BlockException(tr("v okně jde otevřít jen adresa http:// nebo https://"))
            }
            val ui = ui ?: throw BlockException(tr("web jde ukázat jen v okně"))
            BlockResult.Output(
                ui.showWeb(text(block, "title"), url, text(block, "script"), choice(block, "display") == HtmlBlocks.SHEET)
                    ?: throw RunCancelledException()
            )
        },
    )

    // ---------- HTTP ----------

    sealed interface RequestBody {
        class Bytes(val data: ByteArray, val contentType: String) : RequestBody
        class Multipart(val fields: List<Pair<String, String>>) : RequestBody
        class FileBody(val source: String) : RequestBody
    }

    class Response(val code: Int, val headers: Map<String, String>, val text: String, val file: String?)

    /**
     * What the block returns by default:
     * `{"status": 200, "ok": true, "headers": {"content-type": …}, "body": …, "file": "file://…"}`.
     * A JSON body is embedded as JSON (so `body.items[0].url` works), other bodies as text.
     * Header names are lower case; `file` is there only when the response was saved.
     */
    fun responseObject(response: Response): JsonObject = JsonObject(buildMap {
        put("status", JsonPrimitive(response.code))
        put("ok", JsonPrimitive(response.code in 200..299))
        put("headers", JsonObject(response.headers.entries.associate { (k, v) -> k.lowercase() to JsonPrimitive(v) }))
        put("body", JsonTools.parseOrNull(response.text) ?: JsonPrimitive(response.text))
        response.file?.let { put("file", JsonPrimitive(it)) }
    })

    suspend fun request(
        context: Context,
        method: String,
        address: String,
        headers: List<Pair<String, String>>,
        body: RequestBody?,
        saveToFile: Boolean = false,
    ): Response = kotlinx.coroutines.coroutineScope {
        val open = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>()
        val work = async(Dispatchers.IO) { requestBlocking(context, method, address, headers, body, saveToFile, open) }
        try {
            work.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Stopped (the Stop button, a time limit, another round ended the loop): do not wait for the server.
            open.get()?.disconnect()
            throw e
        }
    }

    private fun requestBlocking(
        context: Context,
        method: String,
        address: String,
        headers: List<Pair<String, String>>,
        body: RequestBody?,
        saveToFile: Boolean,
        open: java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>,
    ): Response {
        val url = try {
            URL(if (address.contains("://")) address else "https://$address")
        } catch (e: Exception) {
            throw BlockException(tr("neplatná adresa"))
        }
        if (isLocalHost(url.host) && !LocalNetwork.granted(context)) {
            throw BlockException(tr("Android nepustí aplikaci do místní sítě – povol přístup k místní síti"), Requirement.LocalNetwork)
        }
        val conn = url.openConnection() as HttpURLConnection
        open.set(conn)
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Automation (Android)")
            conn.setRequestProperty("Accept", "application/json, */*")
            for ((key, value) in headers) conn.setRequestProperty(key, value)

            when (body) {
                null -> Unit
                is RequestBody.Bytes -> {
                    if (headers.none { it.first.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", body.contentType)
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.data.size)
                    conn.outputStream.use { it.write(body.data) }
                }
                is RequestBody.FileBody -> {
                    val file = openSource(context, body.source)
                    if (headers.none { it.first.equals("Content-Type", true) }) conn.setRequestProperty("Content-Type", file.mime)
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(64 * 1024)
                    conn.outputStream.use { out -> file.open().use { it.copyTo(out) } }
                }
                is RequestBody.Multipart -> {
                    val boundary = "----Automation" + UUID.randomUUID().toString().replace("-", "")
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    conn.doOutput = true
                    conn.setChunkedStreamingMode(64 * 1024)
                    conn.outputStream.use { out -> writeMultipart(context, out, boundary, body.fields) }
                }
            }

            val code = conn.responseCode
            val responseHeaders = conn.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.joinToString(", ") }
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            if (saveToFile && code < 400) {
                val file = saveResponse(context, stream, conn.contentType, url)
                return Response(code, responseHeaders, "", file)
            }
            val bytes = stream?.use { readLimited(it) } ?: ByteArray(0)
            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(conn.contentType ?: "")?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            Response(code, responseHeaders, String(bytes, charset), null)
        } catch (e: BlockException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw BlockException(tr("bez připojení nebo neznámý server"))
        } catch (e: java.net.SocketTimeoutException) {
            throw BlockException(tr("server neodpovídá"))
        } catch (e: java.net.ProtocolException) {
            throw BlockException(tr("požadavek nejde odeslat (%s)", e.message))
        } catch (e: java.io.IOException) {
            if (e.message?.contains("CLEARTEXT", ignoreCase = true) == true) {
                throw BlockException(tr("nešifrované http:// Android blokuje – použij https://"))
            }
            throw BlockException(tr("požadavek selhal (%s)", e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }

    /** Private address ranges, *.local and *.lan – the home network. */
    fun isLocalHost(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return false
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4) {
            return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) ||
                    (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 169 && parts[1] == 254)
        }
        return h.startsWith("fd") || h.startsWith("fe80:")
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_RESPONSE) throw BlockException(tr("odpověď je moc velká (víc než 20 MB) – ulož ji jako soubor"))
        }
        return out.toByteArray()
    }

    private fun writeMultipart(context: Context, out: OutputStream, boundary: String, fields: List<Pair<String, String>>) {
        fun line(text: String = "") = out.write((text + "\r\n").toByteArray())
        for ((key, value) in fields) {
            line("--$boundary")
            if (value.startsWith("@") && value.length > 1) {
                val file = openSource(context, value.substring(1).trim())
                line("Content-Disposition: form-data; name=\"${quote(key)}\"; filename=\"${quote(file.name)}\"")
                line("Content-Type: ${file.mime}")
                line()
                file.open().use { it.copyTo(out) }
                line()
            } else {
                line("Content-Disposition: form-data; name=\"${quote(key)}\"")
                line("Content-Type: text/plain; charset=utf-8")
                line()
                out.write(value.toByteArray())
                line()
            }
        }
        line("--$boundary--")
    }

    private fun quote(text: String) = text.replace("\"", "%22").replace("\r", "").replace("\n", "")

    class Source(val name: String, val mime: String, val open: () -> InputStream)

    /** A file chosen with "Vybrat soubor" (content://), a saved download (file://) or a path. */
    fun openSource(context: Context, source: String): Source {
        if (source.isBlank()) throw BlockException(tr("soubor není zadaný"))
        if (EmbeddedImage.isEmbedded(source)) {
            // An image stored in the automation itself (data:image/png;base64,…).
            val (bytes, mime) = EmbeddedImage.decode(source)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
            return Source("obrazek.$extension", mime) { java.io.ByteArrayInputStream(bytes) }
        }
        val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
        return when (uri.scheme) {
            "content" -> {
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull() ?: uri.lastPathSegment ?: "soubor"
                val mime = resolver.getType(uri) ?: guessMime(name)
                Source(name, mime) {
                    try {
                        resolver.openInputStream(uri) ?: throw BlockException(tr("soubor nejde otevřít"))
                    } catch (e: SecurityException) {
                        throw BlockException(tr("k souboru už není přístup – vyber ho znovu ve stejném běhu akce"))
                    }
                }
            }
            "file" -> {
                val file = File(uri.path ?: "")
                if (!file.exists()) throw BlockException(tr("soubor %s neexistuje", file.name))
                Source(file.name, guessMime(file.name)) { file.inputStream() }
            }
            else -> throw BlockException(tr("\"%s\" není soubor (čekám content:// nebo file://)", source.take(60)))
        }
    }

    private fun guessMime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Saves a downloaded body into the app cache; returns its file:// address. */
    private fun saveResponse(context: Context, stream: InputStream?, contentType: String?, url: URL): String {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        // Keep the cache small: drop downloads older than a day.
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        val mime = contentType?.substringBefore(';')?.trim()
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: url.path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 1..5 }
            ?: "bin"
        val file = File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}.$extension")
        (stream ?: throw BlockException(tr("odpověď je prázdná"))).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file).toString()
    }
}
