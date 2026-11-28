package cz.natsu.automation.ui.editor

import cz.natsu.automation.i18n.tr

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.Requirement
import cz.natsu.automation.ui.common.hue
import cz.natsu.automation.ui.theme.AccentHue
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showLibrary by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRun by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showReview by remember { mutableStateOf(false) }
    // Secrets the steps use that this phone does not have (an imported automation, a deleted secret).
    var secretsVersion by remember { mutableIntStateOf(0) }
    var addingSecret by remember { mutableStateOf<String?>(null) }
    val usedSecrets = viewModel.secretsUsed()
    val missingSecrets = remember(secretsVersion, usedSecrets) {
        usedSecrets.filterNot { cz.natsu.automation.data.SecretStore.contains(context, it) }
    }
    addingSecret?.let { name ->
        cz.natsu.automation.ui.settings.AddMissingSecretDialog(
            name = name,
            onDismiss = { addingSecret = null },
            onSaved = { addingSecret = null; secretsVersion++ },
        )
    }
    var lostAcknowledged by remember { mutableStateOf(false) }
    var variableTarget by remember { mutableStateOf<Pair<String, RichTextFieldController>?>(null) }
    // Picking a variable by tapping the step that produces it (instead of from the list).
    var pickTarget by remember { mutableStateOf<Pair<String, RichTextFieldController>?>(null) }
    val pickLimit = pickTarget?.let { (id, _) -> viewModel.indexOf(id).takeIf { it >= 0 } ?: viewModel.blocks.size }

    // Re-check system permissions whenever the user comes back from settings.
    var resumeCounter by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCounter++
        onPauseOrDispose { viewModel.saveNow() }
    }
    val policyGranted = remember(resumeCounter) {
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    }
    val notificationsAllowed = remember(resumeCounter) {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val localNetworkLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { resumeCounter++ }
    fun missing(requirement: Requirement?): Requirement? = when (requirement) {
        Requirement.NotificationPolicy -> if (policyGranted) null else requirement
        Requirement.PostNotifications -> if (notificationsAllowed) null else requirement
        Requirement.Overlay -> if (Settings.canDrawOverlays(context)) null else requirement
        Requirement.LocalNetwork -> if (cz.natsu.automation.engine.LocalNetwork.granted(context)) null else requirement
        Requirement.WriteSettings -> if (Settings.System.canWrite(context)) null else requirement
        Requirement.Location -> if (cz.natsu.automation.engine.DeviceState.hasLocation(context)) null else requirement
        Requirement.InstallPackages -> if (context.packageManager.canRequestPackageInstalls()) null else requirement
        null -> null
    }
    fun grant(requirement: Requirement) {
        when (requirement) {
            Requirement.NotificationPolicy -> context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            )
            Requirement.PostNotifications -> context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
            Requirement.LocalNetwork -> localNetworkLauncher.launch(cz.natsu.automation.engine.LocalNetwork.PERMISSION)
            Requirement.WriteSettings -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
            )
            Requirement.Location -> localNetworkLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            Requirement.Overlay -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            )
            Requirement.InstallPackages -> context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            )
        }
    }

    val inputAccent = AccentHue.Orange.accent()
    val clipboardAccent = AccentHue.Teal.accent()
    val dateAccent = AccentHue.Pink.accent()
    val variableAccent = AccentHue.Purple.accent()
    val missingAccent = AccentHue.Red.accent()
    val categoryAccents = cz.natsu.automation.engine.BlockCategory.entries.associateWith { it.hue.accent() }
    val chipStyle: (VarRef) -> ChipStyle = remember(inputAccent, categoryAccents) {
        { ref ->
            val label = viewModel.labelOf(ref)
            when {
                label == null -> ChipStyle(tr("chybí"), missingAccent.container, missingAccent.content)
                ref == VarRef.Input -> ChipStyle(label, inputAccent.container, inputAccent.content)
                ref == VarRef.Clipboard -> ChipStyle(label, clipboardAccent.container, clipboardAccent.content)
                ref == VarRef.CurrentDate -> ChipStyle(label, dateAccent.container, dateAccent.content)
                ref == VarRef.Trigger -> ChipStyle(label, inputAccent.container, inputAccent.content)
                ref is VarRef.Event -> ChipStyle(label, clipboardAccent.container, clipboardAccent.content)
                ref is VarRef.Named -> ChipStyle(label, variableAccent.container, variableAccent.content)
                ref is VarRef.BlockOutput -> {
                    val type = viewModel.blocks.firstOrNull { it.id == ref.blockId }?.type
                    val accent = Blocks.get(type ?: "")?.category?.let { categoryAccents[it] } ?: variableAccent
                    ChipStyle(label, accent.container, accent.content)
                }
                else -> ChipStyle(label, variableAccent.container, variableAccent.content)
            }
        }
    }

    val listState = rememberLazyListState()
    val reorderLocked = viewModel.running || viewModel.stepMode
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromId = from.key as? String ?: return@rememberReorderableLazyListState
        val toId = to.key as? String ?: return@rememberReorderableLazyListState
        viewModel.move(fromId, toId)
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun reportMove(ok: Boolean) {
        if (!ok) scope.launch { snackbar.showSnackbar(tr("Takhle by se podmínky překřížily – přesun vrácen")) }
    }

    val topBarScroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(topBarScroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarScroll,
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zpět")) }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showRename = true }
                    ) {
                        AutomationBadge(viewModel.icon, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(viewModel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Edit, tr("Přejmenovat"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    val collapsed = viewModel.allCollapsed
                    IconButton(onClick = { viewModel.setAllExpanded(collapsed) }) {
                        Icon(
                            if (collapsed) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                            if (collapsed) tr("Rozbalit vše") else tr("Sbalit vše")
                        )
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Tune, tr("Nastavení")) }
                    FilledTonalIconButton(
                        onClick = { if (viewModel.needsReview) showReview = true else showRun = true },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, tr("Spustit"))
                    }
                }
            )
        },
        bottomBar = {
            if (viewModel.stepMode) StepRunBar(viewModel)
        },
        floatingActionButton = {
            if (!viewModel.stepMode && pickTarget == null) ExtendedFloatingActionButton(
                onClick = { showLibrary = true },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(tr("Přidat akci")) }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!viewModel.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Shrink the list above the keyboard so the edited field can scroll into view.
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (viewModel.needsReview) {
                item(key = "review") {
                    Surface(
                        onClick = { showReview = true },
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Importovaná automatizace – zatím se nespouští. Projdi si kroky a klepnutím sem ji povol."),
                                Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            if (missingSecrets.isNotEmpty()) {
                item(key = "missing_secrets") {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                tr("Chybí tajné hodnoty: %s – bez nich automatizace skončí chybou.", missingSecrets.joinToString(", ")),
                                Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            androidx.compose.material3.TextButton(onClick = { addingSecret = missingSecrets.first() }) { Text(tr("Doplnit")) }
                        }
                    }
                }
            }

            item(key = "triggers") {
                TriggerSummary(viewModel) { showSettings = true }
            }

            item(key = "conditions") {
                LaunchConditionsSection(
                    viewModel = viewModel,
                    chipStyle = chipStyle,
                    onRequestVariable = { id, controller -> variableTarget = id to controller },
                )
            }

            if (!viewModel.structure.valid && viewModel.foldedGroup == null) {
                item(key = "invalid") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null)
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Podmínky nejsou správně uzavřené. Zkontroluj pořadí bloků Pokud / Jinak / Konec."))
                        }
                    }
                }
            }

            if (viewModel.blocks.isEmpty()) {
                item(key = "empty") { EmptyEditor() }
            }

            itemsIndexed(viewModel.blocks, key = { _, block -> block.id }) { index, block ->
                val def = Blocks.get(block.type) ?: return@itemsIndexed
                val depth = viewModel.structure.depth.getOrElse(index) { 0 }
                ReorderableItem(reorderState, key = block.id) { isDragging ->
                    androidx.compose.runtime.CompositionLocalProvider(LocalAutomations provides viewModel.automations) {
                    BlockCard(
                        block = block,
                        def = def,
                        outputLabel = if (def.outputName != null) viewModel.outputLabel(block) else null,
                        active = viewModel.activeBlockId == block.id,
                        dragging = isDragging,
                        missingRequirement = missing(def.requirement),
                        chipStyle = chipStyle,
                        expanded = viewModel.isExpanded(block.id),
                        onToggleExpanded = { viewModel.toggleExpanded(block.id) },
                        outputStyle = if (def.outputName != null) chipStyle(VarRef.BlockOutput(block.id)) else null,
                        foldedSteps = viewModel.foldedCount(block.id),
                        runEntry = if (viewModel.stepMode) viewModel.stepResults[block.id] else null,
                        onPickOutput = pickTarget?.takeIf { def.outputName != null && index < (pickLimit ?: 0) }?.let { (_, controller) ->
                            {
                                controller.insertVariable(VarRef.BlockOutput(block.id))
                                pickTarget = null
                            }
                        },
                        suggestions = if ((def.kind == BlockKind.IfStart || def.kind == BlockKind.WhileStart || def.kind == BlockKind.ElseIf) && viewModel.isExpanded(block.id)) {
                            viewModel.suggestionsFor(block)
                        } else null,
                        dragHandle = Modifier.draggableHandle(
                            // Steps keep their order while the automation runs (results stay on the right steps).
                            enabled = !reorderLocked,
                            onDragStarted = {
                                viewModel.startDrag(block.id)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragStopped = { reportMove(viewModel.endDrag()) },
                        ),
                        actions = BlockCardActions(
                            onParamChange = { key, value -> viewModel.updateParam(block.id, key, value) },
                            onRequestVariable = { controller -> variableTarget = block.id to controller },
                            onDuplicate = { viewModel.duplicate(block.id) },
                            onRemove = {
                                val removal = viewModel.remove(block.id)
                                if (removal != null && def.kind != BlockKind.Normal) {
                                    scope.launch {
                                        snackbar.currentSnackbarData?.dismiss()
                                        val what = when (def.kind) {
                                            BlockKind.TryStart -> tr("Zkusit smazáno")
                                            BlockKind.WhileStart -> tr("Opakování smazáno")
                                            BlockKind.IfStart -> tr("Podmínka smazána")
                                            BlockKind.ParallelStart -> tr("Souběžné větve smazány")
                                            BlockKind.Scope -> tr("Skupina smazána")
                                            else -> tr("Větev smazána")
                                        }
                                        val steps = when (removal.innerSteps) {
                                            0 -> ""
                                            1 -> tr(" i s 1 krokem")
                                            else -> tr(" i s %s kroky", removal.innerSteps)
                                        }
                                        val result = snackbar.showSnackbar(
                                            message = what + steps,
                                            actionLabel = tr("Vrátit"),
                                            duration = SnackbarDuration.Long,
                                        )
                                        if (result == SnackbarResult.ActionPerformed) viewModel.restore(removal.before)
                                    }
                                }
                            },
                            onMoveUp = if (reorderLocked) null else { { reportMove(viewModel.moveBy(block.id, -1)) } },
                            onMoveDown = if (reorderLocked) null else { { reportMove(viewModel.moveBy(block.id, 1)) } },
                            onAddElse = if ((def.kind == BlockKind.IfStart || def.kind == BlockKind.TryStart) && !viewModel.hasElse(block)) {
                                { viewModel.addElse(block) }
                            } else null,
                            onGrantRequirement = ::grant,
                            onToggleDisabled = if (def.kind == BlockKind.Else || def.kind == BlockKind.End || def.kind == BlockKind.ElseIf || def.kind == BlockKind.Branch) null else {
                                { viewModel.toggleDisabled(block.id) }
                            },
                            onAddElseIf = if (def.kind == BlockKind.IfStart || def.kind == BlockKind.ElseIf ||
                                def.kind == BlockKind.ParallelStart || def.kind == BlockKind.Branch
                            ) {
                                { viewModel.addElseIf(block) }
                            } else null,
                        ),
                        modifier = Modifier.padding(start = (depth * 20).dp),
                    )
                    }
                }
            }
        }

            if (pickTarget != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            cz.natsu.automation.i18n.tr("Klepni na „Vložit výstup“ u kroku, jehož hodnotu chceš použít."),
                            Modifier.weight(1f).padding(vertical = 10.dp)
                        )
                        androidx.compose.material3.TextButton(onClick = { pickTarget = null }) {
                            Text(cz.natsu.automation.i18n.tr("Zrušit"))
                        }
                    }
                }
            }

            // Undo / redo sit opposite the "add" button – the top bar has no room left.
            if (!viewModel.stepMode) HistoryButtons(
                canUndo = viewModel.canUndo,
                canRedo = viewModel.canRedo,
                onUndo = {
                    snackbar.currentSnackbarData?.dismiss()
                    viewModel.undo()
                },
                onRedo = {
                    snackbar.currentSnackbarData?.dismiss()
                    viewModel.redo()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            )
        }
    }

    if (showLibrary) {
        BlockLibrarySheet(
            onPick = { def ->
                showLibrary = false
                viewModel.addBlock(def)
                scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount) }
            },
            onDismiss = { showLibrary = false }
        )
    }

    variableTarget?.let { (blockId, controller) ->
        val options = viewModel.variablesFor(blockId)
        VariablePickerSheet(
            options = options,
            onPickFromSteps = if (options.any { it.ref is VarRef.BlockOutput }) {
                {
                    pickTarget = blockId to controller
                    variableTarget = null
                }
            } else null,
            chipStyle = chipStyle,
            onPick = { option ->
                controller.insertVariable(option.ref)
                variableTarget = null
            },
            onDismiss = { variableTarget = null }
        )
    }

    if (showSettings) {
        AutomationSettingsSheet(viewModel, onDismiss = { showSettings = false })
    }

    if (showRun) {
        RunSheet(
            viewModel,
            onGrantRequirement = ::grant,
            onDismiss = {
                showRun = false
                viewModel.clearResult()
            },
            onStepByStep = { input ->
                showRun = false
                viewModel.run(input, stepByStep = true)
            },
        )
    }

    // Step-by-step run: keep the step that just ran in view.
    val pausedId = viewModel.pausedAt?.blockId
    LaunchedEffect(pausedId) {
        val id = pausedId ?: return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.key == id }
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        if (item == null || item.offset + item.size > viewportEnd) {
            val blockIndex = viewModel.indexOf(id)
            if (blockIndex >= 0) {
                // Items above the steps: triggers, launch conditions (and the warning).
                val before = 2 + (if (!viewModel.structure.valid) 1 else 0) + (if (viewModel.needsReview) 1 else 0)
                listState.animateScrollToItem(before + blockIndex)
            }
        }
    }

    cz.natsu.automation.ui.run.RunDialogs(viewModel.runUi)

    if (showRename) {
        RenameDialog(
            current = viewModel.name,
            onConfirm = { viewModel.rename(it); showRename = false },
            onDismiss = { showRename = false }
        )
    }

    if (showReview && viewModel.needsReview) {
        val summary = remember { viewModel.reviewSummary() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showReview = false },
            title = { Text(tr("Povolit importovanou automatizaci?")) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(tr("Pochází ze souboru. Povol ji, jen když věříš tomu, kdo ji vytvořil, a víš, co dělá:"))
                    for (line in summary) Text(line, style = MaterialTheme.typography.bodyMedium)
                    if (usedSecrets.isNotEmpty()) {
                        Text(tr("Používá tvoje tajné hodnoty:"), style = MaterialTheme.typography.bodyMedium)
                        for (name in usedSecrets) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Key, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(name, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                                if (name in missingSecrets) {
                                    androidx.compose.material3.TextButton(onClick = { addingSecret = name }) { Text(tr("Doplnit")) }
                                } else {
                                    Text(tr("uložená ✓"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        if (missingSecrets.isNotEmpty()) Text(
                            tr("Bez chybějících hodnot automatizace skončí chybou. Do exportu se tajné hodnoty nikdy nedostanou, proto je musíš zadat sám."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.approve(); showReview = false }) {
                    Text(tr("Povolit"))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showReview = false }) { Text(tr("Zatím ne")) }
            },
        )
    }

    if (viewModel.unreadable) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBack,
            title = { Text(tr("Automatizaci nejde načíst")) },
            text = {
                Text(
                    tr("Uložené kroky jsou poškozené nebo z novější verze aplikace. Nic se nepřepíše – ") +
                            tr("data zůstávají uložená a po aktualizaci aplikace se můžou znovu načíst.")
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onBack) { Text(tr("Zpět")) } },
        )
    } else if (viewModel.lostParts > 0 && !lostAcknowledged) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBack,
            title = { Text(tr("Část automatizace nejde načíst")) },
            text = {
                Text(
                    tr("Nepodařilo se přečíst %s kroků nebo hodnot (nejspíš jsou z novější verze ", viewModel.lostParts) +
                            tr("aplikace). Dokud nic nezměníš, zůstávají uložené. Když automatizaci upravíš, ") +
                            tr("tyhle části se ztratí.")
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { lostAcknowledged = true }) { Text(tr("Přesto upravit")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onBack) { Text(tr("Zpět")) } },
        )
    }

    if (viewModel.missing) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            Toast.makeText(context, tr("Automatizace už neexistuje"), Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
}

@Composable
private fun TriggerSummary(viewModel: EditorViewModel, onClick: () -> Unit) {
    val active = buildList {
        if (viewModel.triggerProcessText) add(Icons.Rounded.TextFields to tr("Výběr textu"))
        if (viewModel.triggerShare) add(Icons.Rounded.Share to tr("Sdílet"))
        if (viewModel.triggerTile) add(Icons.Rounded.Tune to tr("Dlaždice"))
        if (viewModel.triggerClipboard) add(Icons.Rounded.ContentPaste to tr("Změna schránky"))
        if (viewModel.triggerWidget) add(Icons.Rounded.Widgets to tr("Widget"))
        for (event in viewModel.events) add(eventIcon(cz.natsu.automation.data.EventType.of(event.type)) to eventLabel(event))
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Spouštění"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    if (active.isEmpty()) tr("Jen ručně z aplikace – klepni a vyber, kde se má nabízet")
                    else active.joinToString(" · ") { it.second },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun EmptyEditor() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.Bolt,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(tr("Zatím žádné kroky"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Přidej první akci tlačítkem dole. Kroky se provedou postupně shora dolů, ") +
                    tr("výsledky předchozích kroků vložíš do textu přes ikonu { }."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** Pill with "Zpět" / "Znovu", shown once there is something to undo or redo. */
@Composable
private fun HistoryButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = canUndo || canRedo,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, tr("Zpět o krok"))
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, tr("Znovu"))
                }
            }
        }
    }
}
