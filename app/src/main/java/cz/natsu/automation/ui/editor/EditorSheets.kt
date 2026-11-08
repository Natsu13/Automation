package cz.natsu.automation.ui.editor

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ContentPaste
import cz.natsu.automation.trigger.ClipboardMonitor
import androidx.compose.runtime.rememberCoroutineScope
import cz.natsu.automation.data.AutomationTransfer
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.natsu.automation.R
import cz.natsu.automation.data.AutomationIcons
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.Requirement
import cz.natsu.automation.engine.RunResult
import cz.natsu.automation.trigger.AutomationTileService
import cz.natsu.automation.ui.common.IconBadge
import cz.natsu.automation.ui.common.automationHue
import cz.natsu.automation.ui.common.automationIcon
import cz.natsu.automation.ui.common.hue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLibrarySheet(onPick: (BlockDef) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Přidat akci", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Hledat akci") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            for ((category, defs) in Blocks.library) {
                val visible = defs.filter {
                    query.isBlank() || foldText(it.title).contains(foldText(query)) ||
                            foldText(it.description).contains(foldText(query))
                }
                if (visible.isEmpty()) continue
                item(key = "header_${category.name}") {
                    Text(
                        category.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
                    )
                }
                items(visible, key = { it.type }) { def ->
                    ListItem(
                        headlineContent = { Text(def.title) },
                        supportingContent = { Text(def.description, maxLines = 2) },
                        leadingContent = { IconBadge(def.icon, def.category.hue.accent()) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onPick(def) }
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariablePickerSheet(
    options: List<VariableOption>,
    chipStyle: (cz.natsu.automation.data.VarRef) -> ChipStyle,
    onPick: (VariableOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Vložit proměnnou",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            "Hodnota se doplní až při spuštění akce.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        LazyColumn {
            items(options) { option ->
                val style = chipStyle(option.ref)
                ListItem(
                    headlineContent = {
                        Surface(shape = RoundedCornerShape(8.dp), color = style.container) {
                            Text(
                                option.label,
                                color = style.content,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    },
                    supportingContent = { Text(option.description) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { onPick(option) }
                )
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationSettingsSheet(
    viewModel: EditorViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text("Spouštění", style = MaterialTheme.typography.titleLarge)
            Text(
                "Kde všude se má automatizace nabízet. Text, se kterým se spustí, najdeš v proměnné Vstup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            TriggerRow(
                icon = Icons.Rounded.TextFields,
                title = "Při výběru textu",
                description = "V menu po označení textu (vedle Kopírovat). Výsledek akce nahradí označený text.",
                checked = viewModel.triggerProcessText,
                onChecked = { viewModel.setTriggers(processText = it) }
            )
            TriggerRow(
                icon = Icons.Rounded.Share,
                title = "Sdílet",
                description = "V nabídce Sdílet jako vlastní cíl. Sdílený text je Vstup.",
                checked = viewModel.triggerShare,
                onChecked = { viewModel.setTriggers(share = it) }
            )
            TriggerRow(
                icon = Icons.Rounded.Tune,
                title = "Dlaždice v rychlém nastavení",
                description = "Klepnutím na dlaždici se akce spustí – může číst i měnit schránku.",
                checked = viewModel.triggerTile,
                onChecked = { viewModel.setTriggers(tile = it) }
            )
            TriggerRow(
                icon = Icons.Rounded.ContentPaste,
                title = "Při změně schránky",
                description = "Spustí se hned po zkopírování textu v jakékoli aplikaci. Zkopírovaný text je Vstup.",
                checked = viewModel.triggerClipboard,
                onChecked = { viewModel.setTriggers(clipboard = it) }
            )
            if (viewModel.triggerClipboard && !ClipboardMonitor.isEnabled(context)) {
                Text(
                    "Sledování schránky je vypnuté. Zapneš ho v seznamu automatizací → ⋮ → Nastavení (potřebuje jednorázově ADB).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 52.dp, bottom = 4.dp)
                )
            }
            if (viewModel.triggerTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FilledTonalButton(
                    onClick = { requestAddTile(context) },
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                ) {
                    Icon(Icons.Rounded.AddToHomeScreen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Přidat dlaždici")
                }
            }

            EventsSection(viewModel)

            Spacer(Modifier.height(20.dp))
            Text("Ikona", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in AutomationIcons.ALL) {
                    FilledIconToggleButton(
                        checked = viewModel.icon == key,
                        onCheckedChange = { viewModel.changeIcon(key) },
                        shape = CircleShape,
                    ) {
                        Icon(automationIcon(key), contentDescription = key)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Export a import", style = MaterialTheme.typography.titleMedium)
            Text(
                "Automatizaci pošleš jako soubor .json (třeba do jiného telefonu). Import ze souboru přidá novou automatizaci, tahle zůstane beze změny.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            val scope = rememberCoroutineScope()
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) viewModel.import(uri) { message ->
                    message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    viewModel.saveNow()
                    viewModel.exportEntity()?.let { entity ->
                        scope.launch { AutomationTransfer.share(context, listOf(entity)) }
                    }
                }) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sdílet")
                }
                FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Importovat")
                }
            }
        }
    }
}

private fun requestAddTile(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return
    manager.requestAddTileService(
        ComponentName(context, AutomationTileService::class.java),
        "Automatizace",
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Dlaždice přidána"
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Dlaždice už v rychlém nastavení je"
            else -> null
        }
        message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
}

@Composable
private fun TriggerRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp, start = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunSheet(
    viewModel: EditorViewModel,
    onGrantRequirement: (Requirement) -> Unit,
    onDismiss: () -> Unit,
    /** Starts a step-by-step run; the sheet closes so the steps can be watched. */
    onStepByStep: (input: String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val needsInput = remember(viewModel.blocks.toList()) { viewModel.usesInput() }
    val result = viewModel.lastResult

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text("Vyzkoušet", style = MaterialTheme.typography.titleLarge)
            if (needsInput) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Vstup (např. označený text)") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.run(input) },
                enabled = !viewModel.running,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (viewModel.running) "Běží…" else "Spustit")
            }
            OutlinedButton(
                onClick = { onStepByStep(input) },
                enabled = !viewModel.running,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Icon(Icons.Rounded.SkipNext, null)
                Spacer(Modifier.width(8.dp))
                Text("Krokovat – po každém kroku zastavit")
            }
            if (viewModel.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            if (result != null) {
                Spacer(Modifier.height(16.dp))
                ResultCard(result, onGrantRequirement)
                if (result.log.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Průběh", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    for ((index, entry) in result.log.withIndex()) {
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp)
                            )
                            Column {
                                Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                                val detail = entry.error ?: entry.output
                                if (detail != null) {
                                    Text(
                                        detail.take(300),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (entry.error != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: RunResult, onGrantRequirement: (Requirement) -> Unit) {
    val ok = result.success
    Surface(
        color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        result.cancelled -> "Zrušeno"
                        ok -> "Hotovo"
                        else -> "Chyba"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (result.error != null) {
                Text(result.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (result.output != null) {
                Text("Výsledek:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                SelectionContainer { Text(result.output, style = MaterialTheme.typography.bodyMedium) }
            }
            if (result.requirement != null) {
                TextButton(onClick = { onGrantRequirement(result.requirement) }) {
                    Text("Povolit: ${result.requirement.title}")
                }
            }
        }
    }
}

@Composable
fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Název automatizace") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text("Uložit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } }
    )
}

@Composable
fun AutomationBadge(iconKey: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    IconBadge(automationIcon(iconKey), automationHue(iconKey).accent(), size = size)
}

/** Lower case without diacritics, so "udaje" finds "Údaje". */
private fun foldText(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
