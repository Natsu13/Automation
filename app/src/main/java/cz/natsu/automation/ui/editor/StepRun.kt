package cz.natsu.automation.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bottom bar of a step-by-step run: which step just ran, and buttons for the
 * next step, running to the end, stopping and looking at the variables. The
 * result of every executed step is also shown on its card.
 */
@Composable
fun StepRunBar(viewModel: EditorViewModel) {
    var showVariables by remember { mutableStateOf(false) }
    val paused = viewModel.pausedAt
    val result = viewModel.lastResult

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when {
                // Finished (or stopped).
                !viewModel.running && result != null -> {
                    val ok = result.success
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (result.cancelled) Icons.Rounded.StopCircle
                            else if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                            null,
                            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                result.cancelled -> "Krokování zastaveno"
                                ok -> "Hotovo · provedeno kroků: ${viewModel.stepCount}"
                                else -> "Chyba"
                            },
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    result.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    result.output?.let { output ->
                        Text("Výsledek:", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer {
                            Text(
                                output.ifEmpty { "(prázdné)" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        VariablesButton(viewModel) { showVariables = true }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { viewModel.closeSteps() }) { Text("Zavřít") }
                    }
                }

                // Waiting for "next".
                paused != null -> {
                    Text(
                        "Krok ${viewModel.stepCount}: ${paused.title}",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            paused.error != null -> "Chyba: ${paused.error}"
                            paused.output == null -> "Provedeno – výsledek je u kroku."
                            else -> "→ " + paused.output.ifEmpty { "(prázdné)" }.take(300).replace('\n', ' ')
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.stopRun() }) { Text("Stop") }
                        VariablesButton(viewModel) { showVariables = true }
                        Spacer(Modifier.weight(1f))
                        androidx.compose.material3.OutlinedIconButton(onClick = { viewModel.runToEnd() }) {
                            Icon(Icons.Rounded.FastForward, "Dokončit bez zastavování")
                        }
                        Button(onClick = { viewModel.nextStep() }) {
                            Icon(Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Další", maxLines = 1)
                        }
                    }
                }

                // A step is executing (or waits for a dialog).
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Běží…", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.stopRun() }) { Text("Stop") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (showVariables) {
        VariablesDialog(viewModel) { showVariables = false }
    }
}

@Composable
private fun VariablesButton(viewModel: EditorViewModel, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            if (viewModel.stepVariables.isEmpty()) "Proměnné" else "Proměnné (${viewModel.stepVariables.size})"
        )
    }
}

/** Values so far: named variables and the outputs of the executed steps. */
@Composable
private fun VariablesDialog(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    val outputs = viewModel.blocks.mapNotNull { block ->
        val entry = viewModel.stepResults[block.id] ?: return@mapNotNull null
        val label = cz.natsu.automation.engine.Blocks.get(block.type)?.outputName?.let { viewModel.outputLabel(block) }
        if (label == null || entry.output == null || entry.skipped) null else label to entry.output
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hodnoty po kroku ${viewModel.stepCount}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (viewModel.stepVariables.isEmpty() && outputs.isEmpty()) {
                    Text("Zatím žádné hodnoty.")
                }
                if (viewModel.stepVariables.isNotEmpty()) {
                    Text("Proměnné", style = MaterialTheme.typography.titleSmall)
                    for ((name, value) in viewModel.stepVariables) ValueRow(name, value)
                }
                if (outputs.isNotEmpty()) {
                    if (viewModel.stepVariables.isNotEmpty()) HorizontalDivider()
                    Text("Výstupy kroků", style = MaterialTheme.typography.titleSmall)
                    for ((label, value) in outputs) ValueRow(label, value)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

@Composable
private fun ValueRow(name: String, value: String) {
    Column {
        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        SelectionContainer {
            // Named variables are not shortened by the runner; huge texts would freeze the dialog.
            Text(
                cz.natsu.automation.engine.logPreview(value)!!.ifEmpty { "(prázdné)" },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
