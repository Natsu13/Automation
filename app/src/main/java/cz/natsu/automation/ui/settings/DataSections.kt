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
import cz.natsu.automation.data.SecretStore
import cz.natsu.automation.data.ValueStore

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

    Text("Uložené hodnoty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (values.isEmpty()) "Zatím nic. Hodnoty sem ukládá blok Uložit hodnotu, aby si je automatizace " +
                        "pamatovaly i mezi spuštěními."
                else "Co si automatizace zapamatovaly blokem Uložit hodnotu.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((key, value) in values) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(value.ifEmpty { "(prázdné)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { ValueStore.remove(context, key); version++ }) {
                        Icon(Icons.Rounded.Delete, "Smazat")
                    }
                }
            }
            if (values.size > 1) {
                TextButton(onClick = { ValueStore.clear(context); version++ }) { Text("Smazat vše") }
            }
        }
    }
}
