package cz.natsu.automation.ui.list

import cz.natsu.automation.i18n.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.CardButton
import cz.natsu.automation.data.Countdowns
import androidx.compose.runtime.getValue
import cz.natsu.automation.data.HomeCard
import cz.natsu.automation.data.HomeCards
import cz.natsu.automation.ui.common.IconCatalog
import cz.natsu.automation.ui.editor.AutomationBadge

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

/** A news item at the top of the home screen; × closes it for good. */
@Composable
internal fun NewsCard(item: News.Item, onAction: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    IconCatalog.icon(item.icon), null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (item.translated) item.title else tr(item.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, tr("Zavřít"), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                if (item.translated) item.text else tr(item.text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(end = 12.dp),
            )
            item.button?.let { label ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onAction) { Text(tr(label)) }
                }
            }
        }
    }
}

/** A card an automation put on the home screen (block "Karta na úvodu"). */
@Composable
internal fun HomeCardView(card: HomeCard, onButton: (CardButton) -> Unit, onClose: () -> Unit) {
    // A countdown ticks every second; other cards do not redraw.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), card.countdownEnd) {
        while (card.hasCountdown) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000 - value % 1000)
        }
    }
    val text = card.textAt(now)
    val progress: Float? = if (card.hasCountdown) {
        val start = card.countdownStart!!
        val end = card.countdownEnd!!
        ((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    } else card.progress
    val progressText = if (card.hasCountdown) {
        val left = (card.countdownEnd!! - now).coerceAtLeast(0)
        if (left > 0) tr("zbývá %s", Countdowns.clock(left)) else tr("Odpočet doběhl")
    } else card.progressText
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (card.icon.isNotEmpty()) {
                    Icon(
                        IconCatalog.icon(card.icon), null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                } else {
                    AutomationBadge(card.automationIcon, size = 36.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val title = card.titleAt(now)
                    if (title.isNotBlank()) {
                        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    if (text.isNotBlank()) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (card.closable) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, tr("Zavřít"), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
            progress?.let { progress ->
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(end = 12.dp)) {
                    if (progress < 0f) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(
                            progressText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        if (progress >= 0f) {
                            Text(
                                "${(progress * 100).toInt()} %",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
            if (card.buttons.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    card.buttons.forEachIndexed { index, button ->
                        val enabled = HomeCards.isLive(card, button)
                        // The last button is the main one.
                        if (index == card.buttons.lastIndex) {
                            androidx.compose.material3.Button(onClick = { onButton(button) }, enabled = enabled) { Text(button.label) }
                        } else {
                            TextButton(onClick = { onButton(button) }, enabled = enabled) { Text(button.label) }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
