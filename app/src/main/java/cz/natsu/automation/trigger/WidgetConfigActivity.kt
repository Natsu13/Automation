package cz.natsu.automation.trigger

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.MainActivity
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.engine.WidgetBlocks
import cz.natsu.automation.engine.WidgetBuilder
import cz.natsu.automation.i18n.tr
import cz.natsu.automation.ui.common.IconCatalog
import cz.natsu.automation.ui.theme.AutomationTheme

/** Opened when a widget is put on the home screen: picks the automation the widget shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val repository = (application as AutomationApplication).repository
        val current = HomeWidgets.automationOf(this, widgetId)
        setContent {
            AutomationTheme {
                var automations by remember { mutableStateOf<List<AutomationEntity>?>(null) }
                LaunchedEffect(Unit) {
                    automations = repository.getAll().filter { HomeWidgets.rendersWidget(it) }
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp)) {
                        Header()
                        val list = automations
                        when {
                            list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            list.isEmpty() -> Empty()
                            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(list, key = { it.id }) { automation ->
                                    AutomationRow(automation, selected = automation.id == current) { choose(widgetId, automation) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Header() {
        Column(Modifier.padding(top = 24.dp, bottom = 16.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Widgets, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(tr("Co má widget ukazovat?"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("Widget se překreslí po přidání, změně velikosti, klepnutí a každých 30 minut."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun Empty() {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tr("Žádná automatizace nemá zapnutý spouštěč Widget na ploše. Zapni ho v automatizaci " +
                        "(Spouštěče) a do kroků dej blok Widget z bloků nebo Nastavit widget."),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Button(onClick = {
                startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java))
                finish()
            }) { Text(tr("Otevřít aplikaci")) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun AutomationRow(automation: AutomationEntity, selected: Boolean, onClick: () -> Unit) {
        val blocks = automation.blocks
        val kind = when {
            blocks.any { it.type == WidgetBuilder.BUILD } -> tr("Widget z bloků")
            blocks.any { it.type == WidgetBlocks.SET } -> tr("Widget z HTML")
            else -> tr("Zatím bez obsahu widgetu")
        }
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(IconCatalog.icon(automation.icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(automation.name, style = MaterialTheme.typography.titleMedium)
                    Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun choose(widgetId: Int, automation: AutomationEntity) {
        HomeWidgets.bind(this, widgetId, automation.id)
        HomeWidgets.showMessage(this, widgetId, tr("Načítání…"), automation.icon)
        HomeWidgets.refresh(this, intArrayOf(widgetId))
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
