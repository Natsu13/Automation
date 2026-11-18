package cz.natsu.automation.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.Block
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.engine.Requirement
import cz.natsu.automation.engine.formatNumber
import cz.natsu.automation.ui.common.IconBadge
import cz.natsu.automation.ui.common.hue

data class BlockCardActions(
    val onParamChange: (key: String, value: ParamValue) -> Unit,
    val onRequestVariable: (RichTextFieldController) -> Unit,
    val onDuplicate: () -> Unit,
    val onRemove: () -> Unit,
    /** Null while the steps can't be reordered (an automation is running). */
    val onMoveUp: (() -> Unit)?,
    val onMoveDown: (() -> Unit)?,
    val onAddElse: (() -> Unit)?,
    val onGrantRequirement: (Requirement) -> Unit,
    /** Switch the step off / on; null where it makes no sense (otherwise / end). */
    val onToggleDisabled: (() -> Unit)? = null,
    /** Add another condition ("Jinak pokud") to an "if"; null elsewhere. */
    val onAddElseIf: (() -> Unit)? = null,
)

@Composable
fun BlockCard(
    block: Block,
    def: BlockDef,
    outputLabel: String?,
    active: Boolean,
    dragging: Boolean,
    missingRequirement: Requirement?,
    chipStyle: (cz.natsu.automation.data.VarRef) -> ChipStyle,
    actions: BlockCardActions,
    dragHandle: Modifier,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    outputStyle: ChipStyle?,
    modifier: Modifier = Modifier,
    /** Number of steps folded into this "if" while the whole condition is being dragged. */
    foldedSteps: Int = 0,
    /** What this step did in a step-by-step run. */
    runEntry: cz.natsu.automation.engine.LogEntry? = null,
    /** Values offered under "Porovnat s" of a condition. */
    suggestions: ValueSuggestions? = null,
    /** While a variable is being picked by tapping a step: inserts this step's output. */
    onPickOutput: (() -> Unit)? = null,
) {
    val disabled = block.disabled
    val accent = def.category.hue.accent()
    val border by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "activeBorder"
    )
    val compact = def.kind == BlockKind.Else || def.kind == BlockKind.End || def.kind == BlockKind.Branch
    val expandable = def.params.isNotEmpty()
    val showFields = expandable && expanded && foldedSteps == 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 14.dp else 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (compact) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
        border = BorderStroke(2.dp, border),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp)
                .alpha(if (disabled) 0.5f else 1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.DragIndicator,
                    contentDescription = tr("Přesunout"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandle.size(24.dp)
                )
                Spacer(Modifier.width(6.dp))
                IconBadge(def.icon, accent, size = if (compact) 28.dp else 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = expandable, onClick = onToggleExpanded)
                        .padding(vertical = 4.dp)
                ) {
                    if (showFields || compact) {
                        Text(
                            tr(def.title),
                            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        BlockSummary(
                            block = block,
                            def = def,
                            chipStyle = chipStyle,
                            outputLabel = outputLabel,
                            outputStyle = outputStyle,
                        )
                    }
                    if (disabled) {
                        Text(
                            if (def.kind.isGroupStart) tr("Vypnuto – přeskočí se i s kroky uvnitř") else tr("Vypnuto – přeskočí se"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (foldedSteps > 0) {
                        Text(
                            tr("+ kroků uvnitř: %s", foldedSteps),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (expandable && foldedSteps == 0) {
                    Icon(
                        if (showFields) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (showFields) tr("Sbalit") else tr("Rozbalit"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onToggleExpanded)
                            .padding(4.dp)
                    )
                }
                cz.natsu.automation.engine.BlockHelp.of(def.type)?.let { help -> HelpButton(tr(def.title), tr(help)) }
                if (def.kind != BlockKind.End) {
                    BlockMenu(def, actions, disabled)
                }
            }

            if (onPickOutput != null) {
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onPickOutput, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Vložit výstup") + (outputLabel?.let { ": $it" } ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (runEntry != null) {
                Spacer(Modifier.size(8.dp))
                StepResultBox(runEntry)
            }

            if (missingRequirement != null) {
                Spacer(Modifier.size(10.dp))
                RequirementBanner(missingRequirement) { actions.onGrantRequirement(missingRequirement) }
            }

            if (showFields) {
                Spacer(Modifier.size(10.dp))
                val values = def.params.associate { it.key to (block.params[it.key] ?: it.defaultValue()) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (param in def.params) {
                        if (param.isHidden(values)) continue
                        val embedded = if (param is ParamDef.Text && param.imagePicker) embeddedImageOf(block.params[param.key]) else null
                        if (embedded != null) {
                            EmbeddedImageField(param, embedded) {
                                actions.onParamChange(param.key, cz.natsu.automation.data.richText(""))
                            }
                        } else {
                            ParamEditor(
                                param = param,
                                value = block.params[param.key] ?: param.defaultValue(),
                                chipStyle = chipStyle,
                                onChange = { actions.onParamChange(param.key, it) },
                                onRequestVariable = actions.onRequestVariable,
                            )
                        }
                        if (param is ParamDef.Text && param.imagePicker) {
                            ImagePickerRow { actions.onParamChange(param.key, it) }
                        }
                        if (param is ParamDef.Text && param.appPicker) {
                            SuggestionsRow(
                                ValueSuggestions(emptyList(), hint = tr("Nainstalované aplikace:"), app = AppValue.Package)
                            ) { value -> actions.onParamChange(param.key, cz.natsu.automation.data.richText(value)) }
                        }
                        if (param is ParamDef.Text && param.suggestions.isNotEmpty()) {
                            SuggestionsRow(ValueSuggestions(param.suggestions, hint = tr("Časté hodnoty:"))) { value ->
                                actions.onParamChange(param.key, cz.natsu.automation.data.richText(value))
                            }
                        }
                        if (param is ParamDef.Text && param.componentPicker) {
                            val app = (block.params["package"] as? ParamValue.Rich)?.segments
                                ?.filterIsInstance<cz.natsu.automation.data.Segment.Text>()?.joinToString("") { it.text }.orEmpty()
                            ComponentPickerRow(app) { target, className ->
                                actions.onParamChange(param.key, cz.natsu.automation.data.richText(className))
                                actions.onParamChange("target", ParamValue.Choice(target))
                            }
                        }
                        if (param.key == "right" && suggestions != null) {
                            SuggestionsRow(suggestions) { value ->
                                actions.onParamChange("right", cz.natsu.automation.data.richText(value))
                            }
                        }
                    }
                }
            }

            if (outputLabel != null && showFields) {
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("Výstup: "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(shape = RoundedCornerShape(6.dp), color = accent.container) {
                        Text(
                            outputLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = accent.content,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockMenu(def: BlockDef, actions: BlockCardActions, disabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = tr("Možnosti"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (actions.onAddElse != null) {
                DropdownMenuItem(
                    text = { Text(if (def.kind == BlockKind.TryStart) tr("Přidat větev Když selže") else tr("Přidat větev Jinak")) },
                    leadingIcon = { Icon(Icons.Rounded.AltRoute, null) },
                    onClick = { open = false; actions.onAddElse.invoke() }
                )
            }
            if (actions.onAddElseIf != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            when (def.type) {
                                cz.natsu.automation.engine.ControlBlocks.SWITCH,
                                cz.natsu.automation.engine.ControlBlocks.CASE -> tr("Přidat větev Když je")
                                cz.natsu.automation.engine.ControlBlocks.PARALLEL,
                                cz.natsu.automation.engine.ControlBlocks.BRANCH -> tr("Přidat souběžnou větev")
                                else -> tr("Přidat větev Jinak pokud")
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Rounded.AltRoute, null) },
                    onClick = { open = false; actions.onAddElseIf.invoke() }
                )
            }
            actions.onMoveUp?.let { moveUp ->
                DropdownMenuItem(
                    text = { Text(tr("Posunout nahoru")) },
                    leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                    onClick = { open = false; moveUp() }
                )
            }
            actions.onMoveDown?.let { moveDown ->
                DropdownMenuItem(
                    text = { Text(tr("Posunout dolů")) },
                    leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                    onClick = { open = false; moveDown() }
                )
            }
            if (def.kind != BlockKind.Else && def.kind != BlockKind.ElseIf && def.kind != BlockKind.Branch) {
                DropdownMenuItem(
                    text = { Text(tr("Duplikovat")) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { open = false; actions.onDuplicate() }
                )
            }
            if (actions.onToggleDisabled != null) {
                DropdownMenuItem(
                    text = { Text(if (disabled) tr("Zapnout krok") else tr("Vypnout krok")) },
                    leadingIcon = {
                        Icon(if (disabled) Icons.Rounded.PlayCircleOutline else Icons.Rounded.Block, null)
                    },
                    onClick = { open = false; actions.onToggleDisabled.invoke() }
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(tr("Odstranit"), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { open = false; actions.onRemove() }
            )
        }
    }
}

@Composable
private fun RequirementBanner(requirement: Requirement, onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr(requirement.title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    tr(requirement.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onGrant) { Text(tr("Povolit")) }
        }
    }
}

@Composable
private fun ParamLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun ParamEditor(
    param: ParamDef,
    value: ParamValue,
    chipStyle: (cz.natsu.automation.data.VarRef) -> ChipStyle,
    onChange: (ParamValue) -> Unit,
    onRequestVariable: (RichTextFieldController) -> Unit,
) {
    when (param) {
        is ParamDef.Text -> Column {
            ParamLabel(tr(param.label))
            val segments = (value as? ParamValue.Rich)?.segments ?: emptyList()
            Row(verticalAlignment = Alignment.CenterVertically) {
                RichTextField(
                    segments = segments,
                    onChange = { onChange(ParamValue.Rich(it)) },
                    styleOf = chipStyle,
                    modifier = Modifier.weight(1f),
                    placeholder = tr(param.placeholder),
                    allowVariables = !param.plain,
                    onRequestVariable = onRequestVariable,
                    code = param.code,
                )
                if (param.colorPicker) {
                    Spacer(Modifier.width(8.dp))
                    ColorSwatchButton(segments) { onChange(cz.natsu.automation.data.richText(it)) }
                }
            }
        }

        is ParamDef.Choice -> Column {
            ParamLabel(tr(param.label))
            val current = (value as? ParamValue.Choice)?.value ?: param.default
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(12.dp)) {
                    Text(tr(param.labelOf(current)))
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (option in param.options) {
                        DropdownMenuItem(
                            text = { Text(tr(option.label)) },
                            onClick = {
                                open = false
                                onChange(ParamValue.Choice(option.value))
                            }
                        )
                    }
                }
            }
        }

        is ParamDef.Automation -> Column {
            ParamLabel(tr(param.label))
            val automations = LocalAutomations.current
            val current = (value as? ParamValue.Choice)?.value.orEmpty()
            val selected = automations.firstOrNull { it.id.toString() == current }
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { open = true }, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        when {
                            selected != null -> selected.name + if (selected.isCurrent) tr(" (tato)") else ""
                            current.isNotEmpty() -> tr("Smazaná automatizace")
                            else -> tr("Vybrat automatizaci")
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (automation in automations) {
                        DropdownMenuItem(
                            text = { Text(automation.name + if (automation.isCurrent) tr(" (tato)") else "") },
                            onClick = {
                                open = false
                                onChange(ParamValue.Choice(automation.id.toString()))
                            }
                        )
                    }
                }
            }
        }

        is ParamDef.Number -> Column {
            val stored = (value as? ParamValue.Number)?.value ?: param.default
            var current by remember(stored) { mutableFloatStateOf(stored) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParamLabel(tr(param.label))
                Spacer(Modifier.weight(1f))
                Text(
                    formatNumber(current.toDouble()) + if (param.unit.isNotEmpty()) " ${param.unit}" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val steps = ((param.max - param.min) / param.step).toInt() - 1
            Slider(
                value = current,
                onValueChange = { current = (Math.round(it / param.step) * param.step) },
                onValueChangeFinished = { onChange(ParamValue.Number(current)) },
                valueRange = param.min..param.max,
                steps = steps.coerceIn(0, 200),
            )
        }

        is ParamDef.Toggle -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr(param.label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = (value as? ParamValue.Bool)?.value ?: param.default,
                onCheckedChange = { onChange(ParamValue.Bool(it)) }
            )
        }
    }
}

/** Quick check whether a rich value is empty – used for hints. */
fun ParamValue.isEmptyText(): Boolean =
    this is ParamValue.Rich && segments.all { it is Segment.Text && it.text.isBlank() }

/** Result of the step in a step-by-step run, selectable so it can be copied. */
@Composable
private fun StepResultBox(entry: cz.natsu.automation.engine.LogEntry) {
    val error = entry.error != null
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                when {
                    error -> tr("Chyba")
                    entry.skipped -> tr("Přeskočeno")
                    entry.output == null -> tr("Provedeno")
                    else -> tr("Výsledek")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
            )
            val detail = entry.error ?: entry.output?.takeUnless { entry.skipped }
            if (detail != null) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        detail.ifEmpty { tr("(prázdné)") }.take(2000),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
    }
}

/** (?) next to a block: its longer help in a dialog. */
@Composable
private fun HelpButton(title: String, help: String) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            Icons.AutoMirrored.Rounded.HelpOutline,
            contentDescription = tr("Nápověda"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (open) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(help, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text(tr("Rozumím")) } },
        )
    }
}
