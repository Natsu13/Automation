package cz.natsu.automation.trigger

import cz.natsu.automation.i18n.tr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.MainActivity
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.RunHistory
import cz.natsu.automation.data.TriggerType
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.EventInfo
import cz.natsu.automation.engine.RunResult
import cz.natsu.automation.engine.RunTrigger
import cz.natsu.automation.ui.editor.AutomationBadge
import cz.natsu.automation.ui.run.DialogRunUi
import cz.natsu.automation.ui.run.RunDialogs
import cz.natsu.automation.ui.theme.AutomationTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal sealed interface TriggerUiState {
    data object Loading : TriggerUiState
    data class Pick(val options: List<AutomationEntity>) : TriggerUiState
    data class Running(val automation: AutomationEntity) : TriggerUiState
    data class Empty(val message: String) : TriggerUiState
}

/**
 * Invisible activity that runs an automation from outside the app:
 * text selection menu (PROCESS_TEXT), share sheet (SEND), quick settings tile,
 * launcher / direct share shortcuts and the play button in the list.
 *
 * It has to be an activity: Android lets only the focused app read the clipboard.
 */
class TriggerActivity : ComponentActivity() {
    companion object {
        const val ACTION_RUN = "cz.natsu.automation.action.RUN"
        const val ACTION_TILE = "cz.natsu.automation.action.TILE"
        const val ACTION_CLIPBOARD = "cz.natsu.automation.action.CLIPBOARD"
        const val EXTRA_AUTOMATION_ID = "cz.natsu.automation.extra.AUTOMATION_ID"
        const val ACTION_EVENT = "cz.natsu.automation.action.EVENT"
        private const val EXTRA_INPUT = "cz.natsu.automation.extra.INPUT"
        private const val EXTRA_EVENT_LABEL = "cz.natsu.automation.extra.EVENT_LABEL"
        private const val EXTRA_EVENT_VALUES = "cz.natsu.automation.extra.EVENT_VALUES"

        /** Opened by [EventDispatcher] for an automation that needs dialogs or the clipboard. */
        fun eventIntent(context: Context, automationId: Long, input: String, event: EventInfo): Intent =
            Intent(context, TriggerActivity::class.java)
                .setAction(ACTION_EVENT)
                .putExtra(EXTRA_AUTOMATION_ID, automationId)
                .putExtra(EXTRA_INPUT, input)
                .putExtra(EXTRA_EVENT_LABEL, event.label)
                .putExtra(EXTRA_EVENT_VALUES, HashMap(event.values))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        fun runIntent(context: Context, automationId: Long): Intent =
            Intent(context, TriggerActivity::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_AUTOMATION_ID, automationId)

        /** [automationId] = a numbered tile that runs one automation; null = the general tile. */
        fun tileIntent(context: Context, automationId: Long? = null): Intent =
            Intent(context, TriggerActivity::class.java)
                .setAction(ACTION_TILE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .apply { if (automationId != null) putExtra(EXTRA_AUTOMATION_ID, automationId) }

        private const val ACTION_NOTIFICATION_RUN = "cz.natsu.automation.action.NOTIFICATION_RUN"
        private const val ACTION_NOTIFICATION_OPEN = "cz.natsu.automation.action.NOTIFICATION_OPEN"
        private const val ACTION_NOTIFICATION_SHARE = "cz.natsu.automation.action.NOTIFICATION_SHARE"
        private const val EXTRA_TEXT = "cz.natsu.automation.extra.TEXT"

        private fun notificationIntent(context: Context, action: String, notificationId: Int): Intent =
            Intent(context, TriggerActivity::class.java)
                .setAction(action)
                .putExtra(NotificationButtonReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

        /** Button "Spustit automatizaci" of the notification block. */
        fun notificationRunIntent(context: Context, notificationId: Int, automationId: Long, input: String): Intent =
            notificationIntent(context, ACTION_NOTIFICATION_RUN, notificationId)
                .putExtra(EXTRA_AUTOMATION_ID, automationId)
                .putExtra(EXTRA_INPUT, input)

        /** Button "Otevřít odkaz" of the notification block. */
        fun notificationOpenIntent(context: Context, notificationId: Int, url: String): Intent =
            notificationIntent(context, ACTION_NOTIFICATION_OPEN, notificationId).putExtra(EXTRA_TEXT, url)

        /** Button "Sdílet text" of the notification block. */
        fun notificationShareIntent(context: Context, notificationId: Int, text: String): Intent =
            notificationIntent(context, ACTION_NOTIFICATION_SHARE, notificationId).putExtra(EXTRA_TEXT, text)

        /** Opened by [BackgroundService] after the clipboard changed. */
        fun clipboardIntent(context: Context): Intent =
            Intent(context, TriggerActivity::class.java)
                .setAction(ACTION_CLIPBOARD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    private enum class Mode { ProcessText, Share, Tile, Clipboard, Event, Notification, Direct }

    private val repository by lazy { (application as AutomationApplication).repository }
    private val windowFocused = MutableStateFlow(false)
    private val runUi = DialogRunUi()
    private var state by mutableStateOf<TriggerUiState>(TriggerUiState.Loading)

    private var mode = Mode.Direct
    private var input: String? = null
    private var readOnly = true
    private var started = false
    private var event: EventInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleNotificationButton()) return

        var directId: Long? = null
        when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                mode = Mode.ProcessText
                input = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            }
            Intent.ACTION_SEND -> {
                mode = Mode.Share
                input = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                directId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)?.let(ShortcutPublisher::automationId)
            }
            ACTION_TILE -> {
                mode = Mode.Tile
                directId = intent.getLongExtra(EXTRA_AUTOMATION_ID, -1L).takeIf { it >= 0 }
            }
            ACTION_CLIPBOARD -> mode = Mode.Clipboard
            ACTION_NOTIFICATION_RUN -> {
                mode = Mode.Notification
                directId = intent.getLongExtra(EXTRA_AUTOMATION_ID, -1L).takeIf { it >= 0 }
                input = intent.getStringExtra(EXTRA_INPUT)
            }
            ACTION_EVENT -> {
                mode = Mode.Event
                directId = intent.getLongExtra(EXTRA_AUTOMATION_ID, -1L).takeIf { it >= 0 }
                input = intent.getStringExtra(EXTRA_INPUT)
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val values = intent.getSerializableExtra(EXTRA_EVENT_VALUES) as? HashMap<String, String>
                event = EventInfo(intent.getStringExtra(EXTRA_EVENT_LABEL) ?: "Událost", values.orEmpty())
            }
            else -> {
                mode = Mode.Direct
                directId = intent.getLongExtra(EXTRA_AUTOMATION_ID, -1L).takeIf { it >= 0 }
            }
        }

        setContent {
            AutomationTheme {
                TriggerContent(
                    state = state,
                    onPick = ::start,
                    onDismiss = ::finishCanceled,
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finishCanceled()
                    }
                )
                RunDialogs(runUi)
            }
        }

        if (mode == Mode.Clipboard) {
            runForClipboard()
            return
        }

        lifecycleScope.launch {
            if (mode == Mode.Direct || directId != null) {
                val automation = directId?.let { repository.get(it) }
                if (automation == null) {
                    state = TriggerUiState.Empty(tr("Automatizace už neexistuje."))
                } else if (automation.needsReview) {
                    state = TriggerUiState.Empty(
                        tr("„%s“ je importovaná a čeká na kontrolu. Otevři ji v aplikaci a povol ji.", automation.name)
                    )
                } else {
                    start(automation)
                }
                return@launch
            }

            val type = when (mode) {
                Mode.ProcessText -> TriggerType.ProcessText
                Mode.Share -> TriggerType.Share
                else -> TriggerType.Tile
            }
            val options = repository.withTrigger(type)
            if (options.isEmpty()) {
                state = TriggerUiState.Empty(emptyMessage(type))
                return@launch
            }
            // Conditions may read the clipboard, which needs window focus.
            windowFocused.first { it }
            val matching = matching(options)
            when (matching.size) {
                0 -> state = TriggerUiState.Empty(
                    tr("Žádná automatizace se na tohle nehodí – u všech se nesplnily podmínky spuštění.")
                )
                1 -> start(matching.first())
                else -> state = TriggerUiState.Pick(matching)
            }
        }
    }

    /**
     * Buttons of the notification block close the notification; "open link" and
     * "share" then only hand over to another app. Returns true when done.
     */
    private fun handleNotificationButton(): Boolean {
        val action = intent.action ?: return false
        if (!action.startsWith("cz.natsu.automation.action.NOTIFICATION_")) return false
        androidx.core.app.NotificationManagerCompat.from(this)
            .cancel(intent.getIntExtra(NotificationButtonReceiver.EXTRA_NOTIFICATION_ID, 0))
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val target = when (action) {
            ACTION_NOTIFICATION_OPEN -> Intent(Intent.ACTION_VIEW, android.net.Uri.parse(text))
            ACTION_NOTIFICATION_SHARE -> Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null
            )
            else -> return false
        }
        try {
            startActivity(target)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, tr("Žádná aplikace neumí otevřít \"%s\"", text), Toast.LENGTH_LONG).show()
        }
        finishCanceled()
        return true
    }

    /** How this activity was started, for the "Typ spuštění" variable and launch conditions. */
    private val runTrigger: RunTrigger
        get() = when (mode) {
            Mode.ProcessText -> RunTrigger.ProcessText
            Mode.Share -> RunTrigger.Share
            Mode.Tile -> RunTrigger.Tile
            Mode.Clipboard -> RunTrigger.Clipboard
            Mode.Event -> RunTrigger.Event
            Mode.Notification -> RunTrigger.NotificationButton
            Mode.Direct -> RunTrigger.Manual
        }

    /** Automations whose launch conditions hold for the current input. */
    private suspend fun matching(options: List<AutomationEntity>): List<AutomationEntity> =
        options.filter {
            AutomationRunner.conditionsMet(this, it.conditions, it.conditionsAny, input, runTrigger, automationId = it.id)
        }

    private fun emptyMessage(type: TriggerType) = when (type) {
        TriggerType.ProcessText -> tr("Žádná automatizace nemá zapnuté spuštění při výběru textu.")
        TriggerType.Share -> tr("Žádná automatizace nemá zapnuté spuštění přes Sdílet.")
        TriggerType.Tile -> tr("Žádná automatizace nemá zapnuté spuštění dlaždicí.")
        TriggerType.Clipboard -> tr("Žádná automatizace nemá zapnuté spuštění při změně schránky.")
    }

    /**
     * Clipboard trigger: once focused, read the clipboard and run every automation
     * that reacts to it, one after another, with the copied text as input.
     */
    private fun runForClipboard() {
        started = true
        lifecycleScope.launch {
            windowFocused.first { it }
            val text = getSystemService(ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this@TriggerActivity)?.toString()
            if (text.isNullOrEmpty() || !ClipboardMonitor.isNew(text)) {
                finishCanceled()
                return@launch
            }
            input = text
            for (automation in matching(repository.withTrigger(TriggerType.Clipboard))) {
                state = TriggerUiState.Running(automation)
                val startedAt = System.currentTimeMillis()
                val result = AutomationRunner.run(
                    this@TriggerActivity, automation.blocks, text, runUi, runTrigger, automationId = automation.id
                )
                RunHistory.record(this@TriggerActivity, automation, runTrigger.label, startedAt, result)
                if (!report(automation, result)) break
            }
            finishCanceled()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) windowFocused.value = true
    }

    private fun start(automation: AutomationEntity) {
        if (started) return
        started = true
        state = TriggerUiState.Running(automation)
        lifecycleScope.launch {
            // Wait for window focus so blocks can read the clipboard.
            windowFocused.first { it }
            val startedAt = System.currentTimeMillis()
            val result = AutomationRunner.run(
                this@TriggerActivity, automation.blocks, input, runUi, runTrigger, event, automationId = automation.id
            )
            RunHistory.record(
                this@TriggerActivity, automation, event?.label ?: runTrigger.label, startedAt, result
            )
            finishWith(automation, result)
        }
    }

    private fun finishWith(automation: AutomationEntity, result: RunResult) {
        val output = result.output
        if (result.success && output != null && mode == Mode.ProcessText && !readOnly) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, output))
            finish()
            return
        }
        report(automation, result)
        finishCanceled()
    }

    /**
     * Shows the outcome of a run: an error toast, or the output copied to the clipboard.
     * Returns false when the run failed or was cancelled.
     */
    private fun report(automation: AutomationEntity, result: RunResult): Boolean {
        if (result.cancelled) return false
        if (!result.success) {
            val hint = result.requirement?.let { tr("\nPovol v aplikaci: %s", it.title) } ?: ""
            Toast.makeText(this, "${automation.name}: ${result.error}$hint", Toast.LENGTH_LONG).show()
            return false
        }
        result.output?.let { output ->
            // Our own write must not start the clipboard automations again.
            ClipboardMonitor.markOwnWrite(output)
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(automation.name, output))
            Toast.makeText(this, tr("Výsledek zkopírován do schránky"), Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun finishCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerContent(
    state: TriggerUiState,
    onPick: (AutomationEntity) -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
) {
    when (state) {
        TriggerUiState.Loading -> Unit

        is TriggerUiState.Running -> Box(
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
          // The window covers the home screen, so the cards of the running automation are shown here too.
          val context = androidx.compose.ui.platform.LocalContext.current
          val allCards by cz.natsu.automation.data.HomeCards.cards.collectAsState()
          val cards = allCards.filter { it.automationId == state.automation.id }
          androidx.compose.foundation.layout.Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
          ) {
            for (card in cards) {
                cz.natsu.automation.ui.list.HomeCardView(
                    card,
                    onButton = { button ->
                        cz.natsu.automation.data.HomeCards.press(context, card, button) { id, label ->
                            val app = context.applicationContext as cz.natsu.automation.AutomationApplication
                            app.repository.appScope.launch {
                                app.repository.get(id)?.takeIf { !it.needsReview }?.let {
                                    EventDispatcher.runNow(app, it, label, "Tlačítko karty")
                                }
                            }
                        }
                    },
                    onClose = { cz.natsu.automation.data.HomeCards.remove(context, card.id) },
                )
            }
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 6.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutomationBadge(state.automation.icon, size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(state.automation.name, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(16.dp))
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
          }
        }

        is TriggerUiState.Pick -> ModalBottomSheet(onDismissRequest = onDismiss) {
            Text(
                tr("Spustit automatizaci"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn {
                items(state.options, key = { it.id }) { automation ->
                    ListItem(
                        headlineContent = { Text(automation.name) },
                        leadingContent = { AutomationBadge(automation.icon) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onPick(automation) }
                    )
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }

        is TriggerUiState.Empty -> ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(tr("Nic ke spuštění"), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(onClick = onDismiss) { Text(tr("Zavřít")) }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = onOpenApp) { Text(tr("Otevřít aplikaci")) }
                }
            }
        }
    }
}
