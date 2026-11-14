package cz.natsu.automation.engine

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.core.content.FileProvider
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.ValueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Stored values, loop position, calculations and files. One block = one thing. */
object ExtraBlocks {
    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    private val scopeParam
        get() = ParamDef.Choice(
            "scope", "Platnost", listOf(
                ChoiceOption("own", "Jen pro tuto automatizaci", "vlastní"),
                ChoiceOption("shared", "Sdílená se všemi automatizacemi", "sdílená"),
            )
        ).apply { hiddenInSummaryWhen = { (it["scope"] as? ParamValue.Choice)?.value != "shared" } }

    /** Owner of a stored value: this automation, or null for a shared one. */
    private fun RunContext.owner(block: cz.natsu.automation.data.Block): Long? =
        if (choice(block, "scope") == "shared") null else (automationId ?: 0L)

    private const val FILE_HINT = "výstup bloku Vybrat soubor, Upravit obrázek, stažený soubor nebo https:// adresa"

    val all: List<BlockDef> = listOf(
        // ---------- Stored values ----------
        BlockDef(
            type = "store_set",
            summary = "{mode} {key} {scope}",
            title = "Uložit hodnotu",
            description = "Zapamatuje si hodnotu i po skončení akce (třeba poslední ID z API), aby ji příští " +
                    "spuštění mohlo porovnat. Patří jen této automatizaci, pokud ji nenastavíš jako sdílenou. " +
                    "Hodnoty jsou jen v telefonu, v exportu nejsou.",
            category = BlockCategory.Variables,
            icon = Icons.Rounded.SaveAlt,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Co udělat", listOf(
                        ChoiceOption("set", "Uložit", "Uložit pod"),
                        ChoiceOption("remove", "Smazat", "Smazat"),
                    )
                ),
                ParamDef.Text("key", "Název", placeholder = "např. posledni_id"),
                ParamDef.Text("value", "Hodnota").apply { hiddenWhen = choiceIs("mode", "remove") },
                scopeParam,
            ),
        ) { block ->
            val key = text(block, "key").trim()
            if (key.isEmpty()) throw BlockException("název je prázdný")
            if (choice(block, "mode") == "remove") ValueStore.remove(context, owner(block), key)
            else ValueStore.set(context, owner(block), key, text(block, "value"))
            BlockResult.None
        },

        BlockDef(
            type = "store_get",
            summary = "Načíst {key} {scope}",
            title = "Načíst hodnotu",
            description = "Vrátí hodnotu uloženou blokem Uložit hodnotu (i z dřívějšího spuštění) – " +
                    "této automatizace, nebo sdílenou.",
            category = BlockCategory.Variables,
            icon = Icons.Rounded.Inventory2,
            params = listOf(
                ParamDef.Text("key", "Název", placeholder = "např. posledni_id"),
                ParamDef.Text("fallback", "Když nic uložené není", placeholder = "prázdný text"),
                scopeParam,
            ),
            outputName = "Uložená hodnota",
        ) { block ->
            val key = text(block, "key").trim()
            if (key.isEmpty()) throw BlockException("název je prázdný")
            BlockResult.Output(ValueStore.get(context, owner(block), key) ?: text(block, "fallback"))
        },

        // ---------- Loops ----------
        BlockDef(
            type = "loop_index",
            summary = "Pořadí v opakování{zero}",
            title = "Pořadí v opakování",
            description = "Kolikáté kolo nejbližšího opakování (Pro každou položku, Opakovat, Opakuj dokud) právě běží.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.FormatListNumbered,
            params = listOf(ParamDef.Toggle("zero", "Počítat od 0", summaryWhenOn = " (od 0)")),
            outputName = "Pořadí",
        ) { block ->
            val loop = currentLoop ?: throw BlockException("krok není uvnitř opakování")
            val round = loopRound[loop] ?: 1
            BlockResult.Output((if (bool(block, "zero")) round - 1 else round).toString())
        },

        // ---------- Numbers ----------
        BlockDef(
            type = "calculate",
            summary = "Vypočítat {expression}",
            title = "Vypočítat",
            description = "Spočítá výraz: + − × / % ^, závorky, desetinná čárka i tečka, funkce round(x; míst), " +
                    "floor, ceil, abs, sqrt, min, max. Čísla vložíš z proměnných.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Calculate,
            params = listOf(
                ParamDef.Text("expression", "Výraz", placeholder = "např. round(cena * 1,21; 2)"),
                ParamDef.Text("places", "Zaokrouhlit na míst", default = "", placeholder = "nezaokrouhlovat"),
            ),
            outputName = "Výsledek výpočtu",
        ) { block ->
            var value = Calc.evaluate(text(block, "expression"))
            text(block, "places").trim().takeIf { it.isNotEmpty() }?.let {
                val places = parseNumber(it)?.toInt() ?: throw BlockException("\"Zaokrouhlit na míst\" není číslo")
                value = Calc.evaluate("round($value; $places)")
            }
            BlockResult.Output(formatNumber(value))
        },

        // ---------- Files ----------
        BlockDef(
            type = "share_file",
            summary = "Sdílet soubor {file}",
            title = "Sdílet soubor",
            description = "Otevře okno Sdílet se souborem nebo obrázkem – třeba upraveným nebo staženým.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Share,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = FILE_HINT),
                ParamDef.Text("text", "Text ke sdílení", placeholder = "nepovinné"),
            ),
        ) { block ->
            val copy = FileTools.copyToCache(context, text(block, "file"), "shared")
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", copy.file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(copy.mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            text(block, "text").takeIf { it.isNotBlank() }?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
            val chooser = Intent.createChooser(send, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) { context.startActivity(chooser) }
            BlockResult.None
        },

        BlockDef(
            type = "save_file",
            summary = "Uložit {file} do {where}",
            title = "Uložit soubor",
            description = "Uloží soubor do Galerie (obrázky, složka Automation) nebo do Stažených. Vrátí jeho adresu.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Download,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = FILE_HINT),
                ParamDef.Choice(
                    "where", "Kam", listOf(
                        ChoiceOption("auto", "Obrázky do Galerie, ostatní do Stažených", "Galerie / Stažené"),
                        ChoiceOption("gallery", "Galerie (Obrázky)", "Galerie"),
                        ChoiceOption("downloads", "Stažené soubory", "Stažené"),
                    )
                ),
                ParamDef.Text("name", "Název souboru", placeholder = "nechat původní"),
            ),
            outputName = "Uložený soubor",
        ) { block ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                throw BlockException("ukládání do Galerie / Stažených potřebuje Android 10 nebo novější")
            }
            val source = HttpBlocks.openSource(context, FileTools.local(context, text(block, "file")))
            val name = text(block, "name").trim().ifEmpty { source.name }
            val image = source.mime.startsWith("image/")
            val toGallery = when (choice(block, "where")) {
                "gallery" -> true
                "downloads" -> false
                else -> image
            }
            val result = withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, source.mime)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        (if (toGallery) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Automation"
                    )
                }
                val collection = if (toGallery) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val resolver = context.contentResolver
                val uri = resolver.insert(collection, values) ?: throw BlockException("soubor nejde uložit")
                try {
                    resolver.openOutputStream(uri)?.use { out -> source.open().use { it.copyTo(out) } }
                        ?: throw BlockException("soubor nejde uložit")
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw if (e is BlockException) e else BlockException("soubor nejde uložit (${e.message})")
                }
                uri.toString()
            }
            BlockResult.Output(result)
        },
    )
}

/** Helpers for blocks that take a file. */
object FileTools {
    class Copy(val file: File, val mime: String)

    /** A local address for [source]; an https:// address is downloaded into the cache first. */
    suspend fun local(context: android.content.Context, source: String): String {
        val trimmed = source.trim().removePrefix("@")
        if (trimmed.isEmpty()) throw BlockException("soubor není zadaný")
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            val response = HttpBlocks.request(context, "GET", trimmed, emptyList(), null, saveToFile = true)
            if (response.code >= 400) throw BlockException("soubor nejde stáhnout (chyba ${response.code})")
            return response.file ?: throw BlockException("soubor nejde stáhnout")
        }
        return trimmed
    }

    /** Copies [source] into cache/[folder] (files from other apps can't be passed on directly). */
    suspend fun copyToCache(context: android.content.Context, source: String, folder: String): Copy =
        withContext(Dispatchers.IO) {
            val input = HttpBlocks.openSource(context, local(context, source))
            val dir = File(context.cacheDir, folder).apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
            val safeName = input.name.replace(Regex("[^\\w.\\-]+"), "_").take(60).ifBlank { "soubor" }
            val file = File(File(dir, UUID.randomUUID().toString().take(8)).apply { mkdirs() }, safeName)
            input.open().use { inp -> file.outputStream().use { inp.copyTo(it) } }
            Copy(file, input.mime)
        }
}
