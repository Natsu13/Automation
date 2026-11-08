package cz.natsu.automation.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.Blocks

/**
 * "Podmínky spuštění" above the steps. When the automation is started from the
 * share sheet, text selection, the tile or the clipboard, it is only offered if
 * these conditions hold; with a single matching automation no picker is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchConditionsSection(
    viewModel: EditorViewModel,
    chipStyle: (VarRef) -> ChipStyle,
    onRequestVariable: (blockId: String, RichTextFieldController) -> Unit,
) {
    val def = Blocks.get(Blocks.LAUNCH_CONDITION) ?: return
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FilterAlt, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Podmínky spuštění", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (viewModel.conditions.isEmpty()) {
                            "Žádné – automatizace se nabídne vždy."
                        } else {
                            "Nabídne se jen když platí. Vyhoví-li jediná automatizace, spustí se rovnou bez výběru."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (viewModel.conditions.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !viewModel.conditionsAny,
                        onClick = { viewModel.changeConditionsAny(false) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text("Musí platit všechny") }
                    SegmentedButton(
                        selected = viewModel.conditionsAny,
                        onClick = { viewModel.changeConditionsAny(true) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text("Stačí jedna") }
                }
            }

            for (condition in viewModel.conditions) {
                BlockCard(
                    block = condition,
                    def = def,
                    outputLabel = null,
                    active = false,
                    dragging = false,
                    missingRequirement = null,
                    chipStyle = chipStyle,
                    expanded = viewModel.isExpanded(condition.id),
                    onToggleExpanded = { viewModel.toggleExpanded(condition.id) },
                    outputStyle = null,
                    dragHandle = Modifier,
                    suggestions = if (viewModel.isExpanded(condition.id)) viewModel.suggestionsFor(condition) else null,
                    actions = BlockCardActions(
                        onParamChange = { key, value -> viewModel.updateParam(condition.id, key, value) },
                        onRequestVariable = { controller -> onRequestVariable(condition.id, controller) },
                        onDuplicate = { viewModel.duplicateCondition(condition.id) },
                        onRemove = { viewModel.removeCondition(condition.id) },
                        onMoveUp = { viewModel.moveCondition(condition.id, -1) },
                        onMoveDown = { viewModel.moveCondition(condition.id, 1) },
                        onAddElse = null,
                        onGrantRequirement = {},
                        onToggleDisabled = { viewModel.toggleDisabled(condition.id) },
                    ),
                )
            }

            TextButton(onClick = { viewModel.addCondition() }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Přidat podmínku")
            }
        }
    }
}
