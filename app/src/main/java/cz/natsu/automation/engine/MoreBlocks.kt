package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.content.ComponentName
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.text.Html
import android.util.Base64
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.Visibility
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.text.Normalizer
import kotlin.random.Random

/** Sharing, web, dialogs and other extra blocks. */
object MoreBlocks {
    private const val TRIGGER_ACTIVITY = "cz.natsu.automation.trigger.TriggerActivity"
    const val EVENT_VALUE = "event_value"

    private val urlRegex = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex("""[\w.+-]+@[\w-]+(\.[\w-]+)+""")
    private val phoneRegex = Regex("""\+?\d[\d \-]{7,}\d""")
    private val numberRegex = Regex("""-?\d+(?:[.,]\d+)?""")

    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    val all: List<BlockDef> = listOf(
        // ---------- Sharing & web ----------
        BlockDef(
            type = "share",
            needsWindow = true,
            summary = "Sdílet {text}",
            title = "Sdílet",
            description = "Otevře systémové okno Sdílet s textem – vybereš aplikaci, kam ho poslat.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Share,
            params = listOf(
                ParamDef.Text("text", "Text ke sdílení", defaultRef = VarRef.Input),
                ParamDef.Text("title", "Nadpis okna", default = "", placeholder = "nepovinné", plain = true),
            ),
        ) { block ->
            val text = text(block, "text")
            if (text.isEmpty()) throw BlockException(tr("není co sdílet – text je prázdný"))
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            val chooser = Intent.createChooser(send, text(block, "title").ifBlank { null })
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Don't offer this app itself – that would just start the automation again.
                .putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(ComponentName(context.packageName, TRIGGER_ACTIVITY))
                )
            withContext(Dispatchers.Main) { context.startActivity(chooser) }
            BlockResult.None
        },

        BlockDef(
            type = "web",
            needsWindow = true,
            summary = "Načíst {what} z {url}",
            title = "Načíst webovou stránku",
            description = "Stáhne stránku z internetu. Umí i rozbalit zkrácený odkaz (bit.ly, t.co…) na cílovou adresu.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Language,
            params = listOf(
                ParamDef.Text("url", "Adresa", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "what", "Co vrátit", listOf(
                        ChoiceOption("final_url", "Cílovou adresu (po přesměrování)", "cílovou adresu"),
                        ChoiceOption("title", "Název stránky", "název stránky"),
                        ChoiceOption("text", "Text stránky (bez HTML)", "text stránky"),
                        ChoiceOption("raw", "Zdrojový kód", "zdrojový kód"),
                    )
                ),
            ),
            outputName = "Web",
        ) { block ->
            var url = text(block, "url").trim()
            if (url.isEmpty()) throw BlockException(tr("adresa je prázdná"))
            if (!url.contains("://")) url = "https://$url"
            BlockResult.Output(fetch(url, choice(block, "what")))
        },

        // ---------- Dialogs ----------
        BlockDef(
            type = "ask_text",
            needsWindow = true,
            summary = "Zeptat se {title}",
            title = "Zeptat se na text",
            description = "Zobrazí okno s textovým polem. Zrušení dialogu akci ukončí.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.EditNote,
            params = listOf(
                ParamDef.Text("title", "Otázka", default = "Zadej text"),
                ParamDef.Text("initial", "Předvyplněno", placeholder = "nic"),
                ParamDef.Toggle("multiline", "Víc řádků").apply {
                    hiddenWhen = { values -> (values["hidden"] as? ParamValue.Bool)?.value == true }
                },
                ParamDef.Toggle("hidden", "Skrýt zadávání (heslo)", summaryWhenOn = "(heslo)"),
            ),
            outputName = "Zadaný text",
        ) { block ->
            val initial = text(block, "initial")
            val hidden = bool(block, "hidden")
            val ui = ui ?: return@BlockDef BlockResult.Output(initial)
            val answer = ui.askText(text(block, "title"), initial, bool(block, "multiline") && !hidden, hidden)
                ?: throw RunCancelledException()
            // A password stays out of the log.
            if (hidden) markSecret(answer)
            BlockResult.Output(answer)
        },

        BlockDef(
            type = "choose",
            needsWindow = true,
            summary = "Vybrat z {options}",
            title = "Vybrat z nabídky",
            description = "Nabídne možnosti (každá na samostatném řádku, nebo JSON pole) a vrátí tu vybranou.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Checklist,
            params = listOf(
                ParamDef.Text("title", "Nadpis", default = "Vyber"),
                ParamDef.Text("options", "Možnosti (jedna na řádek)", default = "Ano\nNe"),
                ParamDef.Text(
                    "label", "U seznamu objektů zobrazit",
                    placeholder = "cesta, např. title nebo user.name – vrátí se celý objekt",
                ),
            ),
            outputName = "Vybraná položka",
        ) { block ->
            val options = JsonTools.toList(text(block, "options"))
            if (options.isEmpty()) throw BlockException(tr("nabídka je prázdná"))
            val labelPath = text(block, "label").trim()
            // For a list of JSON objects show one value of each, but return the whole object.
            val labels = if (labelPath.isEmpty()) options else {
                val steps = JsonTools.parsePath(labelPath)
                options.mapIndexed { i, option ->
                    JsonTools.parseOrNull(option)?.let { JsonTools.select(it, steps) }?.let { JsonTools.asText(it) }
                        ?.takeIf { it.isNotBlank() } ?: "${i + 1}. ${option.take(60)}"
                }
            }
            val ui = ui ?: return@BlockDef BlockResult.Output(options.first())
            val picked = ui.choose(text(block, "title"), labels) ?: throw RunCancelledException()
            BlockResult.Output(options[labels.indexOf(picked).coerceAtLeast(0)])
        },

        BlockDef(
            type = "show_text",
            needsWindow = true,
            summary = "Ukázat {text}",
            title = "Ukázat text",
            description = "Zobrazí text v okně, odkud ho jde zkopírovat. Akce pokračuje po zavření.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Visibility,
            params = listOf(
                ParamDef.Text("title", "Nadpis", default = "Výsledek"),
                ParamDef.Text("text", "Text", defaultRef = VarRef.Input),
            ),
        ) { block ->
            val value = text(block, "text")
            val ui = ui
            if (ui == null) {
                withContext(Dispatchers.Main) { Toast.makeText(context, value, Toast.LENGTH_LONG).show() }
            } else if (!ui.showText(text(block, "title"), value)) {
                throw RunCancelledException()
            }
            BlockResult.None
        },

        // ---------- Text ----------
        BlockDef(
            type = "extract",
            summary = "Najít {which} {what} v {source}",
            title = "Najít v textu",
            description = "Vytáhne z textu odkazy, e-maily, telefonní čísla, čísla nebo shodu s regulárním výrazem.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.Search,
            params = listOf(
                ParamDef.Text("source", "V textu", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "what", "Co hledat", listOf(
                        ChoiceOption("url", "Odkazy", "odkaz"),
                        ChoiceOption("email", "E-maily", "e-mail"),
                        ChoiceOption("phone", "Telefonní čísla", "telefon"),
                        ChoiceOption("number", "Čísla", "číslo"),
                        ChoiceOption("regex", "Regulární výraz", "regex"),
                    )
                ),
                ParamDef.Text("pattern", "Regulární výraz", placeholder = "např. #\\w+", plain = true).apply {
                    hiddenWhen = { !choiceIs("what", "regex")(it) }
                },
                ParamDef.Choice(
                    "which", "Které", listOf(
                        ChoiceOption("first", "První nalezené", "první"),
                        ChoiceOption("all", "Všechna (každé na řádek)", "všechna"),
                        ChoiceOption("count", "Jen počet", "počet"),
                    )
                ),
            ),
            outputName = "Nalezeno",
        ) { block ->
            val source = text(block, "source")
            val regex = when (choice(block, "what")) {
                "email" -> emailRegex
                "phone" -> phoneRegex
                "number" -> numberRegex
                "regex" -> try {
                    Regex(text(block, "pattern"))
                } catch (e: Exception) {
                    throw BlockException(tr("neplatný regulární výraz"))
                }
                else -> urlRegex
            }
            var found = regex.findAll(source).map { it.value }.toList()
            if (choice(block, "what") == "url") found = found.map { it.trimEnd('.', ',', ')', '!', '?', ';', ':') }
            val result = when (choice(block, "which")) {
                "all" -> found.joinToString("\n")
                "count" -> found.size.toString()
                else -> found.firstOrNull() ?: ""
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "convert",
            summary = "{mode}: {source}",
            title = "Převést text",
            description = "Odstraní diakritiku, zakóduje / dekóduje URL nebo Base64, obrátí text.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.Transform,
            params = listOf(
                ParamDef.Text("source", "Text", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "mode", "Převod", listOf(
                        ChoiceOption("diacritics", "Odstranit diakritiku (č → c)", "Bez diakritiky"),
                        ChoiceOption("url_encode", "URL zakódovat", "URL zakódovat"),
                        ChoiceOption("url_decode", "URL dekódovat", "URL dekódovat"),
                        ChoiceOption("b64_encode", "Base64 zakódovat", "Base64 zakódovat"),
                        ChoiceOption("b64_decode", "Base64 dekódovat", "Base64 dekódovat"),
                        ChoiceOption("reverse", "Obrátit pořadí znaků", "Obrátit"),
                    )
                ),
            ),
            outputName = "Převedený text",
        ) { block ->
            val source = text(block, "source")
            val result = try {
                when (choice(block, "mode")) {
                    "url_encode" -> URLEncoder.encode(source, "UTF-8")
                    "url_decode" -> URLDecoder.decode(source, "UTF-8")
                    "b64_encode" -> Base64.encodeToString(source.toByteArray(), Base64.NO_WRAP)
                    "b64_decode" -> String(Base64.decode(source.trim(), Base64.DEFAULT))
                    "reverse" -> source.reversed()
                    else -> Normalizer.normalize(source, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
                }
            } catch (e: IllegalArgumentException) {
                throw BlockException(tr("text nejde převést (%s)", e.message))
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "random",
            summary = "Náhodné číslo od {min} do {max}",
            title = "Náhodné číslo",
            description = "Vrátí náhodné celé číslo v zadaném rozsahu (včetně krajů).",
            category = BlockCategory.Numbers,
            icon = Icons.Rounded.Casino,
            params = listOf(
                ParamDef.Text("min", "Od", default = "1"),
                ParamDef.Text("max", "Do", default = "100"),
            ),
            outputName = "Náhodné číslo",
        ) { block ->
            val min = parseNumber(text(block, "min"))?.toLong() ?: throw BlockException(tr("\"Od\" není číslo"))
            val max = parseNumber(text(block, "max"))?.toLong() ?: throw BlockException(tr("\"Do\" není číslo"))
            if (max < min) throw BlockException(tr("\"Do\" je menší než \"Od\""))
            BlockResult.Output(Random.nextLong(min, max + 1).toString())
        },

        // ---------- Device ----------
        BlockDef(
            type = EVENT_VALUE,
            summary = "Zjistit {value}",
            title = "Údaje z události",
            description = "Co přinesla událost, která automatizaci spustila: jméno Bluetooth zařízení, " +
                    "aplikace a text oznámení, odesílatel SMS, ID štítku… Mimo událost vrátí aktuální stav, kde to jde.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.Sensors,
            params = listOf(
                ParamDef.Choice(
                    "value", "Zjistit", listOf(
                        ChoiceOption("wifi_name", "Název Wi-Fi", "název Wi-Fi"),
                        ChoiceOption("bt_device", "Bluetooth zařízení", "Bluetooth zařízení"),
                        ChoiceOption("notif_app", "Oznámení – aplikace", "aplikaci oznámení"),
                        ChoiceOption("notif_title", "Oznámení – nadpis", "nadpis oznámení"),
                        ChoiceOption("notif_text", "Oznámení – text", "text oznámení"),
                        ChoiceOption("notif_package", "Oznámení – balíček aplikace", "balíček oznámení"),
                        ChoiceOption("dnd", "Režim Nerušit (zapnuto / vypnuto)", "stav Nerušit"),
                        ChoiceOption("sms_sender", "SMS – odesílatel", "odesílatele SMS"),
                        ChoiceOption("sms_text", "SMS – text", "text SMS"),
                        ChoiceOption("call_number", "Hovor – číslo", "číslo volajícího"),
                        ChoiceOption("nfc_id", "NFC štítek – ID", "ID štítku"),
                        ChoiceOption("nfc_text", "NFC štítek – text", "text štítku"),
                        ChoiceOption("app_package", "Aplikace – balíček", "balíček aplikace"),
                        ChoiceOption("app_name", "Aplikace – název", "název aplikace"),
                    )
                ),
            ),
            outputName = "Údaj události",
        ) { block ->
            BlockResult.Output(resolve(VarRef.Event(choice(block, "value"))))
        },

        BlockDef(
            type = "torch",
            summary = "Svítilna {mode}",
            title = "Svítilna",
            description = "Zapne nebo vypne svítilnu u fotoaparátu.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.FlashlightOn,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Stav", listOf(
                        ChoiceOption("on", "Zapnout", "zapnout"),
                        ChoiceOption("off", "Vypnout", "vypnout"),
                    )
                ),
            ),
        ) { block ->
            val camera = context.getSystemService(CameraManager::class.java)
                ?: throw BlockException(tr("fotoaparát není dostupný"))
            val id = camera.cameraIdList.firstOrNull {
                camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw BlockException(tr("zařízení nemá svítilnu"))
            try {
                camera.setTorchMode(id, choice(block, "mode") == "on")
            } catch (e: Exception) {
                throw BlockException(tr("svítilnu nejde ovládat (%s)", e.message))
            }
            BlockResult.None
        },
    )

    /** Downloads [startUrl], following redirects, and returns what [what] asks for. */
    /** Android blocks unencrypted HTTP, so plain http:// addresses are upgraded to https://. */
    private fun secure(url: URL): URL =
        if (url.protocol.equals("http", ignoreCase = true)) URL("https", url.host, url.port, url.file) else url

    private suspend fun fetch(startUrl: String, what: String): String = withContext(Dispatchers.IO) {
        var url = try {
            secure(URL(startUrl))
        } catch (e: Exception) {
            throw BlockException(tr("neplatná adresa"))
        }
        try {
            var connection: HttpURLConnection? = null
            for (hop in 0 until 10) {
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Automation")
                val code = conn.responseCode
                val location = conn.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    conn.disconnect()
                    url = secure(URL(url, location))
                    continue
                }
                if (code >= 400) {
                    conn.disconnect()
                    throw BlockException(tr("server vrátil chybu %s", code))
                }
                connection = conn
                break
            }
            val conn = connection ?: throw BlockException(tr("příliš mnoho přesměrování"))
            if (what == "final_url") {
                conn.disconnect()
                return@withContext url.toString()
            }

            val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(conn.contentType ?: "")?.groupValues?.get(1)
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
            val body = conn.inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (out.size() < 2_000_000) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                String(out.toByteArray(), charset)
            }
            conn.disconnect()

            when (what) {
                "title" -> Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(body)?.groupValues?.get(1)
                    ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: ""
                "text" -> {
                    val cleaned = body
                        .replace(Regex("<(script|style|noscript)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
                        .replace(Regex("[ \\t\\u00A0]+"), " ")
                        .replace(Regex("\\s*\\n\\s*\\n+"), "\n\n")
                        .trim()
                }
                else -> body
            }
        } catch (e: BlockException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw BlockException(tr("bez připojení nebo neznámý server"))
        } catch (e: java.net.SocketTimeoutException) {
            throw BlockException(tr("server neodpovídá"))
        } catch (e: Exception) {
            throw BlockException(tr("načtení selhalo (%s)", e.message ?: e.javaClass.simpleName))
        }
    }
}
