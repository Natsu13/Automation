package cz.natsu.automation.engine

import android.content.Context
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PostAdd
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileInputStream

/** What a new file made by "Soubor pro zápis" can be. */
enum class FileFormat(val key: String, val label: String, val mime: String, val extension: String) {
    Csv("csv", "CSV (tabulka)", "text/csv", "csv"),
    Json("json", "JSON", "application/json", "json"),
    Text("txt", "Text", "text/plain", "txt");

    companion object {
        fun of(key: String): FileFormat? = entries.firstOrNull { it.key == key }
    }
}

/** The pure part of "Přidat záznam do souboru": rows of a CSV and items of a JSON list. */
object Records {
    private val pretty = Json { prettyPrint = true }

    /**
     * One line of the "Hodnoty" field as name and value. [pieces] are its parts in order; only a
     * typed part (true) can hold the "=", so a "=" inside a variable's value stays in the value.
     */
    fun pair(pieces: List<Pair<String, Boolean>>, index: Int): Pair<String, String>? {
        if (pieces.all { it.first.isBlank() }) return null
        val at = pieces.indexOfFirst { (text, typed) -> typed && '=' in text }
        if (at < 0) return "hodnota${index + 1}" to pieces.joinToString("") { it.first }.trim()
        val (before, after) = pieces[at].first.split("=", limit = 2)
        val key = (pieces.take(at).joinToString("") { it.first } + before).trim()
        val value = (after + pieces.drop(at + 1).joinToString("") { it.first }).trim()
        return key.ifEmpty { "hodnota${index + 1}" } to value
    }

    fun csvRow(values: List<String>, separator: Char): String = values.joinToString(separator.toString()) { value ->
        if (value.any { it == separator || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    } + "\r\n"

    /** [existing] (a JSON list, or nothing yet) with [record] added as its last item. */
    fun addToJson(existing: String, record: List<Pair<String, String>>): String {
        val text = existing.removePrefix("﻿")
        val list: List<JsonElement> = if (text.isBlank()) emptyList() else {
            JsonTools.parseOrNull(text) as? JsonArray ?: throw BlockException(tr("soubor není seznam JSON [ … ]"))
        }
        val item = JsonObject(record.associate { (key, value) -> key to JsonPrimitive(value) })
        return pretty.encodeToString(JsonArray.serializer(), JsonArray(list + item)) + "\n"
    }
}

/** Writing into a file the user chose (content://) or one of the app's own (file://, a path). */
object FileWrite {
    /** Larger files are not read back (needed for JSON and for providers that cannot append). */
    private const val MAX_REWRITE = 8 * 1024 * 1024

    /** Two runs must not write the same file at once (two notifications in one second). */
    private val lock = Mutex()

    class Target(
        val name: String,
        val mime: String,
        /** Size in bytes and the last byte (null when empty or unknown). */
        val tail: () -> Pair<Long, Byte?>,
        val read: () -> ByteArray,
        val write: (data: ByteArray, append: Boolean) -> Unit,
    ) {
        val isJson: Boolean get() = name.endsWith(".json", ignoreCase = true) || "json" in mime

        /** "csv", "json"…: from the name, or from the kind of the file when the name has none. */
        val extension: String
            get() = name.substringAfterLast('.', "").lowercase().ifEmpty {
                when {
                    "json" in mime -> "json"
                    "csv" in mime || "comma-separated" in mime -> "csv"
                    mime == "text/plain" -> "txt"
                    else -> ""
                }
            }
    }

    suspend fun <T> on(context: Context, source: String, action: (Target) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                action(target(context, source.trim().removePrefix("@")))
            } catch (e: BlockException) {
                throw e
            } catch (e: SecurityException) {
                throw BlockException(tr("k souboru už není přístup – vyber ho znovu"))
            } catch (e: Exception) {
                throw BlockException(tr("do souboru nejde zapsat (%s)", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun tooBig(): Nothing = throw BlockException(tr("soubor je moc velký (nejvýš %s MB)", MAX_REWRITE / (1024 * 1024)))

    private fun target(context: Context, source: String): Target {
        if (source.isEmpty()) throw BlockException(tr("soubor není zadaný"))
        val uri = if (source.startsWith("/")) Uri.fromFile(File(source)) else Uri.parse(source)
        return when (uri.scheme) {
            "content" -> {
                val resolver = context.contentResolver
                val info = HttpBlocks.openSource(context, source)
                fun read(): ByteArray = resolver.openInputStream(uri)?.use { input ->
                    val data = input.readBytes()
                    if (data.size > MAX_REWRITE) tooBig()
                    data
                } ?: ByteArray(0)
                Target(
                    name = info.name,
                    mime = info.mime,
                    tail = {
                        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                            val size = descriptor.statSize
                            if (size <= 0) size.coerceAtLeast(0) to null
                            else FileInputStream(descriptor.fileDescriptor).use { stream ->
                                val one = java.nio.ByteBuffer.allocate(1)
                                size to if (stream.channel.read(one, size - 1) == 1) one.get(0) else null
                            }
                        } ?: (0L to null)
                    },
                    read = ::read,
                    write = { data, append ->
                        fun whole(bytes: ByteArray) {
                            val out = try {
                                resolver.openOutputStream(uri, "wt")
                            } catch (e: Exception) {
                                if (e is SecurityException) throw e
                                resolver.openOutputStream(uri, "w")
                            } ?: throw BlockException(tr("soubor nejde otevřít"))
                            out.use { it.write(bytes) }
                        }
                        if (!append) whole(data) else {
                            // Not every place for files can add to the end; then the file is written again whole.
                            val out = try {
                                resolver.openOutputStream(uri, "wa")
                            } catch (e: Exception) {
                                if (e is SecurityException) throw e
                                null
                            }
                            if (out != null) out.use { it.write(data) } else whole(read() + data)
                        }
                    },
                )
            }
            "file" -> {
                val file = File(uri.path ?: "")
                Target(
                    name = file.name,
                    mime = "",
                    tail = {
                        val size = file.length()
                        size to if (size > 0) java.io.RandomAccessFile(file, "r").use { it.seek(size - 1); it.readByte() } else null
                    },
                    read = {
                        if (file.length() > MAX_REWRITE) tooBig()
                        if (file.exists()) file.readBytes() else ByteArray(0)
                    },
                    write = { data, append ->
                        file.parentFile?.mkdirs()
                        if (append) file.appendBytes(data) else file.writeBytes(data)
                    },
                )
            }
            else -> throw BlockException(tr("\"%s\" není soubor (čekám content:// nebo file://)", source.take(60)))
        }
    }
}

/** Writing into files. One block = one thing: choose the file, write a text, add a record. */
object FileBlocks {
    const val PICK = "pick_save_file"
    private const val FILE_HINT = "výstup bloku Soubor pro zápis"

    /** The lines of "Hodnoty" as names and values; a variable never breaks a line or a name. */
    private suspend fun RunContext.record(block: Block): List<Pair<String, String>> {
        val segments = (block.params["values"] as? ParamValue.Rich)?.segments.orEmpty()
        val lines = mutableListOf(mutableListOf<Pair<String, Boolean>>())
        for (segment in segments) when (segment) {
            is Segment.Text -> segment.text.split('\n').forEachIndexed { index, part ->
                if (index > 0) lines.add(mutableListOf())
                if (part.isNotEmpty()) lines.last().add(part.trimEnd('\r') to true)
            }
            is Segment.Var -> lines.last().add(resolve(segment.ref) to false)
        }
        return lines.mapIndexedNotNull { index, pieces -> Records.pair(pieces, index) }
    }

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = PICK,
            // No "needsWindow": an automation asks once, keeps the address with "Uložit hodnotu" and then
            // runs in the background, where a window opened for every run would only get in the way.
            summary = "Soubor pro zápis {format}",
            title = "Soubor pro zápis",
            description = "Zeptá se, kam zapisovat: vytvořit nový soubor (CSV, JSON nebo text), nebo vybrat existující. " +
                    "Formát nového souboru jde určit předem (csv, json, txt – i proměnnou), jinak se na něj zeptá. " +
                    "Do vybraného souboru smí aplikace zapisovat i příště – ulož si výstup blokem Uložit hodnotu, " +
                    "příště ho vezmi blokem Načíst hodnotu a ptát se už nemusíš. " +
                    "Výstup patří do bloků Zapsat do souboru a Přidat záznam do souboru.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Description,
            params = listOf(
                ParamDef.Text(
                    "format", "Formát nového souboru", placeholder = "zeptat se",
                    suggestions = FileFormat.entries.map { it.key },
                ),
                ParamDef.Text("name", "Název nového souboru", default = "zaznam").apply { hiddenInSummaryWhen = { true } },
            ),
            outputName = "Soubor pro zápis",
        ) { block ->
            val ui = ui ?: throw BlockException(tr("výběr souboru potřebuje okno (spusť akci ručně)"))
            val name = text(block, "name").trim().replace(Regex("[\\\\/:*?\"<>|]+"), "_").ifEmpty { "zaznam" }
            val format = text(block, "format").trim().lowercase().removePrefix(".")
            if (format.isNotEmpty() && FileFormat.of(format) == null) {
                throw BlockException(tr("neznámý formát „%s“ (csv, json nebo txt)", format))
            }
            BlockResult.Output(ui.pickWritable(format, name) ?: throw RunCancelledException())
        },

        BlockDef(
            type = "write_file",
            summary = "{mode}: {content} do {file}",
            title = "Zapsat do souboru",
            description = "Zapíše text do souboru: připíše ho jako nový řádek, připíše na konec, nebo jím soubor přepíše. " +
                    "Soubor dodá blok Soubor pro zápis.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.EditNote,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = FILE_HINT),
                ParamDef.Text("content", "Text"),
                ParamDef.Choice(
                    "mode", "Jak", listOf(
                        ChoiceOption("line", "Připsat jako nový řádek", "Nový řádek"),
                        ChoiceOption("append", "Připsat na konec", "Na konec"),
                        ChoiceOption("overwrite", "Přepsat celý soubor", "Přepsat"),
                    )
                ),
            ),
        ) { block ->
            val content = text(block, "content")
            val mode = choice(block, "mode")
            FileWrite.on(context, text(block, "file")) { target ->
                when (mode) {
                    "overwrite" -> target.write(content.toByteArray(), false)
                    "append" -> target.write(content.toByteArray(), true)
                    else -> {
                        // A file that does not end with a new line gets one first, so the lines never join.
                        val last = target.tail().second
                        val start = if (last != null && last != '\n'.code.toByte()) "\n" else ""
                        target.write((start + content + "\n").toByteArray(), true)
                    }
                }
            }
            BlockResult.None
        },

        BlockDef(
            type = "add_record",
            summary = "Přidat záznam do {file}",
            title = "Přidat záznam do souboru",
            description = "Přidá do souboru jeden záznam z hodnot název=hodnota (jedna na řádek). Do CSV připíše řádek " +
                    "a v prázdném souboru nejdřív udělá hlavičku z názvů; do souboru .json přidá objekt na konec seznamu. " +
                    "Formát pozná podle přípony souboru, pokud ho nezadáš (csv nebo json – i proměnnou).",
            category = BlockCategory.Share,
            icon = Icons.Rounded.PostAdd,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = FILE_HINT),
                ParamDef.Text("values", "Hodnoty", placeholder = "cas=…\ntext=…").apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text(
                    "format", "Formát", placeholder = "podle přípony souboru", suggestions = listOf("csv", "json"),
                ).apply { hiddenInSummaryWhen = { true } },
                ParamDef.Choice(
                    "separator", "Oddělovač v CSV", listOf(
                        ChoiceOption(";", "Středník ; (český Excel)", ";"),
                        ChoiceOption(",", "Čárka ,", ","),
                        ChoiceOption("\t", "Tabulátor", "tab"),
                    )
                ).apply { hiddenInSummaryWhen = { true } },
            ),
        ) { block ->
            val record = record(block)
            if (record.isEmpty()) throw BlockException(tr("záznam nemá žádné hodnoty"))
            val separator = choice(block, "separator").firstOrNull() ?: ';'
            val json = when (val format = text(block, "format").trim().lowercase().removePrefix(".")) {
                "" -> null
                "json" -> true
                "csv" -> false
                else -> throw BlockException(tr("neznámý formát „%s“ (csv nebo json)", format))
            }
            FileWrite.on(context, text(block, "file")) { target ->
                if (json ?: target.isJson) {
                    target.write(Records.addToJson(String(target.read(), Charsets.UTF_8), record).toByteArray(), false)
                } else {
                    val (size, last) = target.tail()
                    val text = buildString {
                        // The mark at the start tells Excel the file is UTF-8.
                        if (size == 0L) append('﻿').append(Records.csvRow(record.map { it.first }, separator))
                        else if (last != null && last != '\n'.code.toByte()) append("\r\n")
                        append(Records.csvRow(record.map { it.second }, separator))
                    }
                    target.write(text.toByteArray(), true)
                }
            }
            BlockResult.None
        },

        BlockDef(
            type = "file_info",
            summary = "{what} souboru {file}",
            title = "Údaj o souboru",
            description = "Vrátí o souboru jednu věc: příponu (csv, json, txt… – hodí se do pole Formát nebo do podmínky), " +
                    "název, nebo velikost v bajtech.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Info,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = FILE_HINT),
                ParamDef.Choice(
                    "what", "Co", listOf(
                        ChoiceOption("extension", "Přípona (formát)", "Přípona"),
                        ChoiceOption("name", "Název", "Název"),
                        ChoiceOption("size", "Velikost v bajtech", "Velikost"),
                    )
                ),
            ),
            outputName = "Údaj o souboru",
        ) { block ->
            val what = choice(block, "what")
            BlockResult.Output(FileWrite.on(context, text(block, "file")) { target ->
                when (what) {
                    "name" -> target.name
                    "size" -> target.tail().first.toString()
                    else -> target.extension
                }
            })
        },
    )
}
