package cz.natsu.automation.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.RunHistory
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.data.RunRecord
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.ValueStore

/** Czech / English / by the phone. The change shows at once, without a restart. */
@Composable
fun LanguageSection() {
    val context = LocalContext.current
    Text("Language / Jazyk", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            for (language in cz.natsu.automation.i18n.Language.entries) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { cz.natsu.automation.i18n.I18n.set(context, language) }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(
                        selected = cz.natsu.automation.i18n.I18n.language == language,
                        onClick = { cz.natsu.automation.i18n.I18n.set(context, language) },
                    )
                    Text(language.title)
                }
            }
        }
    }
}

/** The last runs started by triggers and events, with the error of the failed ones. */
@Composable
fun RunHistorySection(resumeCounter: Int) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val records = remember(version, resumeCounter) { RunHistory.all(context) }
    var showAll by remember { mutableStateOf(false) }

    Text(tr("Historie běhů"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) {
                Text(
                    tr("Zatím nic. Objeví se tu spuštění událostí, dlaždicí, sdílením nebo ze schránky – ") +
                            tr("i ta na pozadí, u kterých by ses o chybě jinak nedozvěděl."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (record in if (showAll) records else records.take(8)) RunRecordRow(record, record.name)
            if (records.isNotEmpty()) Row {
                if (records.size > 8) TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) tr("Zobrazit méně") else tr("Zobrazit vše (%s)", records.size))
                }
                TextButton(onClick = { RunHistory.clear(context); version++ }) { Text(tr("Vymazat")) }
            }
        }
    }
}

/** One run: [title] with the outcome, when and how it started, and the error of a failed one. */
@Composable
private fun RunRecordRow(record: RunRecord, title: String) {
    val timeFormat = remember { java.text.SimpleDateFormat("d. M. H:mm:ss", java.util.Locale.getDefault()) }
    val failed = record.outcome == RunRecord.ERROR
    Column {
        Text(
            title + when (record.outcome) {
                RunRecord.ERROR -> " – " + tr("chyba")
                RunRecord.CANCELLED -> " – " + tr("zrušeno")
                else -> ""
            },
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${timeFormat.format(java.util.Date(record.time))} · ${tr(record.trigger)} · " +
                    "${tr("kroků")}: ${record.steps} · ${formatDuration(record.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        record.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The last runs of one automation, for its settings. */
@Composable
fun AutomationRunHistory(automationId: Long) {
    val context = LocalContext.current
    val records = remember { RunHistory.all(context).filter { it.automationId == automationId } }
    var showAll by remember { mutableStateOf(false) }
    Text(tr("Historie běhů"), style = MaterialTheme.typography.titleMedium)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (records.isEmpty()) {
            Text(
                tr("Zatím žádné spuštění mimo editor (událostí, dlaždicí, sdílením, zástupcem…)."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for (record in if (showAll) records else records.take(5)) {
            RunRecordRow(record, when (record.outcome) {
                RunRecord.OK -> tr("Proběhlo")
                else -> tr("Spuštění")
            })
        }
        if (records.size > 5) TextButton(onClick = { showAll = !showAll }) {
            Text(if (showAll) tr("Zobrazit méně") else tr("Zobrazit vše") + " (${records.size})")
        }
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1000) "$ms ms" else String.format(java.util.Locale.getDefault(), "%.1f s", ms / 1000.0)

/** API keys and tokens, stored encrypted; automations insert them by name. */
@Composable
fun SecretsSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val names = remember(version) { SecretStore.names(context) }
    var editing by remember { mutableStateOf<String?>(null) }   // "" = new
    var deleting by remember { mutableStateOf<String?>(null) }

    Text(tr("Tajné hodnoty"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("API klíče, tokeny a hesla. Jsou zašifrované klíčem v Android Keystore, do kroků se vkládají ") +
                        tr("jako proměnná 🔑 a do exportu automatizace se nikdy nedostanou. V záznamu běhu jsou skryté."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (name in names) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Text(name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { editing = name }) { Icon(Icons.Rounded.Edit, tr("Změnit")) }
                    IconButton(onClick = { deleting = name }) { Icon(Icons.Rounded.Delete, tr("Smazat")) }
                }
            }
            TextButton(onClick = { editing = "" }) {
                Icon(Icons.Rounded.Add, null)
                Text(tr("Přidat tajnou hodnotu"), Modifier.padding(start = 8.dp))
            }
        }
    }

    editing?.let { current ->
        SecretDialog(
            existingName = current.ifEmpty { null },
            taken = names.toSet(),
            onDismiss = { editing = null },
            onSave = { name, value ->
                if (current.isNotEmpty() && current != name) SecretStore.remove(context, current)
                SecretStore.set(context, name, value)
                editing = null
                version++
            },
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("Smazat \"%s\"?", name)) },
            text = { Text(tr("Automatizace, které ji používají, skončí chybou, dokud ji znovu nepřidáš.")) },
            confirmButton = {
                TextButton(onClick = {
                    SecretStore.remove(context, name)
                    deleting = null
                    version++
                }) { Text(tr("Smazat")) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Zrušit")) } },
        )
    }
}

/**
 * Asks for the value of a secret an automation uses but this phone does not have (an imported
 * automation). The name is the one the steps use, so it cannot be changed here.
 */
@Composable
fun AddMissingSecretDialog(name: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    SecretDialog(
        existingName = name,
        taken = emptySet(),
        onDismiss = onDismiss,
        onSave = { n, value -> SecretStore.set(context, n, value); onSaved() },
        title = tr("Doplnit tajnou hodnotu"),
        nameLocked = true,
    )
}

@Composable
private fun SecretDialog(
    existingName: String?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
    title: String? = null,
    nameLocked: Boolean = false,
) {
    var name by remember { mutableStateOf(existingName ?: "") }
    // The stored value is never shown again; changing a secret means typing it anew.
    var value by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> null
        trimmed != existingName && trimmed in taken -> tr("Tenhle název už existuje")
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: if (existingName == null) tr("Nová tajná hodnota") else tr("Změnit tajnou hodnotu")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(tr("Název")) },
                    placeholder = { Text(tr("např. github_token")) },
                    singleLine = true,
                    enabled = !nameLocked,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (existingName == null || nameLocked) tr("Hodnota") else tr("Nová hodnota")) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && nameError == null && value.isNotEmpty(),
                onClick = { onSave(trimmed, value) }
            ) { Text(tr("Uložit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/** Values remembered by "Uložit hodnotu" between runs. */
@Composable
fun StoredValuesSection() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val values = remember(version) { ValueStore.all(context) }
    // Names of the automations the values belong to.
    val names by androidx.compose.runtime.produceState(emptyMap<Long, String>(), version) {
        value = (context.applicationContext as cz.natsu.automation.AutomationApplication).repository
            .getAll().associate { it.id to it.name }
    }

    Text(tr("Uložené hodnoty"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (values.isEmpty()) tr("Zatím nic. Hodnoty sem ukládá blok Uložit hodnotu, aby si je automatizace ") +
                        tr("pamatovaly i mezi spuštěními.")
                else tr("Co si automatizace zapamatovaly blokem Uložit hodnotu. Každá má svoje, ") +
                        tr("pokud hodnota není nastavená jako sdílená."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Grouped by the automation they belong to; shared values first.
            for ((owner, entries) in values.groupBy { it.automationId }) {
                Text(
                    when (owner) {
                        null -> tr("Sdílené všemi automatizacemi")
                        0L -> tr("Zkušební běh bez automatizace")
                        else -> names[owner] ?: tr("Smazaná automatizace")
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                for (entry in entries) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(entry.value.ifEmpty { tr("(prázdné)") }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { ValueStore.removeByKey(context, entry.storageKey); version++ }) {
                            Icon(Icons.Rounded.Delete, tr("Smazat"))
                        }
                    }
                }
            }
            if (values.size > 1) {
                TextButton(onClick = { ValueStore.clear(context); version++ }) { Text(tr("Smazat vše")) }
            }
        }
    }
}

/**
 * Stored values of one automation, for its settings sheet: see what it
 * remembers, fix a value by hand, or delete it to start over.
 */
@Composable
fun AutomationStoredValues(automationId: Long) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val values = remember(version) { ValueStore.all(context).filter { it.automationId == automationId } }
    var editing by remember { mutableStateOf<ValueStore.Entry?>(null) }
    var adding by remember { mutableStateOf(false) }

    Text(tr("Uložené hodnoty"), style = MaterialTheme.typography.titleMedium)
    Text(
        if (values.isEmpty()) tr("Tahle automatizace si zatím nic nezapamatovala (blok Uložit hodnotu).")
        else tr("Co si tahle automatizace pamatuje mezi spuštěními. Sdílené hodnoty najdeš v Nastavení aplikace."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    for (entry in values) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(entry.value.ifEmpty { tr("(prázdné)") }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { editing = entry }) { Icon(Icons.Rounded.Edit, tr("Upravit")) }
            IconButton(onClick = { ValueStore.removeByKey(context, entry.storageKey); version++ }) {
                Icon(Icons.Rounded.Delete, tr("Smazat"))
            }
        }
    }
    Row {
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Rounded.Add, null)
            Text(tr("Přidat hodnotu"), Modifier.padding(start = 8.dp))
        }
        if (values.size > 1) {
            TextButton(onClick = { ValueStore.removeAutomation(context, automationId); version++ }) { Text(tr("Smazat vše")) }
        }
    }

    if (adding || editing != null) {
        ValueDialog(
            existing = editing,
            taken = values.map { it.name }.toSet(),
            onDismiss = { adding = false; editing = null },
            onSave = { name, value ->
                ValueStore.set(context, automationId, name, value)
                adding = false
                editing = null
                version++
            },
        )
    }
}

@Composable
private fun ValueDialog(
    existing: ValueStore.Entry?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var value by remember { mutableStateOf(existing?.value ?: "") }
    val trimmed = name.trim()
    val nameError = if (existing == null && trimmed in taken) tr("Tenhle název už existuje") else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) tr("Nová uložená hodnota") else tr("Upravit \"%s\"", existing.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(tr("Název")) },
                        placeholder = { Text(tr("např. last_state")) },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(tr("Hodnota")) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && nameError == null, onClick = { onSave(trimmed, value) }) {
                Text(tr("Uložit"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}
