package cz.natsu.automation.ui.editor

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.EventInfo
import cz.natsu.automation.engine.RunTrigger
import cz.natsu.automation.engine.WidgetBuilder
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.trigger.AutomationWidgetProvider
import cz.natsu.automation.trigger.HomeWidgets
import kotlinx.coroutines.launch

/** Settings of the "Widget na ploše" trigger: the debugging switch and the preview. */
@Composable
fun WidgetTriggerSettings(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf(false) }
    Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Hlásit velikost widgetu (ladění)"), style = MaterialTheme.typography.labelLarge)
            Text(
                tr("Oznámení, když widget naroste tak, že ho Android nemusí zvládnout ukázat."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = viewModel.widgetDebug, onCheckedChange = { viewModel.changeWidgetDebug(it) })
    }
    OutlinedButton(onClick = { preview = true }) {
        Icon(Icons.Rounded.Visibility, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("Náhled widgetu"))
    }
    }
    if (preview) WidgetPreviewDialog(viewModel) { preview = false }
}

/** Sizes offered by the preview, in dp (about as big as the cells of a usual home screen). */
private val previewSizes = listOf(
    Triple("2 × 2", 170, 180),
    Triple("4 × 2", 350, 180),
    Triple("4 × 3", 350, 280),
    Triple("2 × 1", 170, 80),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetPreviewDialog(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { mutableIntStateOf(0) }
    var dark by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var views by remember { mutableStateOf<RemoteViews?>(null) }
    var bytes by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val (_, width, height) = previewSizes[size]

    LaunchedEffect(size, dark, round) {
        running = true
        error = null
        var got: RemoteViews? = null
        val event = EventInfo(
            HomeWidgets.LABEL, mapOf(
                "widget_id" to "0", "widget_width" to width.toString(), "widget_height" to height.toString(),
                "dark_mode" to dark.toString(), "widget_action" to "",
            )
        )
        val result = AutomationRunner.run(
            context, viewModel.blocks.toList(), "", ui = null, trigger = RunTrigger.Event, event = event,
            automationId = viewModel.id, widgetPreview = { v, b -> got = v; bytes = b },
        )
        views = got
        error = result.error ?: if (got == null) tr("Automatizace nic nevykreslila – přidej blok Widget z bloků nebo Nastavit widget.") else null
        running = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Náhled widgetu"), Modifier.weight(1f))
                IconButton(onClick = { round++ }, enabled = !running) { Icon(Icons.Rounded.Refresh, tr("Vykreslit znovu")) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((index, option) in previewSizes.withIndex()) {
                        FilterChip(selected = index == size, onClick = { size = index }, label = { Text(option.first) })
                    }
                    FilterChip(selected = dark, onClick = { dark = !dark }, label = { Text(tr("Tmavý režim")) })
                }
                // A bit of a home screen behind the widget, so transparent corners look as they will.
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF3A4A8C), Color(0xFF1B1B2F))))
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width.dp, height.dp), contentAlignment = Alignment.Center) {
                        val shown = views
                        if (shown != null) {
                            AndroidView(
                                factory = { AppWidgetHostView(it) },
                                update = { host -> host.updateAppWidget(shown) },
                                modifier = Modifier.size(width.dp, height.dp),
                            )
                        }
                        if (running) CircularProgressIndicator()
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (views != null) SizeMeter(bytes)
                Text(
                    tr("Náhled automatizaci opravdu spustí (i její další kroky). Klepání v náhledu nefunguje."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            val shown = views
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && shown != null) {
                TextButton(onClick = {
                    scope.launch {
                        // Android 15+: the picker of widgets shows this picture instead of the general one.
                        val ok = AppWidgetManager.getInstance(context).setWidgetPreview(
                            ComponentName(context, AutomationWidgetProvider::class.java),
                            AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, shown,
                        )
                        Toast.makeText(
                            context,
                            if (ok) tr("Nabídka widgetů teď ukazuje tenhle náhled") else tr("Android teď náhled nepřijal (jde měnit jen párkrát za hodinu)"),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(tr("Použít v nabídce widgetů")) }
            }
            TextButton(onClick = onDismiss) { Text(tr("Zavřít")) }
        },
    )
}

/** How much of the limit the widget takes, with a word on what it means. */
@Composable
private fun SizeMeter(bytes: Int) {
    val part = bytes.toFloat() / WidgetBuilder.MAX_BYTES
    val color = when {
        bytes > WidgetBuilder.MAX_BYTES -> MaterialTheme.colorScheme.error
        bytes > WidgetBuilder.WARN_BYTES -> Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tr("Velikost: %s kB z nejvýš %s kB", formatKb(bytes), WidgetBuilder.MAX_BYTES / 1000),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(
            progress = { part.coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        val note = when {
            bytes > WidgetBuilder.MAX_BYTES -> tr("Moc velký – na plochu se nepošle. Zmenši seznam nebo obrázky.")
            bytes > WidgetBuilder.WARN_BYTES -> tr("Velký – Android ho nemusí zvládnout ukázat.")
            else -> null
        }
        note?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatKb(bytes: Int): String = if (bytes < 10_000) "%.1f".format(bytes / 1000f) else (bytes / 1000).toString()
