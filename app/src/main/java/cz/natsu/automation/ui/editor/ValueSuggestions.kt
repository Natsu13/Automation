package cz.natsu.automation.ui.editor

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which part of an app the app picker puts into the field. */
enum class AppValue { Label, Package }

/**
 * Values that make sense on the right side of a condition ("Porovnat s"), so the
 * user sees what the left value can be. [app] adds an app picker.
 */
data class ValueSuggestions(
    val values: List<String>,
    val hint: String? = null,
    val app: AppValue? = null,
)

/** Chips under "Porovnat s"; tapping one puts its value into the field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionsRow(suggestions: ValueSuggestions, onPick: (String) -> Unit) {
    var pickingApp by remember { mutableStateOf(false) }
    Column {
        Text(
            tr(suggestions.hint ?: tr("Možné hodnoty:")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (value in suggestions.values) {
                SuggestionChip(onClick = { onPick(value) }, label = { Text(value) })
            }
            if (suggestions.app != null) {
                AssistChip(
                    onClick = { pickingApp = true },
                    label = { Text(tr("Vybrat aplikaci…")) },
                    leadingIcon = { Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
    if (pickingApp && suggestions.app != null) {
        AppPickerSheet(
            onPick = { label, packageName ->
                pickingApp = false
                onPick(if (suggestions.app == AppValue.Package) packageName else label)
            },
            onDismiss = { pickingApp = false },
        )
    }
}

private data class AppEntry(val label: String, val packageName: String)

/** A screen, service or receiver of an app that other apps may start. [target] is the block's "target" value. */
private data class ComponentEntry(val target: String, val className: String, val label: String)

/** Button under the "class" field of the intent block: pick one of the chosen app's components. */
@Composable
fun ComponentPickerRow(app: String, onPick: (target: String, className: String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column {
        AssistChip(
            onClick = { picking = true },
            enabled = app.isNotBlank(),
            label = { Text(tr(if (app.isBlank()) tr("Nejdřív vyber aplikaci") else tr("Vybrat obrazovku / službu aplikace…"))) },
            leadingIcon = { Icon(Icons.Rounded.Apps, null, Modifier.size(18.dp)) },
        )
    }
    if (picking) {
        ComponentPickerSheet(
            app = app,
            onPick = { target, className ->
                picking = false
                onPick(target, className)
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComponentPickerSheet(app: String, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var components by remember { mutableStateOf<List<ComponentEntry>?>(null) }
    var packageName by remember { mutableStateOf(app.trim()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(app) {
        components = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val name = cz.natsu.automation.engine.AppBlocks.findPackage(context, app) ?: app.trim()
            packageName = name
            try {
                val info = pm.getPackageInfo(
                    name,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
                )
                buildList {
                    info.activities?.filter { it.exported }?.forEach {
                        add(ComponentEntry("activity", it.name, it.loadLabel(pm).toString()))
                    }
                    info.services?.filter { it.exported }?.forEach {
                        add(ComponentEntry("service", it.name, it.loadLabel(pm).toString()))
                    }
                    info.receivers?.filter { it.exported }?.forEach {
                        add(ComponentEntry("broadcast", it.name, it.loadLabel(pm).toString()))
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            tr("Co aplikace dovoluje spustit"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Text(
            tr("Obrazovky, služby a přijímače, které jsou přístupné zvenku. Jaké parametry (extras) čekají, " +
                    tr("Android neprozradí – ty najdeš jen v dokumentaci aplikace.")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(tr("Hledat")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        val list = components
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        } else if (list.isEmpty()) {
            Text(
                tr("Aplikace nic zvenku nedovoluje, nebo není nainstalovaná."),
                modifier = Modifier.padding(16.dp).navigationBarsPadding()
            )
        } else {
            val shown = list.filter { query.isBlank() || it.className.contains(query, true) || it.label.contains(query, true) }
            LazyColumn {
                for ((target, title) in listOf("activity" to tr("Obrazovky"), "service" to tr("Služby"), "broadcast" to tr("Přijímače zpráv"))) {
                    val group = shown.filter { it.target == target }
                    if (group.isEmpty()) continue
                    item(key = "header_$target") {
                        Text(
                            tr(title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(group, key = { it.target + it.className }) { component ->
                        ListItem(
                            headlineContent = { Text(component.className.removePrefix(packageName)) },
                            supportingContent = { Text(component.label) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.clickable { onPick(component.target, component.className) }
                        )
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

/** Installed apps with a launcher icon, searchable by name or package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(onPick: (label: String, packageName: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            tr("Vybrat aplikaci"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(tr("Hledat")) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        val list = apps
        if (list == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        } else {
            val shown = list.filter {
                query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            LazyColumn {
                items(shown, key = { it.packageName }) { app ->
                    ListItem(
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.packageName) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onPick(app.label, app.packageName) }
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}

/** An automation offered by the "Spustit automatizaci" button of a notification. */
data class AutomationRef(val id: Long, val name: String, val isCurrent: Boolean = false)

/** All automations, for [cz.natsu.automation.engine.ParamDef.Automation]. */
val LocalAutomations = androidx.compose.runtime.compositionLocalOf<List<AutomationRef>> { emptyList() }
