package cz.natsu.automation.ui.editor

import cz.natsu.automation.i18n.tr

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
import androidx.compose.material.icons.rounded.TouchApp
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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

/**
 * Keeps leftover scroll and fling of a list inside its bottom sheet.
 *
 * Without it the end of a fling is handed to the sheet, which starts its own
 * settle animation. Until that finishes the list counts as still scrolling:
 * the next drag does nothing ("frozen" list) and the first tap only stops the
 * scroll instead of picking the item. The sheet can still be closed by
 * dragging its handle / header, tapping outside or pressing back.
 */
private val KeepScrollInList = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockLibrarySheet(onPick: (BlockDef) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(tr("Přidat akci"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(tr("Hledat akci")) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().nestedScroll(KeepScrollInList)) {
            for ((category, defs) in Blocks.library) {
                val visible = defs.filter {
                    query.isBlank() || foldText(tr(it.title)).contains(foldText(query)) ||
                            foldText(tr(it.description)).contains(foldText(query))
                }
                if (visible.isEmpty()) continue
                item(key = "header_${category.name}") {
                    Text(
                        tr(category.title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp)
                    )
                }
                items(visible, key = { it.type }) { def ->
                    ListItem(
                        headlineContent = { Text(tr(def.title)) },
                        supportingContent = { Text(tr(def.description), maxLines = 2) },
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
    /** Closes the sheet and lets the user tap the step whose output is wanted; null = no step has an output. */
    onPickFromSteps: (() -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            tr("Vložit proměnnou"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            tr("Hodnota se doplní až při spuštění akce."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (onPickFromSteps != null) {
            FilledTonalButton(
                onClick = onPickFromSteps,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.TouchApp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Vybrat klepnutím na krok"))
            }
        }
        // A variable by its name – e.g. one that a step only creates while it runs.
        var custom by remember { mutableStateOf("") }
        val customName = custom.trim()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            // A placeholder instead of a label keeps the field exactly as tall as the button next to it.
            OutlinedTextField(
                value = custom,
                onValueChange = { custom = it },
                placeholder = { Text(tr("Proměnná podle názvu")) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(56.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = { onPick(VariableOption(cz.natsu.automation.data.VarRef.Named(customName), customName, tr("Proměnná"))) },
                enabled = customName.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(56.dp),
            ) { Text(tr("Vložit")) }
        }
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(tr("Spouštění"), style = MaterialTheme.typography.titleLarge)
            Text(
                tr("Kde všude se má automatizace nabízet. Text, se kterým se spustí, najdeš v proměnné Vstup."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            TriggerRow(
                icon = Icons.Rounded.TextFields,
                title = tr("Při výběru textu"),
                description = tr("V menu po označení textu (vedle Kopírovat). Výsledek akce nahradí označený text."),
                checked = viewModel.triggerProcessText,
                onChecked = { viewModel.setTriggers(processText = it) }
            )
            TriggerRow(
                icon = Icons.Rounded.Share,
                title = tr("Sdílet"),
                description = tr("V nabídce Sdílet jako vlastní cíl. Sdílený text je Vstup."),
                checked = viewModel.triggerShare,
                onChecked = { viewModel.setTriggers(share = it) }
            )
            TriggerRow(
                icon = Icons.Rounded.Tune,
                title = tr("Dlaždice v rychlém nastavení"),
                description = tr("Klepnutím na dlaždici se akce spustí – může číst i měnit schránku."),
                checked = viewModel.triggerTile,
                onChecked = { viewModel.setTriggers(tile = it) }
            )
            if (viewModel.triggerTile) {
                Text(
                    tr("Společná dlaždice nabídne všechny automatizace, které ji používají. Vlastní dlaždice 1–5 spustí rovnou tuhle."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 52.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(start = 52.dp)
                ) {
                    for (slot in 0..cz.natsu.automation.trigger.TileSlots.count) {
                        androidx.compose.material3.FilterChip(
                            selected = viewModel.tileSlot == slot,
                            onClick = { viewModel.changeTileSlot(slot) },
                            label = { Text(if (slot == 0) tr("Společná") else slot.toString()) },
                        )
                    }
                }
            }
            if (viewModel.triggerTile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FilledTonalButton(
                    onClick = {
                        // Saved first, so the numbered tile is switched on before Android is asked to add it.
                        viewModel.saveNow()
                        requestAddTile(context, viewModel.tileSlot, viewModel.name)
                    },
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                ) {
                    Icon(Icons.Rounded.AddToHomeScreen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Přidat dlaždici"))
                }
            }
            TriggerRow(
                icon = Icons.Rounded.ContentPaste,
                title = tr("Při změně schránky"),
                description = tr("Spustí se hned po zkopírování textu v jakékoli aplikaci. Zkopírovaný text je Vstup."),
                checked = viewModel.triggerClipboard,
                onChecked = { viewModel.setTriggers(clipboard = it) }
            )
            if (viewModel.triggerClipboard && !ClipboardMonitor.isEnabled(context)) {
                Text(
                    tr("Sledování schránky je vypnuté. Zapneš ho v seznamu automatizací → ⋮ → Nastavení (potřebuje jednorázově ADB)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 52.dp, bottom = 4.dp)
                )
            }

            EventsSection(viewModel)

            Spacer(Modifier.height(20.dp))
            Text(tr("Ikona"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            cz.natsu.automation.ui.common.IconPicker(selected = viewModel.icon, onPick = { viewModel.changeIcon(it) })

            Spacer(Modifier.height(20.dp))
            cz.natsu.automation.ui.settings.AutomationStoredValues(viewModel.id)

            Spacer(Modifier.height(20.dp))
            cz.natsu.automation.ui.settings.AutomationRunHistory(viewModel.id)

            Spacer(Modifier.height(20.dp))
            var showText by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showText = true }) {
                Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("Zobrazit jako skript"))
            }
            if (showText) AutomationTextDialog(viewModel) { showText = false }

            Spacer(Modifier.height(20.dp))
            Text(tr("Export a import"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("Automatizaci pošleš jako soubor .json (třeba do jiného telefonu). Import jako novou přidá další automatizaci, tahle zůstane beze změny."),
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
                    Text(tr("Sdílet"))
                }
                FilledTonalButton(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Importovat jako novou"))
                }
            }

            // Updating this automation keeps its id, so its run history, stored values, tile and shortcuts stay.
            Spacer(Modifier.height(16.dp))
            Text(tr("Aktualizovat tuhle automatizaci"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Nahradí kroky novou verzí ze skriptu (třeba od AI) nebo ze souboru. Historie běhů, uložené hodnoty a dlaždice zůstanou. Spuštění, název a ikonu změní, jen když je skript nastavuje (řádky # @); Zpět vrátí předchozí verzi."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            val showUpdated: (String) -> Unit = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            val updateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) viewModel.updateFromFile(uri, showUpdated)
            }
            var pasteUpdate by remember { mutableStateOf(false) }
            if (pasteUpdate) {
                cz.natsu.automation.ui.list.PasteScriptDialog(
                    onDismiss = { pasteUpdate = false },
                    onImport = { text -> pasteUpdate = false; viewModel.updateFromText(text, showUpdated) },
                    title = tr("Aktualizovat ze skriptu"),
                    hint = tr("Vlož novou verzi této automatizace jako skript. Kroky se nahradí, historie a uložené hodnoty zůstanou."),
                    confirm = tr("Aktualizovat"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pasteUpdate = true }) {
                    Icon(Icons.Rounded.TextFields, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Ze skriptu"))
                }
                OutlinedButton(onClick = { updateLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*")) }) {
                    Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Ze souboru"))
                }
            }
        }
    }
}

private fun requestAddTile(context: android.content.Context, slot: Int, name: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(StatusBarManager::class.java) ?: return
    if (slot > 0) {
        // The slot may have been switched on a moment ago; make sure before asking.
        context.packageManager.setComponentEnabledSetting(
            cz.natsu.automation.trigger.TileSlots.component(context, slot),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
    }
    manager.requestAddTileService(
        if (slot > 0) cz.natsu.automation.trigger.TileSlots.component(context, slot)
        else ComponentName(context, AutomationTileService::class.java),
        if (slot > 0) name else tr("Automatizace"),
        Icon.createWithResource(context, R.drawable.ic_tile),
        context.mainExecutor
    ) { result ->
        val message = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> tr("Dlaždice přidána")
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> tr("Dlaždice už v rychlém nastavení je")
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(tr("Vyzkoušet"), style = MaterialTheme.typography.titleLarge)
            if (needsInput) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(tr("Vstup (např. označený text)")) },
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
                Text(if (viewModel.running) tr("Běží…") else tr("Spustit"))
            }
            OutlinedButton(
                onClick = { onStepByStep(input) },
                enabled = !viewModel.running,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) {
                Icon(Icons.Rounded.SkipNext, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Krokovat – po každém kroku zastavit"))
            }
            if (viewModel.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            if (result != null) {
                Spacer(Modifier.height(16.dp))
                ResultCard(result, onGrantRequirement)
                if (result.log.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Průběh"), style = MaterialTheme.typography.titleSmall)
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
                                Text(tr(entry.title), style = MaterialTheme.typography.bodyMedium)
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
                        result.cancelled -> tr("Zrušeno")
                        ok -> tr("Hotovo")
                        else -> tr("Chyba")
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (result.error != null) {
                Text(result.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (result.output != null) {
                Text(tr("Výsledek:"), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                SelectionContainer {
                    Text(cz.natsu.automation.engine.logPreview(result.output)!!, style = MaterialTheme.typography.bodyMedium)
                }
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
        title = { Text(tr("Název automatizace")) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(tr("Uložit")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } }
    )
}

@Composable
fun AutomationBadge(iconKey: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    IconBadge(automationIcon(iconKey), automationHue(iconKey).accent(), size = size)
}

/** Lower case without diacritics, so "udaje" finds "Údaje". */
private fun foldText(text: String): String =
    java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
