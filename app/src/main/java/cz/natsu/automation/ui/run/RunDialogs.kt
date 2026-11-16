package cz.natsu.automation.ui.run

import cz.natsu.automation.i18n.tr

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
            override val title: String get() = tr("Vybrat soubor")
        }

        data class Images(override val title: String, val images: List<String>, val pick: Boolean,
                          val answer: CompletableDeferred<String?>) : Request

        data class Dictate(override val title: String, val language: String,
                           val answer: CompletableDeferred<String?>) : Request

        data class Photo(val answer: CompletableDeferred<String?>) : Request {
            override val title: String get() = tr("Vyfotit")
        }

        data class Web(override val title: String, val url: String, val script: String,
                       val answer: CompletableDeferred<String?>) : Request

        data class Tag(val message: android.nfc.NdefMessage, val answer: CompletableDeferred<String?>) : Request {
            override val title: String get() = tr("Zápis na NFC štítek")
        }
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

    override suspend fun dictate(prompt: String, language: String): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Dictate(prompt, language, answer), answer)
    }

    override suspend fun takePhoto(): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Photo(answer), answer)
    }

    override suspend fun showWeb(title: String, url: String, script: String): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Web(title, url, script, answer), answer)
    }

    override suspend fun writeTag(message: android.nfc.NdefMessage): String? {
        val answer = CompletableDeferred<String?>()
        return show(Request.Tag(message, answer), answer)
    }
}

private const val WEB_BRIDGE = "AutomationWindow"

/**
 * The user's script wrapped so it has done(value): a text goes as it is, anything else as JSON.
 * An error in the script only shows in the page's console.
 */
private fun webScript(script: String): String = """
    (function () {
        function done(value) {
            $WEB_BRIDGE.done(typeof value === 'string' ? value : value === undefined ? '' : JSON.stringify(value));
        }
        try {
            $script
        } catch (e) { console.error(e); }
    })();
""".trimIndent()

/**
 * A web page in a nearly full-screen dialog. Closing it (the button, or Back on the first page)
 * answers with the address the page was on; Back inside the page goes one page back.
 */
@Composable
private fun WebDialog(request: DialogRunUi.Request.Web) {
    var address by remember(request) { mutableStateOf(request.url) }
    var pageTitle by remember(request) { mutableStateOf("") }
    var loading by remember(request) { mutableStateOf(true) }
    var view by remember(request) { mutableStateOf<android.webkit.WebView?>(null) }
    fun close() {
        request.answer.complete(address)
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (view?.canGoBack() == true) view?.goBack() else close() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 32.dp),
        ) {
            Column {
                androidx.compose.foundation.layout.Row(
                    Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            request.title.ifBlank { pageTitle.ifBlank { tr("Web") } },
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            android.net.Uri.parse(address).host ?: address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    TextButton(onClick = { close() }) { Text(tr("Zavřít")) }
                }
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Only the web: no files of the phone or of the app.
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            if (request.script.isNotBlank()) {
                                // The only thing a page can do with the app: hand over a text and close the window.
                                addJavascriptInterface(object {
                                    @android.webkit.JavascriptInterface
                                    fun done(result: String?) {
                                        request.answer.complete(result.orEmpty())
                                    }
                                }, WEB_BRIDGE)
                            }
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: android.webkit.WebView, load: android.webkit.WebResourceRequest
                                ): Boolean {
                                    val scheme = load.url.scheme?.lowercase()
                                    if (scheme == "http" || scheme == "https") return false
                                    // An address for another app (a redirect after signing in, tel:, …)
                                    // is where the page ends: it is the answer.
                                    address = load.url.toString()
                                    close()
                                    return true
                                }

                                override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?) {
                                    address = url
                                    loading = true
                                }

                                override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                    address = url
                                    pageTitle = view.title.orEmpty()
                                    loading = false
                                    if (request.script.isNotBlank()) view.evaluateJavascript(webScript(request.script), null)
                                }

                                override fun doUpdateVisitedHistory(view: android.webkit.WebView, url: String, isReload: Boolean) {
                                    address = url
                                }
                            }
                            loadUrl(request.url)
                            view = this
                        }
                    },
                    onRelease = { it.stopLoading(); it.destroy() },
                )
            }
        }
    }
}

/**
 * Waits for a tag: while the dialog is open the phone gives every tag to this window
 * (no other app opens) and the first one gets the message written.
 */
@Composable
private fun WriteTag(request: DialogRunUi.Request.Tag) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
    }
    val adapter = remember { android.nfc.NfcAdapter.getDefaultAdapter(context) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    val ready = activity != null && adapter?.isEnabled == true
    androidx.compose.runtime.DisposableEffect(request) {
        if (ready) {
            try {
                adapter!!.enableReaderMode(
                    activity,
                    { tag ->
                        try {
                            request.answer.complete(cz.natsu.automation.engine.NfcTags.write(tag, request.message))
                        } catch (e: Exception) {
                            error = cz.natsu.automation.engine.NfcTags.reason(e)
                        }
                    },
                    android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_NFC_B or
                            android.nfc.NfcAdapter.FLAG_READER_NFC_F or android.nfc.NfcAdapter.FLAG_READER_NFC_V,
                    null,
                )
            } catch (e: Exception) {
                error = cz.natsu.automation.engine.NfcTags.reason(e)
            }
        }
        onDispose {
            try {
                if (ready) adapter!!.disableReaderMode(activity)
            } catch (_: Exception) {
            }
        }
    }
    AlertDialog(
        onDismissRequest = { request.answer.complete(null) },
        title = { Text(request.title) },
        text = {
            Column {
                Text(
                    when {
                        adapter == null -> tr("Tenhle telefon NFC nemá.")
                        !ready -> tr("NFC je vypnuté – zapni ho v nastavení telefonu.")
                        else -> tr("Přilož štítek k zadní straně telefonu a chvíli ho tam drž.")
                    }
                )
                error?.let {
                    Text(
                        tr("Nepovedlo se: %s. Zkus štítek přiložit znovu.", it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(tr("Zrušit")) } },
    )
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
        is DialogRunUi.Request.Dictate -> Dictate(request)
        is DialogRunUi.Request.Photo -> TakePhoto(request)
        is DialogRunUi.Request.Tag -> WriteTag(request)
        is DialogRunUi.Request.Web -> WebDialog(request)
    }
}

/** The system speech recognition window; its best guess is the answer. */
@Composable
private fun Dictate(request: DialogRunUi.Request.Dictate) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        request.answer.complete(
            result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        )
    }
    LaunchedEffect(request) {
        try {
            launcher.launch(
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(
                        android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    .apply {
                        if (request.title.isNotBlank()) putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, request.title)
                        if (request.language.isNotBlank()) putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, request.language)
                    }
            )
        } catch (e: Exception) {
            // No app that recognizes speech.
            request.answer.complete(null)
        }
    }
}

/** The camera app saves the photo into the app cache (old photos are cleaned up after a day). */
@Composable
private fun TakePhoto(request: DialogRunUi.Request.Photo) {
    val context = LocalContext.current
    val file = androidx.compose.runtime.remember(request) {
        val dir = java.io.File(context.cacheDir, "shared/photos").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
        java.io.File(dir, "foto-" + java.util.UUID.randomUUID().toString().take(6) + ".jpg")
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        request.answer.complete(if (saved) android.net.Uri.fromFile(file).toString() else null)
    }
    LaunchedEffect(request) {
        try {
            launcher.launch(androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file))
        } catch (e: Exception) {
            request.answer.complete(null)
        }
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
                                loaded.first -> Text(tr("Obrázek nejde načíst"), style = MaterialTheme.typography.bodySmall)
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
            if (!request.pick) TextButton(onClick = { request.answer.complete("") }) { Text(tr("Pokračovat")) }
        },
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(tr("Zrušit")) } },
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
        confirmButton = { TextButton(onClick = { request.answer.complete(value.text) }) { Text(tr("OK")) } },
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(tr("Zrušit")) } },
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
        dismissButton = { TextButton(onClick = { request.answer.complete(null) }) { Text(tr("Zrušit")) } },
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
                    request.text.take(20_000) + tr("\n… (zkráceno pro zobrazení, celkem %s znaků – Kopírovat vezme vše)", request.text.length)
                } else request.text
                SelectionContainer { Text(shown.ifEmpty { tr("(prázdné)") }, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { request.answer.complete(true) }) { Text(tr("Pokračovat")) } },
        dismissButton = {
            TextButton(onClick = {
                cz.natsu.automation.trigger.ClipboardMonitor.markOwnWrite(request.text)
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(request.title, request.text))
                Toast.makeText(context, tr("Zkopírováno"), Toast.LENGTH_SHORT).show()
            }) { Text(tr("Kopírovat")) }
        },
    )
}
