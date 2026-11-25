package cz.natsu.automation.ui.run

import android.annotation.SuppressLint
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import cz.natsu.automation.engine.AuthResult
import cz.natsu.automation.engine.HtmlWindow
import cz.natsu.automation.i18n.tr

/** Where the page seems to come from; nothing is ever loaded from it. */
private const val HTML_BASE = "https://automation.local/"

/**
 * The window a page is shown in: nearly the whole screen, or ([sheet]) a panel that slides up
 * from the bottom to half of the screen. The panel is dragged only by its handle (the page
 * keeps its own scrolling): up to the whole screen, down to half, further down closes it;
 * a tap on the handle switches between half and whole.
 */
@Composable
internal fun PageWindow(
    sheet: Boolean,
    secure: Boolean,
    onClose: () -> Unit,
    header: (@Composable () -> Unit)?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = sheet,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
        ),
    ) {
        if (!sheet) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 32.dp)
                    .imePadding(),
            ) {
                Column {
                    header?.invoke()
                    content()
                }
            }
            return@Dialog
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .imePadding()
                // A tap above the panel closes it, like outside any dialog.
                .pointerInput(Unit) { detectTapGestures { onClose() } },
            contentAlignment = Alignment.BottomCenter,
        ) {
            val full = maxHeight * 0.94f
            val half = maxHeight * 0.5f
            val density = LocalDensity.current
            var target by remember { mutableStateOf(half) }
            var dragging by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
            // Slides up from nothing when it opens.
            var opened by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { opened = true }
            val height by animateDpAsState(
                targetValue = dragging ?: if (opened) target else 0.dp,
                animationSpec = if (dragging != null) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                label = "sheet",
            )
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    // Taps on the panel stay on it.
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .clickable { target = if (target == full) half else full }
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val now = dragging ?: height
                                    dragging = (now - with(density) { delta.toDp() }).coerceIn(0.dp, full)
                                },
                                onDragStopped = { velocity ->
                                    val at = dragging ?: height
                                    dragging = null
                                    val flung = with(density) { velocity.toDp() }
                                    when {
                                        // Thrown down or dragged well under half: close.
                                        flung > 1500.dp || at < half * 0.6f -> onClose()
                                        flung < (-1500).dp || at > (half + full) / 2 -> target = full
                                        else -> target = half
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                        )
                    }
                    header?.invoke()
                    content()
                }
            }
        }
    }
}

/** The bar of a page window: title (and a line under it), maybe a spinner, and Close. */
@Composable
internal fun PageBar(title: String, subtitle: String?, loading: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        TextButton(onClick = onClose) { Text(tr("Zavřít")) }
    }
}

/**
 * The automation's own page. It can talk only to the automation (through [HtmlWindow.BRIDGE]);
 * files of the phone are out of its reach and the internet too, unless the block allows it.
 * Links open in the browser.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlWindowDialog(window: HtmlWindow) {
    val close = { window.close("") }
    PageWindow(
        sheet = window.sheet,
        secure = window.secure,
        onClose = close,
        header = if (window.title.isNotBlank() || window.bar) {
            { PageBar(window.title, null, false, close) }
        } else null,
    ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = !window.internet
                            addJavascriptInterface(object {
                                @android.webkit.JavascriptInterface
                                fun call(id: String?, action: String?, data: String?) =
                                    window.call(id.orEmpty(), action.orEmpty(), data.orEmpty())

                                @android.webkit.JavascriptInterface
                                fun close(value: String?) = window.close(value.orEmpty())
                            }, HtmlWindow.BRIDGE)
                            // alert(), confirm() and prompt() work as dialogs.
                            webChromeClient = android.webkit.WebChromeClient()
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: android.webkit.WebView, load: android.webkit.WebResourceRequest,
                                ): Boolean {
                                    val url = load.url
                                    // Jumps inside the page (#section) stay here.
                                    if (url.toString().startsWith(HTML_BASE)) return url.fragment == null
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    } catch (_: Exception) {
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: android.webkit.WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    window.attach(null)
                                }

                                override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                                    window.attach { js -> view.post { view.evaluateJavascript(js, null) } }
                                }
                            }
                            loadDataWithBaseURL(HTML_BASE, HtmlWindow.withShim(window.html), "text/html", "utf-8", null)
                        }
                    },
                    onRelease = {
                        window.attach(null)
                        it.stopLoading()
                        it.destroy()
                    },
                )
    }
}

/** The system fingerprint / face window; its answer completes the request. */
@Composable
internal fun Authenticate(request: DialogRunUi.Request.Auth) {
    val context = LocalContext.current
    DisposableEffect(request) {
        val cancel = CancellationSignal()
        val executor = context.mainExecutor
        fun answer(result: AuthResult) {
            request.answer.complete(result)
        }
        try {
            val builder = BiometricPrompt.Builder(context).setTitle(request.title.ifBlank { tr("Ověření") })
            if (request.subtitle.isNotBlank()) builder.setSubtitle(request.subtitle)
            val withPin = request.cipher == null && request.allowDeviceCredential && Build.VERSION.SDK_INT >= 29
            when {
                withPin && Build.VERSION.SDK_INT >= 30 -> builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                @Suppress("DEPRECATION")
                withPin -> builder.setDeviceCredentialAllowed(true)
                else -> {
                    if (Build.VERSION.SDK_INT >= 30) {
                        builder.setAllowedAuthenticators(
                            if (request.cipher != null) BiometricManager.Authenticators.BIOMETRIC_STRONG
                            else BiometricManager.Authenticators.BIOMETRIC_WEAK
                        )
                    }
                    builder.setNegativeButton(tr("Zrušit"), executor) { _, _ -> answer(AuthResult.Cancelled) }
                }
            }
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    answer(AuthResult.Ok(result.cryptoObject?.cipher ?: request.cipher))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    answer(
                        when (errorCode) {
                            BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> AuthResult.Cancelled
                            BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS -> AuthResult.Error(tr("v telefonu není nastavený otisk prstu"))
                            BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT, BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                                AuthResult.Error(tr("telefon nemá čtečku otisků (nebo není dostupná)"))
                            else -> AuthResult.Error(errString?.toString() ?: tr("ověření se nepovedlo"))
                        }
                    )
                }
            }
            val prompt = builder.build()
            if (request.cipher != null) prompt.authenticate(BiometricPrompt.CryptoObject(request.cipher), cancel, executor, callback)
            else prompt.authenticate(cancel, executor, callback)
        } catch (e: Exception) {
            answer(AuthResult.Error(e.message ?: tr("ověření se nepovedlo")))
        }
        onDispose { cancel.cancel() }
    }
}
