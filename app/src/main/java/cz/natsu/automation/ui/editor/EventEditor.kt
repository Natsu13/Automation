package cz.natsu.automation.ui.editor

import cz.natsu.automation.i18n.tr

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.AirplanemodeInactive
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.HeadsetOff
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.PowerOff
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.natsu.automation.data.EventGroup
import cz.natsu.automation.data.EventPermission
import cz.natsu.automation.data.EventTrigger
import cz.natsu.automation.data.EventType
import cz.natsu.automation.trigger.EventAlarms
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun eventIcon(type: EventType?): ImageVector = when (type) {
    EventType.WifiConnected -> Icons.Rounded.Wifi
    EventType.WifiDisconnected -> Icons.Rounded.WifiOff
    EventType.MobileConnected -> Icons.Rounded.SignalCellularAlt
    EventType.Offline -> Icons.Rounded.CloudOff
    EventType.AirplaneOn -> Icons.Rounded.AirplanemodeActive
    EventType.AirplaneOff -> Icons.Rounded.AirplanemodeInactive
    EventType.PowerConnected -> Icons.Rounded.Power
    EventType.PowerDisconnected -> Icons.Rounded.PowerOff
    EventType.BatteryBelow -> Icons.Rounded.BatteryAlert
    EventType.BatteryAbove -> Icons.Rounded.BatteryChargingFull
    EventType.ScreenOn -> Icons.Rounded.LightMode
    EventType.ScreenOff -> Icons.Rounded.DarkMode
    EventType.Unlocked -> Icons.Rounded.LockOpen
    EventType.BluetoothConnected -> Icons.Rounded.Bluetooth
    EventType.BluetoothDisconnected -> Icons.Rounded.BluetoothDisabled
    EventType.HeadsetPlugged -> Icons.Rounded.Headphones
    EventType.HeadsetUnplugged -> Icons.Rounded.HeadsetOff
    EventType.DndChanged -> Icons.Rounded.DoNotDisturbOn
    EventType.TimeDaily -> Icons.Rounded.Schedule
    EventType.TimeInterval -> Icons.Rounded.Repeat
    EventType.Notification -> Icons.Rounded.Notifications
    EventType.Boot -> Icons.Rounded.RestartAlt
    null -> Icons.Rounded.WarningAmber
}

/** Short text of an event with its settings, for summaries ("Baterie klesne pod 20 %"). */
fun eventLabel(event: EventTrigger): String {
    val type = EventType.of(event.type) ?: return event.type
    return when (type) {
        EventType.BatteryBelow, EventType.BatteryAbove -> "${tr(type.title)} ${event.param("percent")} %"
        EventType.TimeDaily -> tr("V %s", event.param("time")) + daysLabel(event.param("days")).let { if (it.isEmpty()) "" else " ($it)" }
        EventType.TimeInterval -> tr("Každých %s", intervalLabel(event.param("minutes").toIntOrNull() ?: 60))
        else -> tr(type.title)
    }
}

private val dayNames = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

private fun daysLabel(days: String): String {
    val set = days.mapNotNull { it.digitToIntOrNull() }.toSortedSet()
    return when {
        set.size == 7 -> ""
        set == sortedSetOf(1, 2, 3, 4, 5) -> "Po–Pá"
        set == sortedSetOf(6, 7) -> tr("víkend")
        set.isEmpty() -> tr("žádný den")
        else -> set.joinToString(" ") { dayNames[it - 1] }
    }
}

private val intervals = listOf(15, 30, 60, 120, 180, 360, 720, 1440)

private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 && minutes < 1440 -> "${minutes / 60} h"
    minutes == 1440 -> "24 h"
    else -> "$minutes min"
}

/** "Události" part of the automation settings sheet. */
@Composable
fun EventsSection(viewModel: EditorViewModel) {
    var picking by remember { mutableStateOf(false) }

    Text(tr("Události"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
    Text(
        tr("Automatizace se spustí sama, když se v telefonu něco stane. Hlavní hodnota události je ve Vstupu, ") +
                tr("další vrátí blok Údaje z události. V Podmínkách spuštění jsou k dispozici rovnou."),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (event in viewModel.events) {
            EventCard(event, viewModel)
        }
    }
    TextButton(onClick = { picking = true }) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("Přidat událost"))
    }

    if (picking) {
        EventPickerSheet(
            onPick = { type ->
                picking = false
                viewModel.addEvent(type)
            },
            onDismiss = { picking = false }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventCard(event: EventTrigger, viewModel: EditorViewModel) {
    val type = EventType.of(event.type)
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(eventIcon(type), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(tr(type?.title ?: tr("Neznámá událost")), style = MaterialTheme.typography.titleSmall)
                    if (type != null) {
                        Text(
                            tr(type.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { viewModel.removeEvent(event.id) }) {
                    Icon(Icons.Rounded.Close, tr("Odebrat událost"))
                }
            }

            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (type) {
                    EventType.BatteryBelow, EventType.BatteryAbove -> {
                        val stored = event.param("percent").toFloatOrNull() ?: 20f
                        var value by remember(stored) { mutableFloatStateOf(stored) }
                        Text(tr("Hranice: %s %%", value.toInt()), style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = value,
                            onValueChange = { value = it },
                            onValueChangeFinished = { viewModel.updateEvent(event.id, "percent", value.toInt().toString()) },
                            valueRange = 5f..100f,
                            steps = 18,
                        )
                    }

                    EventType.TimeDaily -> {
                        val (hour, minute) = EventAlarms.parseTime(event.param("time")) ?: (7 to 0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                TimePickerDialog(context, { _, h, m ->
                                    viewModel.updateEvent(event.id, "time", "%02d:%02d".format(h, m))
                                }, hour, minute, true).show()
                            }) {
                                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("%02d:%02d".format(hour, minute))
                            }
                        }
                        val days = event.param("days")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((i, name) in dayNames.withIndex()) {
                                val digit = (i + 1).toString()
                                val on = digit in days
                                FilterChip(
                                    selected = on,
                                    onClick = {
                                        val next = if (on) days.replace(digit, "") else (days + digit).toList().sorted().joinToString("")
                                        viewModel.updateEvent(event.id, "days", next)
                                    },
                                    label = { Text(name) },
                                )
                            }
                        }
                        NextRun(event)
                    }

                    EventType.TimeInterval -> {
                        val minutes = event.param("minutes").toIntOrNull() ?: 60
                        Text(tr("Každých:"), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (option in intervals) {
                                FilterChip(
                                    selected = option == minutes,
                                    onClick = { viewModel.updateEvent(event.id, "minutes", option.toString()) },
                                    label = { Text(intervalLabel(option)) },
                                )
                            }
                        }
                    }

                    else -> Unit
                }
                type?.permission?.let { PermissionHint(it) }
            }
        }
    }
}

@Composable
private fun NextRun(event: EventTrigger) {
    val next = EventAlarms.nextTime(event) ?: run {
        Text(tr("Vyber aspoň jeden den."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        return
    }
    Text(
        "Příště: " + SimpleDateFormat("EEEE d. M. H:mm", Locale.forLanguageTag("cs")).format(Date(next)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Warns about a missing permission of an event and offers to grant it. */
@Composable
private fun PermissionHint(permission: EventPermission) {
    val context = LocalContext.current
    var resumeCounter by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCounter++
        onPauseOrDispose { }
    }
    fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeCounter++ }

    val (missing, text, action) = remember(resumeCounter) {
        when (permission) {
            EventPermission.Location -> when {
                !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> Triple(
                    true, tr("Bez přístupu k poloze se nezjistí název Wi-Fi (událost samotná funguje)."),
                    "Povolit polohu" to { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> Triple(
                    true, tr("Aby šel název Wi-Fi zjistit i na pozadí, povol polohu „Vždy“."),
                    tr("Povolit vždy") to {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        } else {
                            launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                )
                else -> Triple(false, "", null)
            }
            EventPermission.Bluetooth ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) Triple(
                    true, tr("Potřebuje oprávnění Zařízení v okolí, jinak se o připojení nedozví."),
                    "Povolit" to { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                ) else Triple(false, "", null)
            EventPermission.NotificationAccess ->
                if (!cz.natsu.automation.trigger.NotificationEvents.hasAccess(context)) Triple(
                    true, tr("Potřebuje přístup k oznámením (v nastavení systému zapni u aplikace Automation)."),
                    tr("Otevřít nastavení") to { context.startActivity(cz.natsu.automation.trigger.NotificationEvents.settingsIntent()) }
                ) else Triple(false, "", null)
        }
    }
    if (!missing) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            action?.let { (label, onClick) ->
                FilledTonalButton(onClick = onClick, modifier = Modifier.padding(top = 6.dp)) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventPickerSheet(onPick: (EventType) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            tr("Přidat událost"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyColumn {
            for (group in EventGroup.entries) {
                val types = EventType.entries.filter { it.group == group }
                item(key = group.name) {
                    Text(
                        tr(group.title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                    )
                }
                items(types, key = { it.key }) { type ->
                    ListItem(
                        headlineContent = { Text(tr(type.title)) },
                        supportingContent = { Text(tr(type.description)) },
                        leadingContent = { Icon(eventIcon(type), null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onPick(type) }
                    )
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }
}
