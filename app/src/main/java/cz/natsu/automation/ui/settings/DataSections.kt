package cz.natsu.automation.ui.settings

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
import cz.natsu.automation.data.RunRecord
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.ValueStore

/** The last runs started by triggers and events, with the error of the failed ones. */
@Composable
fun RunHistorySection(resumeCounter: Int) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val records = remember(version, resumeCounter) { RunHistory.all(context) }
    var showAll by remember { mutableStateOf(false) }
    val timeFormat = remember { java.text.SimpleDateFormat("d. M. H:mm:ss", java.util.Locale.getDefault()) }

    Text("Historie běhů", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (records.isEmpty()) {
                Text(
                    "Zatím nic. Objeví se tu spuštění událostí, dlaždicí, sdílením nebo ze schránky – " +
                            "i ta na pozadí, u kterých by ses o chybě jinak nedozvěděl.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (record in if (showAll) records else records.take(8)) {
                val failed = record.outcome == RunRecord.ERROR
                Column {
                    Text(
                        record.name + when (record.outcome) {
                            RunRecord.ERROR -> " – chyba"
                            RunRecord.CANCELLED -> " – zrušeno"
                            else -> ""
                        },
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${timeFormat.format(java.util.Date(record.time))} · ${record.trigger} · " +
                                "${record.steps} kroků · ${formatDuration(record.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    record.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (records.isNotEmpty()) Row {
                if (records.size > 8) TextButton(onClick = { showAll = !showAll }) {
                    Text(if (showAll) "Zobrazit méně" else "Zobrazit vše (${records.size})")
                }
                TextButton(onClick = { RunHistory.clear(context); version++ }) { Text("Vymazat") }
            }
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

    Text("Tajné hodnoty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "API klíče, tokeny a hesla. Jsou zašifrované klíčem v Android Keystore, do kroků se vkládají " +
                        "jako proměnná 🔑 a do exportu automatizace se nikdy nedostanou. V záznamu běhu jsou skryté.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for (name in names) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                    Text(name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { editing = name }) { Icon(Icons.Rounded.Edit, "Změnit") }
                    IconButton(onClick = { deleting = name }) { Icon(Icons.Rounded.Delete, "Smazat") }
                }
            }
            TextButton(onClick = { editing = "" }) {
                Icon(Icons.Rounded.Add, null)
                Text("Přidat tajnou hodnotu", Modifier.padding(start = 8.dp))
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
            title = { Text("Smazat \"$name\"?") },
            text = { Text("Automatizace, které ji používají, skončí chybou, dokud ji znovu nepřidáš.") },
            confirmButton = {
                TextButton(onClick = {
                    SecretStore.remove(context, name)
                    deleting = null
                    version++
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Zrušit") } },
        )
    }
}

@Composable
private fun SecretDialog(
    existingName: String?,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
) {
    var name by remember { mutableStateOf(existingName ?: "") }
    // The stored value is never shown again; changing a secret means typing it anew.
    var value by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val nameError = when {
        trimmed.isEmpty() -> null
        trimmed != existingName && trimmed in taken -> "Tenhle název už existuje"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingName == null) "Nová tajná hodnota" else "Změnit tajnou hodnotu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název") },
                    placeholder = { Text("např. github_token") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(if (existingName == null) "Hodnota" else "Nová hodnota") },
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
            ) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
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

    Text("Uložené hodnoty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (values.isEmpty()) "Zatím nic. Hodnoty sem ukládá blok Uložit hodnotu, aby si je automatizace " +
                        "pamatovaly i mezi spuštěními."
                else "Co si automatizace zapamatovaly blokem Uložit hodnotu. Každá má svoje, " +
                        "pokud hodnota není nastavená jako sdílená.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Grouped by the automation they belong to; shared values first.
            for ((owner, entries) in values.groupBy { it.automationId }) {
                Text(
                    when (owner) {
                        null -> "Sdílené všemi automatizacemi"
                        0L -> "Zkušební běh bez automatizace"
                        else -> names[owner] ?: "Smazaná automatizace"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                for (entry in entries) {
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(entry.value.ifEmpty { "(prázdné)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { ValueStore.removeByKey(context, entry.storageKey); version++ }) {
                            Icon(Icons.Rounded.Delete, "Smazat")
                        }
                    }
                }
            }
            if (values.size > 1) {
                TextButton(onClick = { ValueStore.clear(context); version++ }) { Text("Smazat vše") }
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

    Text("Uložené hodnoty", style = MaterialTheme.typography.titleMedium)
    Text(
        if (values.isEmpty()) "Tahle automatizace si zatím nic nezapamatovala (blok Uložit hodnotu)."
        else "Co si tahle automatizace pamatuje mezi spuštěními. Sdílené hodnoty najdeš v Nastavení aplikace.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    for (entry in values) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(entry.value.ifEmpty { "(prázdné)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { editing = entry }) { Icon(Icons.Rounded.Edit, "Upravit") }
            IconButton(onClick = { ValueStore.removeByKey(context, entry.storageKey); version++ }) {
                Icon(Icons.Rounded.Delete, "Smazat")
            }
        }
    }
    Row {
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Rounded.Add, null)
            Text("Přidat hodnotu", Modifier.padding(start = 8.dp))
        }
        if (values.size > 1) {
            TextButton(onClick = { ValueStore.removeAutomation(context, automationId); version++ }) { Text("Smazat vše") }
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
    val nameError = if (existing == null && trimmed in taken) "Tenhle název už existuje" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nová uložená hodnota" else "Upravit \"${existing.name}\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Název") },
                        placeholder = { Text("např. last_state") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Hodnota") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = trimmed.isNotEmpty() && nameError == null, onClick = { onSave(trimmed, value) }) {
                Text("Uložit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
