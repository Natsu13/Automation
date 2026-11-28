package cz.natsu.automation.ui.list

import cz.natsu.automation.i18n.tr

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import cz.natsu.automation.data.AutomationTransfer
import cz.natsu.automation.data.ImportException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.CardButton
import cz.natsu.automation.data.HomeCard
import cz.natsu.automation.data.HomeCards
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.ui.draw.alpha
import cz.natsu.automation.trigger.TriggerActivity
import cz.natsu.automation.ui.editor.AutomationBadge
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AutomationListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AutomationApplication).repository

    val automations: StateFlow<List<AutomationEntity>?> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(repository.create(tr("Nová automatizace"))) }
    }

    fun createExamples(chosen: List<Examples.Example>, onDone: (Int) -> Unit = {}) {
        viewModelScope.launch { onDone(Examples.create(repository, chosen)) }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch { repository.duplicate(id) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun setPaused(id: Long, paused: Boolean) {
        viewModelScope.launch { repository.setPaused(id, paused) }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(id, pinned) }
    }

    /** Cards put on the home screen by running automations. */
    val cards: StateFlow<List<HomeCard>> = HomeCards.cards

    var news by mutableStateOf(News.visible(application))
        private set

    fun dismissNews(id: String) {
        News.dismiss(getApplication(), id)
        news = News.visible(getApplication())
    }

    /** The button of a news item: adds its example automation and closes the news. */
    fun newsAction(item: News.Item, onDone: (String) -> Unit) {
        val example = Examples.all.firstOrNull { it.name == item.example } ?: return
        createExamples(listOf(example)) { onDone(tr("Přidáno: %s", tr(example.name))) }
        dismissNews(item.id)
    }

    fun pressCard(card: HomeCard, button: CardButton) {
        val app = getApplication<Application>()
        HomeCards.press(app, card, button) { automationId, label ->
            viewModelScope.launch {
                val automation = repository.get(automationId)
                if (automation == null || automation.needsReview) {
                    android.widget.Toast.makeText(app, tr("Automatizace tlačítka není dostupná"), android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    cz.natsu.automation.trigger.EventDispatcher.runNow(app, automation, label, "Tlačítko karty")
                }
            }
        }
    }

    fun closeCard(card: HomeCard) {
        HomeCards.remove(getApplication(), card.id)
    }

    /** Shares the given automations (all of them when [ids] is null) as an export file. */
    fun share(context: android.content.Context, ids: Set<Long>? = null) {
        viewModelScope.launch {
            val chosen = repository.getAll().filter { ids == null || it.id in ids }
            AutomationTransfer.share(context, chosen)
        }
    }

    /** Imports pasted text: a script ("Show as script", one from an AI model) or an export file's JSON. */
    fun importText(text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(
                try {
                    val automations = AutomationTransfer.decode(text)
                    AutomationTransfer.importedMessage(AutomationTransfer.importAll(getApplication(), repository, automations))
                } catch (e: ImportException) {
                    e.message
                } catch (e: Exception) {
                    tr("Import se nepodařil: %s", e.message)
                }
            )
        }
    }

    fun import(uri: android.net.Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            onDone(
                try {
                    val imported = AutomationTransfer.import(getApplication(), repository, uri)
                    AutomationTransfer.importedMessage(imported)
                } catch (e: ImportException) {
                    e.message
                } catch (e: Exception) {
                    tr("Import se nepodařil: %s", e.message)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(viewModel: AutomationListViewModel, onOpen: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val automations by viewModel.automations.collectAsState()
    val cards by viewModel.cards.collectAsState()
    val news = viewModel.news
    var showExamples by remember { mutableStateOf(false) }
    if (showExamples) {
        ExamplesDialog(
            existing = automations.orEmpty().map { it.name }.toSet(),
            onDismiss = { showExamples = false },
            onCreate = { chosen ->
                showExamples = false
                viewModel.createExamples(chosen) { added ->
                    val message = if (added == 1) tr("Ukázka přidána") else tr("Přidáno ukázek: %s", added)
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
    var showPasteScript by remember { mutableStateOf(false) }
    if (showPasteScript) {
        PasteScriptDialog(
            onDismiss = { showPasteScript = false },
            onImport = { text ->
                showPasteScript = false
                viewModel.importText(text) { message ->
                    message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
                }
            },
        )
    }
    var toDelete by remember { mutableStateOf<AutomationEntity?>(null) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    // Newly added automations are at the top; scroll there once the list shows them.
    var scrollToTop by remember { mutableStateOf(false) }
    LaunchedEffect(automations?.size) {
        if (scrollToTop && !automations.isNullOrEmpty()) {
            scrollToTop = false
            listState.animateScrollToItem(0)
        }
    }
    // Background usage; read again whenever the list comes back on screen (sampled in MainActivity.onResume).
    var usage by remember { mutableStateOf<cz.natsu.automation.trigger.UsageMeter.Summary?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        usage = cz.natsu.automation.trigger.UsageMeter.summary(context)
        onPauseOrDispose { }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scrollToTop = true
            viewModel.import(uri) { message ->
                message?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(tr("Automatizace")) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { showExamples = true }) {
                        Icon(Icons.Rounded.AutoAwesome, tr("Přidat ukázkové automatizace"))
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr("Další možnosti")) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Importovat ze souboru")) },
                                leadingIcon = { Icon(Icons.Rounded.FileOpen, null) },
                                onClick = {
                                    menu = false
                                    importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Vložit skript")) },
                                leadingIcon = { Icon(Icons.Rounded.TextFields, null) },
                                onClick = { menu = false; showPasteScript = true }
                            )
                            DropdownMenuItem(
                                text = { Text(tr("Exportovat vše")) },
                                leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                enabled = !automations.isNullOrEmpty(),
                                onClick = { menu = false; viewModel.share(context) }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(tr("Nastavení")) },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = { menu = false; onOpenSettings() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.create(onOpen) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(tr("Nová")) }
            )
        }
    ) { padding ->
        val list = automations
        val newsAndCards: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
            items(news, key = { "news_" + it.id }) { item ->
                NewsCard(
                    item,
                    onAction = {
                        scrollToTop = true
                        viewModel.newsAction(item) { message ->
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDismiss = { viewModel.dismissNews(item.id) },
                )
            }
            items(cards, key = { "card_" + it.id }) { card ->
                HomeCardView(card, onButton = { viewModel.pressCard(card, it) }, onClose = { viewModel.closeCard(card) })
            }
        }
        when {
            list == null -> Unit
            list.isEmpty() && news.isEmpty() && cards.isEmpty() ->
                EmptyList(Modifier.padding(padding), onExamples = { showExamples = true })
            list.isEmpty() -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                newsAndCards()
                item(key = "empty") { EmptyList(Modifier.heightIn(min = 360.dp), onExamples = { showExamples = true }) }
            }
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                usage?.takeIf { it.high }?.let { summary ->
                    item(key = "usage_warning") { UsageWarning(summary, onOpenSettings) }
                }
                newsAndCards()
                val pinned = list.filter { it.pinned }
                val others = list.filterNot { it.pinned }
                if (pinned.isNotEmpty()) {
                    item(key = "header_pinned") { SectionHeader(tr("Připnuté")) }
                }
                for ((index, part) in listOf(pinned, others).withIndex()) {
                    if (index == 1 && pinned.isNotEmpty() && others.isNotEmpty()) {
                        item(key = "header_others") { SectionHeader(tr("Ostatní")) }
                    }
                    items(part, key = { it.id }) { automation ->
                        AutomationCard(
                            automation = automation,
                            onOpen = { onOpen(automation.id) },
                            onRun = { context.startActivity(TriggerActivity.runIntent(context, automation.id)) },
                            onDuplicate = { viewModel.duplicate(automation.id) },
                            onShare = { viewModel.share(context, setOf(automation.id)) },
                            onDelete = { toDelete = automation },
                            onPin = { viewModel.setPinned(automation.id, !automation.pinned) },
                            onPause = { viewModel.setPaused(automation.id, !automation.paused) },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { automation ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("Smazat „%s“?", automation.name)) },
            text = { Text(tr("Automatizace i všechny její kroky budou odstraněny.")) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(automation.id); toDelete = null }) {
                    Text(tr("Smazat"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("Zrušit")) } }
        )
    }
}

@Composable
private fun AutomationCard(
    automation: AutomationEntity,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onDuplicate: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onPause: () -> Unit,
) {
    val stepCount = remember(automation.blocksJson) { automation.blocks.size }
    Card(
        onClick = onOpen,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // A paused automation is shown faded, with a pause mark over its icon.
            Box(Modifier.alpha(if (automation.paused) 0.45f else 1f)) {
                AutomationBadge(automation.icon, size = 48.dp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).alpha(if (automation.paused) 0.6f else 1f)) {
                Text(
                    automation.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (automation.paused) {
                        Icon(Icons.Rounded.PauseCircle, null, Modifier.padding(end = 4.dp).size(16.dp), MaterialTheme.colorScheme.tertiary)
                    }
                    Text(
                        when {
                            automation.needsReview -> tr("čeká na kontrolu")
                            automation.paused -> tr("pozastaveno")
                            else -> stepsLabel(stepCount)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            automation.needsReview -> MaterialTheme.colorScheme.error
                            automation.paused -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    val iconMod = Modifier.padding(start = 8.dp).size(16.dp)
                    val tint = MaterialTheme.colorScheme.primary
                    if (automation.triggerProcessText) Icon(Icons.Rounded.TextFields, tr("Výběr textu"), iconMod, tint)
                    if (automation.triggerShare) Icon(Icons.Rounded.Share, tr("Sdílet"), iconMod, tint)
                    if (automation.triggerTile) Icon(Icons.Rounded.Tune, tr("Dlaždice"), iconMod, tint)
                    if (automation.triggerClipboard) Icon(Icons.Rounded.ContentPaste, tr("Změna schránky"), iconMod, tint)
                    for (event in automation.events.take(4)) {
                        Icon(
                            cz.natsu.automation.ui.editor.eventIcon(cz.natsu.automation.data.EventType.of(event.type)),
                            cz.natsu.automation.ui.editor.eventLabel(event), iconMod, tint
                        )
                    }
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, tr("Možnosti")) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (automation.pinned) tr("Odepnout") else tr("Připnout nahoru")) },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                        onClick = { menu = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (automation.paused) tr("Znovu zapnout") else tr("Pozastavit")) },
                        leadingIcon = { Icon(if (automation.paused) Icons.Rounded.PlayCircle else Icons.Rounded.PauseCircle, null) },
                        onClick = { menu = false; onPause() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Duplikovat")) },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; onDuplicate() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Sdílet (export)")) },
                        leadingIcon = { Icon(Icons.Rounded.Share, null) },
                        onClick = { menu = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Smazat"), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
            FilledTonalIconButton(onClick = onRun) { Icon(Icons.Rounded.PlayArrow, tr("Spustit")) }
        }
    }
}

private fun stepsLabel(count: Int) = when (count) {
    0 -> tr("Žádné kroky")
    1 -> tr("1 krok")
    in 2..4 -> tr("%s kroky", count)
    else -> tr("%s kroků", count)
}

@Composable
private fun EmptyList(modifier: Modifier, onExamples: () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Rounded.Bolt, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(tr("Žádné automatizace"), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Automatizace je řada kroků, které se spustí z aplikace, po označení textu, ") +
                    tr("přes Sdílet nebo dlaždicí v rychlém nastavení."),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onExamples) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Vytvořit ukázkové automatizace"))
        }
    }
}

/** Shown above the list when the app used more CPU in the background than it should. */
@Composable
private fun UsageWarning(summary: cz.natsu.automation.trigger.UsageMeter.Summary, onDetails: () -> Unit) {
    val worst = maxOf(summary.today.backgroundCpuMs, summary.yesterday.backgroundCpuMs)
    val day = if (summary.today.backgroundCpuMs >= summary.yesterday.backgroundCpuMs) tr("dnes") else tr("včera")
    androidx.compose.material3.Surface(
        onClick = onDetails,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.BatteryAlert, null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Vyšší spotřeba na pozadí"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    tr("Aplikace %s na pozadí použila %s procesoru. ", day, cz.natsu.automation.trigger.UsageMeter.formatCpu(worst)) +
                            tr("Klepni pro podrobnosti – nejvíc obvykle stojí sledování schránky."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Lets the user pick which ready-made automations to create. Ones that already
 * exist (same name) are marked; picking them again creates a copy.
 */
@Composable
private fun ExamplesDialog(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (List<Examples.Example>) -> Unit,
) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        title = { Text(tr("Ukázkové automatizace")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(Examples.all, key = { it.name }) { example ->
                    val checked = example.name in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { chosen = if (checked) chosen - example.name else chosen + example.name }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 8.dp))
                        AutomationBadge(example.icon, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr(example.name), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr(example.description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (example.name in existing || tr(example.name) in existing) {
                                Text(
                                    tr("Už ji máš – vytvoří se kopie"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(Examples.all.filter { it.name in chosen }) },
                enabled = chosen.isNotEmpty(),
            ) { Text(if (chosen.isEmpty()) tr("Vytvořit") else tr("Vytvořit (%s)", chosen.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}

/**
 * Pasting an automation written as a script: the text of "Show as script", or one an AI model wrote
 * from the guide on the web editor's site (with "# @name", "# @event", … lines in front).
 */
@Composable
internal fun PasteScriptDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    title: String = tr("Vložit skript"),
    hint: String = tr("Automatizace zapsaná jako skript – z „Zobrazit jako skript“, nebo od AI podle návodu na natsu13.github.io/Automation/ai-guide.md. Řádky # @ nastaví název a spuštění."),
    confirm: String = tr("Importovat"),
) {
    val context = LocalContext.current
    var text by remember {
        // A copied script is offered right away; anything else stays out of the field.
        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        mutableStateOf(if (clip.isNotBlank() && cz.natsu.automation.engine.ScriptReader.looksLikeScript(clip)) clip else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    placeholder = { Text("# @name \"…\"\ntoast(\"Ahoj \" & input)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zrušit")) } },
    )
}
