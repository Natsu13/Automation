package cz.natsu.automation.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.EventLog
import cz.natsu.automation.data.EventLogEntry
import cz.natsu.automation.i18n.tr

/** The debug log of events: a switch, and while it is on the events that came and what they started. */
@Composable
fun EventLogSection(resumeCounter: Int) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(EventLog.isEnabled(context)) }
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version, resumeCounter, enabled) { if (enabled) EventLog.all(context) else emptyList() }
    var showAll by remember { mutableStateOf(false) }

    Card(shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BugReport, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Ladicí záznam událostí"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Zapisuje každou událost, která do aplikace dorazila, co přinesla a které automatizace kvůli ní běžely."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    EventLog.setEnabled(context, it)
                })
            }
            if (enabled) {
                Text(
                    tr("Záznam zůstává jen v telefonu, ale jsou v něm i texty zpráv a oznámení. Vypnutím se smaže."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entries.isEmpty()) {
                    Text(
                        tr("Zatím nic nedorazilo. Vyvolej událost a vrať se sem."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                for (entry in if (showAll) entries else entries.take(12)) EventLogRow(entry)
                Row {
                    TextButton(onClick = { version++ }) { Text(tr("Obnovit")) }
                    if (entries.size > 12) TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) tr("Zobrazit méně") else tr("Zobrazit vše (%s)", entries.size))
                    }
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("event log", EventLog.text(entries)))
                            Toast.makeText(context, tr("Zkopírováno"), Toast.LENGTH_SHORT).show()
                        }) { Text(tr("Kopírovat")) }
                        TextButton(onClick = { EventLog.clear(context); version++ }) { Text(tr("Vymazat")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventLogRow(entry: EventLogEntry) {
    val timeFormat = remember { java.text.SimpleDateFormat("d. M. H:mm:ss", java.util.Locale.getDefault()) }
    val time = timeFormat.format(java.util.Date(entry.time))
    when (entry.kind) {
        EventLog.CAME -> Column {
            Text(tr(entry.event))
            Text(
                "$time · " + tr("čeká na ni automatizací: %s", entry.waiting),
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.waiting == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((key, value) in entry.values) {
                Text(
                    "$key = $value",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            val started = entry.kind == EventLog.STARTED
            Text(
                "$time · " + (if (started) tr("spuštěno: %s", entry.automation)
                else tr("podmínky spuštění nesplněny: %s", entry.automation)),
                style = MaterialTheme.typography.bodySmall,
                color = if (started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}
