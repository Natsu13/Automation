package cz.natsu.automation.data

import cz.natsu.automation.i18n.tr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One automation in an export file – everything except the local database id. */
@Serializable
data class ExportedAutomation(
    val name: String,
    val icon: String = AutomationIcons.DEFAULT,
    val triggerProcessText: Boolean = false,
    val triggerShare: Boolean = false,
    val triggerTile: Boolean = false,
    val triggerClipboard: Boolean = false,
    val conditions: List<Block> = emptyList(),
    val conditionsAny: Boolean = false,
    val events: List<EventTrigger> = emptyList(),
    val blocks: List<Block> = emptyList(),
)

@Serializable
data class ExportFile(
    val format: String = AutomationTransfer.FORMAT,
    val version: Int = 1,
    val automations: List<ExportedAutomation>,
)

class ImportException(message: String) : Exception(message)

/** Export to / import from a JSON file that can be shared between devices. */
object AutomationTransfer {
    const val FORMAT = "cz.natsu.automation"
    const val MIME = "application/json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(automations: List<AutomationEntity>): String = json.encodeToString(
        ExportFile.serializer(),
        ExportFile(automations = automations.map {
            ExportedAutomation(
                name = it.name,
                icon = it.icon,
                triggerProcessText = it.triggerProcessText,
                triggerShare = it.triggerShare,
                triggerTile = it.triggerTile,
                triggerClipboard = it.triggerClipboard,
                conditions = it.conditions,
                conditionsAny = it.conditionsAny,
                events = it.events,
                blocks = it.blocks,
            )
        })
    )

    /** How an import went: [missingSecrets] = secrets the automations use that this phone does not have. */
    class Imported(val count: Int, val missingSecrets: List<String>)

    /** What to tell the user after an import. */
    fun importedMessage(imported: Imported): String =
        (if (imported.count == 1) tr("Automatizace importována") else tr("Importováno automatizací: %s", imported.count)) +
                tr(" – najdeš ji v seznamu. Než půjde spustit, otevři ji a povol.") +
                if (imported.missingSecrets.isEmpty()) "" else "\n" + tr(
                    "Chybí tajné hodnoty: %s – doplníš je při povolení, nebo v Nastavení → Tajné hodnoty.",
                    imported.missingSecrets.joinToString(", ")
                )

    /** Stores [automations] as new ones and checks which of their secrets this phone lacks. */
    suspend fun importAll(context: Context, repository: AutomationRepository, automations: List<ExportedAutomation>): Imported {
        if (automations.isEmpty()) throw ImportException(tr("V souboru nejsou žádné automatizace."))
        val count = repository.importAll(automations)
        val missing = withContext(Dispatchers.IO) {
            SecretStore.missingIn(context, automations.flatMap { it.blocks + it.conditions })
        }
        return Imported(count, missing)
    }

    /**
     * Accepts a full export file, a single exported automation, or an automation written as a
     * script ("Show as script", or one an AI model wrote from the guide on the web editor's site).
     */
    fun decode(text: String): List<ExportedAutomation> {
        val trimmed = text.trim().removePrefix("﻿")
        if (cz.natsu.automation.engine.ScriptReader.looksLikeScript(trimmed)) {
            val automation = cz.natsu.automation.engine.ScriptReader.read(trimmed, tr("Importovaná automatizace"))
            if (automation.blocks.isEmpty()) throw ImportException(tr("Ve skriptu nejsou žádné kroky."))
            return listOf(automation)
        }
        val file = runCatching { json.decodeFromString(ExportFile.serializer(), trimmed) }.getOrNull()
        if (file != null) {
            if (file.format != FORMAT) throw ImportException(tr("Soubor není export z Automatizací."))
            return file.automations
        }
        val single = runCatching { json.decodeFromString(ExportedAutomation.serializer(), trimmed) }.getOrNull()
            ?: throw ImportException(tr("Soubor se nepodařilo přečíst – není to export automatizací."))
        return listOf(single)
    }

    /** Writes the export to the cache and opens the share sheet with it. */
    suspend fun share(context: Context, automations: List<AutomationEntity>) {
        if (automations.isEmpty()) return
        val fileName = if (automations.size == 1) {
            safeFileName(automations.first().name) + ".automation.json"
        } else {
            "automatizace.json"
        }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, fileName).apply { writeText(encode(automations)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val title = if (automations.size == 1) automations.first().name else "Automatizace (${automations.size})"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            clipData = ClipData.newRawUri(title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Sdílet automatizaci").apply {
            // Sharing an export back into this app would only duplicate it.
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(android.content.ComponentName(context, cz.natsu.automation.MainActivity::class.java))
            )
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    /** Reads an export file and stores its automations as new ones. Returns how many were added. */
    suspend fun import(context: Context, repository: AutomationRepository, uri: Uri): Imported {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                // An export is small; refuse anything that clearly isn't one.
                val bytes = stream.readNBytesCompat(5 * 1024 * 1024)
                    ?: throw ImportException(tr("Soubor je příliš velký."))
                bytes.toString(Charsets.UTF_8)
            } ?: throw ImportException(tr("Soubor se nepodařilo otevřít."))
        }
        return importAll(context, repository, decode(text))
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    private fun safeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(60).ifBlank { "automatizace" }
}
