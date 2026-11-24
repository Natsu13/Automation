package cz.natsu.automation.data

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A button of a card on the home screen. */
@Serializable
data class CardButton(
    val label: String,
    /** [HomeCards.WAIT], [HomeCards.STOP], [HomeCards.RUN] or [HomeCards.CLOSE]. */
    val action: String,
    /** The automation [HomeCards.RUN] starts. */
    val automationId: Long? = null,
    /** Vstup of that automation; null = the label of the button. */
    val input: String? = null,
)

/** A card an automation put on the home screen (block "Karta na úvodu"). */
@Serializable
data class HomeCard(
    val id: String,
    val title: String,
    val text: String = "",
    /** Key of an icon from the icon catalogue; "" = the icon of the automation. */
    val icon: String = "",
    /** null = no progress bar, a negative number = busy without a known amount, else 0..1. */
    val progress: Float? = null,
    /** Small text under the progress bar ("12 / 25 MB"). */
    val progressText: String = "",
    val buttons: List<CardButton> = emptyList(),
    /** Shows a × that removes the card. */
    val closable: Boolean = true,
    /** Stays after the run that made it ended (and after the app restarts). */
    val keep: Boolean = false,
    /** The run that made the card; its buttons "wait" and "stop" talk to it. */
    val runId: String? = null,
    val automationId: Long? = null,
    val automationIcon: String = AutomationIcons.DEFAULT,
    /** A countdown on the card: when it started and ends (ms); the card draws the time left itself. */
    val countdownStart: Long? = null,
    val countdownEnd: Long? = null,
    /** The card goes when the countdown ends. */
    val removeAtEnd: Boolean = false,
) {
    val hasCountdown: Boolean get() = countdownStart != null && countdownEnd != null

    /** The text to show at [now], with {zbyva} filled in by the time left of the countdown. */
    fun textAt(now: Long): String = fill(text, now)

    fun titleAt(now: Long): String = fill(title, now)

    private fun fill(base: String, now: Long): String {
        val end = countdownEnd ?: return base
        val left = Countdowns.words((end - now).coerceAtLeast(0))
        return base.replace("{zbyva}", left).replace("{zbývá}", left).replace("{left}", left)
    }

    /** The moment [fraction] (0..1) of the countdown is gone. */
    fun countdownAt(fraction: Double): Long? {
        val start = countdownStart ?: return null
        val end = countdownEnd ?: return null
        return start + ((end - start) * fraction).toLong()
    }
}

/** Formats a time left for cards. */
object Countdowns {
    /** "1:05:03", "19:59", "0:45". */
    fun clock(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    /** "1 h 5 min", "20 min", "45 s" – for the text of a card. */
    fun words(ms: Long): String {
        val total = (ms + 999) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        return when {
            h > 0 -> if (m > 0) "$h h $m min" else "$h h"
            total >= 60 -> "${(total + 59) / 60} min"
            else -> "$total s"
        }
    }

    /** "90" or "1:30" or "1:00:00" as milliseconds; null = not a time. */
    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(":")
        if (text.isBlank() || parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            val number = part.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (number < 0) return null
            seconds = seconds * 60 + number
        }
        return (seconds * 1000).toLong().coerceAtMost(30L * 24 * 3600_000)
    }

    /** "18:30" as the next such moment (today, or tomorrow when it has passed). */
    fun parseTime(text: String, now: Long = System.currentTimeMillis()): Long? {
        val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(text.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, h)
            set(java.util.Calendar.MINUTE, min)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }
}

/**
 * Cards on the home screen. A card belongs to the run that made it and disappears with
 * it, unless it is kept; kept cards are saved and survive a restart of the app. The
 * buttons can answer a run that waits for them, stop the run or start an automation.
 */
object HomeCards {
    const val WAIT = "wait"
    const val STOP = "stop"
    const val RUN = "run"
    const val CLOSE = "close"

    private const val FILE = "home_cards.json"
    private val serializer = ListSerializer(HomeCard.serializer())

    private val state = MutableStateFlow<List<HomeCard>>(emptyList())
    val cards: StateFlow<List<HomeCard>> = state

    @Volatile
    private var loaded = false

    /** Answers for runs waiting in "Počkat na tlačítko karty", by card id. */
    private val answers = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Reads the kept cards once per process. Cards of runs from before are gone with their runs. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val saved = try {
                cz.natsu.automation.data.BlockJson.json.decodeFromString(serializer, file(context).readText())
            } catch (e: Exception) {
                emptyList()
            }
            state.value = saved.filter { it.keep }.map { it.copy(runId = null, progress = it.progress?.takeIf { p -> p >= 0f }) }
            loaded = true
            // Alarms are gone after a reboot; planning them again is harmless otherwise.
            for (card in state.value) cz.natsu.automation.trigger.CardTimers.schedule(context, card)
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun save(context: Context) {
        try {
            file(context).writeText(BlockJson.json.encodeToString(serializer, state.value.filter { it.keep }))
        } catch (e: Exception) {
            // Cards are only informative; a full disk must not break a run.
        }
    }

    fun get(id: String): HomeCard? = state.value.firstOrNull { it.id == id }

    /** Adds the card, or replaces the one with the same id in its place. */
    fun show(context: Context, card: HomeCard) {
        load(context)
        val before = get(card.id)
        state.update { list ->
            if (list.any { it.id == card.id }) list.map { if (it.id == card.id) card else it } else list + card
        }
        // A new look of the card starts a new question: an old unread answer does not count.
        answers[card.id]?.takeIf { it.isCompleted }?.let { answers.remove(card.id, it) }
        if (card.keep || before?.keep == true) save(context)
        cz.natsu.automation.trigger.CardTimers.schedule(context, card)
    }

    /** Changes only the progress of an existing card (used many times a second while downloading). */
    fun progress(id: String, progress: Float?, text: String) {
        state.update { list -> list.map { if (it.id == id) it.copy(progress = progress, progressText = text) else it } }
    }

    fun remove(context: Context, id: String) {
        val card = get(id) ?: return
        state.update { list -> list.filterNot { it.id == id } }
        // A run still waiting for this card goes on with an empty answer.
        answers.remove(id)?.complete("")
        if (card.keep) save(context)
        cz.natsu.automation.trigger.CardTimers.cancel(context, id)
        // Runs planned against the countdown of this card go with it.
        ScheduledRuns.dropForCard(context, id)
    }

    /** Changes only the given parts of an existing card ("Upravit kartu"); false when there is no such card. */
    fun edit(context: Context, id: String, change: (HomeCard) -> HomeCard): Boolean {
        val before = get(id) ?: return false
        val after = change(before)
        state.update { list -> list.map { if (it.id == id) after else it } }
        if (after.keep) save(context)
        return true
    }

    /** The run ended: its cards go, kept ones lose the buttons that talked to it. */
    fun runEnded(context: Context, runId: String) {
        val mine = state.value.filter { it.runId == runId }
        if (mine.isEmpty()) return
        state.update { list ->
            list.mapNotNull { card ->
                when {
                    card.runId != runId -> card
                    !card.keep -> null
                    else -> card.copy(
                        runId = null,
                        buttons = card.buttons.filter { it.action != WAIT && it.action != STOP },
                        progress = card.progress?.takeIf { it >= 0f },
                    )
                }
            }
        }
        for (card in mine) if (!card.keep) answers.remove(card.id)
        if (mine.any { it.keep }) save(context)
    }

    /** Whether [card]'s [button] still does something (the run it talks to may be gone). */
    fun isLive(card: HomeCard, button: CardButton): Boolean =
        (button.action != WAIT && button.action != STOP) || card.runId?.let { RunRegistry.isRunning(it) } == true

    /** A button of a card was tapped. */
    fun press(context: Context, card: HomeCard, button: CardButton, run: (Long, String) -> Unit) {
        when (button.action) {
            WAIT -> answers.getOrPut(card.id) { CompletableDeferred() }.complete(button.label)
            STOP -> card.runId?.let { RunRegistry.stop(it) }
            RUN -> button.automationId?.let { run(it, button.input ?: button.label) }
            else -> remove(context, card.id)
        }
    }

    /** Waits until a button of the card that answers is tapped; returns its label ("" when the card was closed). */
    suspend fun awaitPress(id: String): String {
        val answer = answers.getOrPut(id) { CompletableDeferred() }
        val label = answer.await()
        answers.remove(id, answer)
        return label
    }
}

/** Runs going on now, so the button of a card can stop the one that made it. */
object RunRegistry {
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    fun register(runId: String, job: kotlinx.coroutines.Job) {
        jobs[runId] = job
    }

    fun unregister(runId: String) {
        jobs.remove(runId)
    }

    fun isRunning(runId: String): Boolean = jobs[runId]?.isActive == true

    fun stop(runId: String) {
        jobs[runId]?.cancel()
    }
}
