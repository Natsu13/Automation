package cz.natsu.automation.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.TriggerType
import cz.natsu.automation.trigger.BackgroundService
import cz.natsu.automation.trigger.ClipboardMonitor
import cz.natsu.automation.trigger.UsageMeter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageName = context.packageName

    // Permissions are granted outside the app; check again whenever the screen comes back.
    var resumeCounter by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumeCounter++
        onPauseOrDispose { }
    }
    val logAccess = remember(resumeCounter) { ClipboardMonitor.hasLogAccess(context) }
    val overlay = remember(resumeCounter) { ClipboardMonitor.canOverlay(context) }
    val notifications = remember(resumeCounter) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    var enabled by remember { mutableStateOf(ClipboardMonitor.isEnabled(context)) }
    var running by remember { mutableStateOf(ClipboardMonitor.running) }
    var automationCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(resumeCounter, enabled) {
        automationCount = (context.applicationContext as AutomationApplication).repository
            .withTrigger(TriggerType.Clipboard).size
        // The service starts asynchronously; refresh its state for a moment.
        repeat(6) {
            running = ClipboardMonitor.running
            delay(500)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        resumeCounter++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nastavení") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zpět") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UsageSection(resumeCounter)

            Text(
                "Sledování schránky",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Sledovat schránku", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    !logAccess || !overlay -> "Nejdřív dokonči kroky níže."
                                    enabled && running -> "Běží – po zkopírování textu se spustí automatizace."
                                    enabled -> "Zapnuto, ale neběží. Zkontroluj kroky níže."
                                    else -> "Vypnuto"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = enabled,
                            enabled = enabled || (logAccess && overlay),
                            onCheckedChange = {
                                enabled = it
                                ClipboardMonitor.setEnabled(context, it)
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Automatizace, které mají ve Spouštění zapnuté „Při změně schránky“, se spustí " +
                                "hned po zkopírování textu. Zkopírovaný text dostanou jako Vstup. " +
                                "Teď je takových automatizací: $automationCount.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Text(
                "Jak to zprovoznit",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Od Androidu 10 smí schránku číst jen aplikace, která je zrovna na obrazovce. " +
                        "Když se schránka změní, systém si to zapíše do svého logu. Aplikace ten log " +
                        "sleduje a na zlomek vteřiny otevře neviditelné okno, ve kterém schránku přečte.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Step(
                number = 1,
                done = logAccess,
                title = "Přístup k systémovému logu (přes ADB)",
            ) {
                Text(
                    "Tohle oprávnění jde udělit jen z počítače. Na telefonu zapni Možnosti pro vývojáře → " +
                            "Ladění přes USB (nebo Bezdrátové ladění), připoj ho k počítači s nástrojem adb a spusť:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Command("adb shell pm grant $packageName android.permission.READ_LOGS")
                Text(
                    "Android při tom aplikaci zavře – pak ji znovu otevři. Oprávnění vydrží i po restartu telefonu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Step(
                number = 2,
                done = overlay,
                title = "Zobrazení přes ostatní aplikace",
            ) {
                Text(
                    "Aby se mohlo z pozadí otevřít neviditelné okno, které schránku přečte.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!overlay) {
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    }) { Text("Povolit") }
                    Text(
                        "Nebo taky přes ADB:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Command("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Step(
                    number = 3,
                    done = notifications,
                    title = "Oznámení (nepovinné)",
                ) {
                    Text(
                        "Dokud sledování běží, Android vyžaduje trvalé oznámení. Bez povolení ho jen neuvidíš.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!notifications) {
                        FilledTonalButton(onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }) { Text("Povolit oznámení") }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(Modifier.padding(16.dp)) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        buildString {
                            append("Všechny příkazy najednou:\n")
                            append("adb shell pm grant $packageName android.permission.READ_LOGS\n")
                            append("adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                append(
                                    "\n\nAndroid 13+: po zapnutí se systém zeptá na přístup k logům zařízení – " +
                                            "zvol „Povolit jednorázový přístup“. Po restartu telefonu stačí aplikaci jednou otevřít."
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                append("\n\nPo každém přečtení ukáže Android krátkou zprávu, že aplikace vložila obsah ze schránky. To je normální.")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, done: Boolean, title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (done) "Hotovo" else "Chybí",
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(12.dp))
                Text("$number. $title", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable
private fun Command(command: String) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            SelectionContainer(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("adb", command))
                ClipboardMonitor.markOwnWrite(command)
                Toast.makeText(context, "Příkaz zkopírován", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Rounded.ContentCopy, "Kopírovat příkaz", Modifier.size(20.dp))
            }
        }
    }
}

/** Background service status, measured usage and the optional precise measurement. */
@Composable
private fun UsageSection(resumeCounter: Int) {
    val context = LocalContext.current
    val packageName = context.packageName
    var tick by remember { mutableIntStateOf(0) }
    val summary = remember(resumeCounter, tick) {
        UsageMeter.sample(context)
        UsageMeter.summary(context)
    }
    val serviceRunning = remember(resumeCounter, tick) { BackgroundService.serviceRunning }
    val listening = remember(resumeCounter, tick) { BackgroundService.listening }
    val ignoringOptimizations = remember(resumeCounter) {
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
    }
    LaunchedEffect(Unit) {
        // The service may still be starting when the screen opens.
        repeat(4) { delay(700); tick++ }
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (summary.high) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BatteryStd, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text("Běh na pozadí a spotřeba", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                when {
                    !serviceRunning -> "Na pozadí teď nic neběží – žádná automatizace nepotřebuje hlídat události ani schránku."
                    else -> "Běží: " + buildList {
                        if (ClipboardMonitor.active(context)) add("schránka")
                        addAll(listening.map { it.title.replaceFirstChar(Char::lowercaseChar) })
                    }.joinToString(", ") + "."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            UsageRow("Procesor na pozadí dnes", UsageMeter.formatCpu(summary.today.backgroundCpuMs))
            UsageRow("Procesor na pozadí včera", UsageMeter.formatCpu(summary.yesterday.backgroundCpuMs))
            UsageRow("V aplikaci dnes", UsageMeter.formatCpu(summary.today.foregroundCpuMs))
            UsageRow("Události / spuštění dnes", "${summary.today.events} / ${summary.today.runs}")
            Text(
                if (summary.high) {
                    "Víc než obvykle. Zkus vypnout sledování schránky nebo časté události (displej, oznámení)."
                } else {
                    "Měří se čas procesoru, který aplikace zabrala, když nebyla na obrazovce. " +
                            "Pod ${UsageMeter.formatCpu(UsageMeter.WARN_CPU_MS)} za den je to zanedbatelné; " +
                            "víc ukáže upozornění nahoře v seznamu."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!ignoringOptimizations && serviceRunning) {
                Text(
                    "Optimalizace baterie je pro aplikaci zapnutá. Na některých telefonech (Xiaomi, Samsung…) " +
                            "pak systém běh na pozadí ukončí – vypni ji, pokud události nechodí.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Nastavení optimalizace") }
            }
        }
    }

    PreciseCard(resumeCounter)
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PreciseCard(resumeCounter: Int) {
    val context = LocalContext.current
    val packageName = context.packageName
    var enabled by remember { mutableStateOf(UsageMeter.isPreciseEnabled(context)) }
    val access = remember(resumeCounter) { UsageMeter.hasPreciseAccess(context) }
    var result by remember { mutableStateOf<UsageMeter.PreciseResult?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled, access, resumeCounter, refresh) {
        result = null
        if (enabled && access) result = UsageMeter.readPrecise(context)
    }
    val czech = java.util.Locale.forLanguageTag("cs")

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QueryStats, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Přesné měření baterie", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Spotřeba v mAh přímo ze statistik Androidu (od posledního nabití).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    UsageMeter.setPreciseEnabled(context, it)
                })
            }
            if (enabled) {
                if (!access) {
                    Text(
                        "Statistiky baterie Android vydá jen s oprávněním, které jde udělit přes ADB " +
                                "(stejně jako přístup k logu). Připoj telefon k počítači a spusť:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Command("adb shell pm grant $packageName android.permission.DUMP")
                    Command("adb shell pm grant $packageName android.permission.PACKAGE_USAGE_STATS")
                    Command("adb shell pm grant $packageName android.permission.BATTERY_STATS")
                    Text(
                        "Android při tom aplikaci zavře – pak ji znovu otevři. Měří se jen když je tahle obrazovka " +
                                "otevřená, na pozadí kvůli tomu nic navíc neběží.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else when (val r = result) {
                    null -> Text("Načítám…", style = MaterialTheme.typography.bodyMedium)
                    is UsageMeter.PreciseResult.Failed -> Text(
                        "Nepodařilo se načíst: ${r.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    is UsageMeter.PreciseResult.Ok -> {
                        val v = r.value
                        fun mah(x: Double) = String.format(czech, "%.2f mAh", x)
                        val percent = v.backgroundPercent?.let { String.format(czech, " (%.2f %% baterie)", it) } ?: ""
                        UsageRow("Na pozadí", mah(v.backgroundMah) + percent)
                        UsageRow("Displej při používání aplikace", mah(v.screenMah))
                        UsageRow("Celkem", mah(v.totalMah))
                        Text(
                            "Od posledního odpojení nabíječky. „Na pozadí“ je to, co stojí sledování schránky a událostí.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { refresh++ }) { Text("Načíst znovu") }
                    }
                }
            }
        }
    }
}
