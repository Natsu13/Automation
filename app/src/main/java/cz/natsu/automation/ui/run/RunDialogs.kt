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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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

        data class PickFile(val mime: String, val answer: CompletableDeferred<String?>) : Request {
            override val title: String get() = "Vybrat soubor"
        }

        data class Images(override val title: String, val images: List<String>, val pick: Boolean,
                          val answer: CompletableDeferred<String?>) : Request
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

    override suspend fun pickFile(mime: String): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.PickFile(mime, answer), answer)
    }

    override suspend fun showImages(title: String, images: List<String>, pick: Boolean): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Images(title, images, pick, answer), answer)
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
        is DialogRunUi.Request.PickFile -> PickFile(request)
        is DialogRunUi.Request.Images -> ImagesDialog(request)
    }
}

@Composable
private fun PickFile(request: DialogRunUi.Request.PickFile) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        request.answer.complete(uri?.toString())
    }
    LaunchedEffect(request) {
        try {
            launcher.launch(request.mime)
        } catch (e: Exception) {
            request.answer.complete(null)
        }
    }
}

@Composable
private fun ImagesDialog(request: DialogRunUi.Request.Images) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { request.answer.complete(if (request.pick) null else "") },
        title = { Text(request.title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(request.images) { source ->
                    // first = finished loading, second = the image (null when it failed)
                    val loaded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, source) {
                        value = true to ImageLoader.load(context, source)
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .then(if (request.pick) Modifier.clickable { request.answer.complete(source) } else Modifier)
                    ) {
                        val bitmap = loaded.second
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                bitmap != null -> Image(
                                    bitmap, contentDescription = source,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                loaded.first -> Text("Obrázek nejde načíst", style = MaterialTheme.typography.bodySmall)
                                else -> CircularProgressIndicator(Modifier.size(32.dp))
                            }
                        }
                        Text(
                            source, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!request.pick) TextButton(onClick = { request.answer.complete("") }) { Text("Pokračovat") }
        },
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text("Zrušit") } },
    )
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
                // Very long texts (e.g. raw API responses) would freeze the dialog; "Kopírovat" still copies all of it.
                val shown = if (request.text.length > 20_000) {
                    request.text.take(20_000) + "\n… (zkráceno pro zobrazení, celkem ${request.text.length} znaků – Kopírovat vezme vše)"
                } else request.text
                SelectionContainer { Text(shown.ifEmpty { "(prázdné)" }, modifier = Modifier.padding(top = 4.dp)) }
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
