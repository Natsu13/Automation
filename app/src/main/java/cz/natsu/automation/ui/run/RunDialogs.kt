package cz.natsu.automation.ui.run

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import cz.natsu.automation.engine.RunUi
import kotlinx.coroutines.CompletableDeferred

/**
 * [RunUi] backed by Compose dialogs: a block suspends until the user answers
 * the dialog shown by [RunDialogs].
 */
class DialogRunUi : RunUi {
    sealed interface Request {
        val title: String

        data class Ask(override val title: String, val initial: String, val multiline: Boolean,
                       val answer: CompletableDeferred<String?>) : Request

        data class Choose(override val title: String, val options: List<String>,
                          val answer: CompletableDeferred<String?>) : Request

        data class Show(override val title: String, val text: String,
                        val answer: CompletableDeferred<Boolean>) : Request
    }

    var request by mutableStateOf<Request?>(null)
        private set

    private suspend fun <T> show(request: Request, answer: CompletableDeferred<T>): T {
        this.request = request
        return try {
            answer.await()
        } finally {
            if (this.request === request) this.request = null
        }
    }

    override suspend fun askText(title: String, initial: String, multiline: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Ask(title, initial, multiline, answer), answer)
    }

    override suspend fun choose(title: String, options: List<String>): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Choose(title, options, answer), answer)
    }

    override suspend fun showText(title: String, text: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        return show(Request.Show(title, text, answer), answer)
    }
}

/** Renders the dialog the running automation is currently waiting for. */
@Composable
fun RunDialogs(ui: DialogRunUi) {
    when (val request = ui.request) {
        null -> Unit
        is DialogRunUi.Request.Ask -> AskDialog(request)
        is DialogRunUi.Request.Choose -> ChooseDialog(request)
        is DialogRunUi.Request.Show -> ShowDialog(request)
    }
}

@Composable
private fun AskDialog(request: DialogRunUi.Request.Ask) {
    var value by remember(request) {
        mutableStateOf(TextFieldValue(request.initial, TextRange(0, request.initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = { request.answer.complete(null) },
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = !request.multiline,
                minLines = if (request.multiline) 3 else 1,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
            )
        },
        confirmButton = { TextButton(onClick = { request.answer.complete(value.text) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text("Zrušit") } },
    )
}

@Composable
private fun ChooseDialog(request: DialogRunUi.Request.Choose) {
    AlertDialog(
        onDismissRequest = { request.answer.complete(null) },
        title = { Text(request.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(request.options) { option ->
                    ListItem(
                        headlineContent = { Text(option) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clickable { request.answer.complete(option) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text("Zrušit") } },
    )
}

@Composable
private fun ShowDialog(request: DialogRunUi.Request.Show) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { request.answer.complete(true) },
        title = { Text(request.title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SelectionContainer { Text(request.text.ifEmpty { "(prázdné)" }, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { request.answer.complete(true) }) { Text("Pokračovat") } },
        dismissButton = {
            TextButton(onClick = {
                cz.natsu.automation.trigger.ClipboardMonitor.markOwnWrite(request.text)
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(request.title, request.text))
                Toast.makeText(context, "Zkopírováno", Toast.LENGTH_SHORT).show()
            }) { Text("Kopírovat") }
        },
    )
}
