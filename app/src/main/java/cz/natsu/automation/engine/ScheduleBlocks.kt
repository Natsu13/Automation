package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmAdd
import androidx.compose.material.icons.rounded.AlarmOff
import cz.natsu.automation.data.Countdowns
import cz.natsu.automation.data.HomeCards
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.ScheduledRun
import cz.natsu.automation.data.ScheduledRuns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Running an automation later: in a while, at a time, or at a point of the countdown of a
 * card. Each block plans one run, so a countdown can have as many milestones as needed.
 */
object ScheduleBlocks {
    const val SCHEDULE = "schedule_run"
    const val CANCEL = "schedule_cancel"

    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = SCHEDULE,
            summary = "Spustit {automation} {when} {value}",
            title = "Naplánovat spuštění",
            description = "Spustí automatizaci později se zvoleným Vstupem – za dobu, v čase, nebo v bodě odpočtu " +
                    "karty na úvodu (když zbývá 20:00, při 90 %). Funguje i se zavřenou aplikací. Víc milníků = víc " +
                    "těchto bloků. Plán svázaný s kartou zmizí s ní. Výstupem je čas spuštění.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.AlarmAdd,
            params = listOf(
                ParamDef.Automation("automation", "Spustit automatizaci"),
                ParamDef.Choice(
                    "when", "Kdy", listOf(
                        ChoiceOption("in", "Za dobu (sekundy, 1:30 nebo 1:00:00)", "za"),
                        ChoiceOption("at", "V čase (18:30)", "v"),
                        ChoiceOption("card_left", "Když na kartě zbývá (20:00)", "když na kartě zbývá"),
                        ChoiceOption("card_percent", "Při průběhu odpočtu karty (%)", "při % karty"),
                    )
                ),
                ParamDef.Text("value", "Hodnota", placeholder = "např. 20:00, 18:30 nebo 90"),
                ParamDef.Text("card", "Název karty (id)", default = "karta").apply {
                    hiddenWhen = { !choiceIs("when", "card_left", "card_percent")(it) }
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Text("input", "Vstup pro automatizaci", placeholder = "prázdný").apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Text("name", "Název plánu", placeholder = "nepovinný – stejný název plán nahradí").apply {
                    hiddenInSummaryWhen = { true }
                },
            ),
            outputName = "Čas spuštění",
        ) { block ->
            val target = choice(block, "automation").toLongOrNull() ?: throw BlockException(tr("není vybraná automatizace"))
            val value = text(block, "value").trim()
            val now = System.currentTimeMillis()
            var cardId: String? = null
            var cardEnd: Long? = null
            val at = when (val mode = choice(block, "when").ifEmpty { "in" }) {
                "at" -> Countdowns.parseTime(value, now) ?: throw BlockException(tr("„%s“ není čas (např. 18:30)", value))
                "card_left", "card_percent" -> {
                    val id = text(block, "card").trim().ifEmpty { throw BlockException(tr("karta nemá název (id)")) }
                    val card = HomeCards.get(id) ?: throw BlockException(tr("karta \"%s\" není na úvodu", id))
                    val end = card.countdownEnd ?: throw BlockException(tr("karta \"%s\" nemá odpočet", id))
                    cardId = id
                    cardEnd = end
                    if (mode == "card_left") {
                        end - (Countdowns.parseDuration(value)
                            ?: throw BlockException(tr("„%s“ není doba (sekundy, 1:30 nebo 1:00:00)", value)))
                    } else {
                        val percent = parseNumber(value.removeSuffix("%").trim())?.takeIf { it in 0.0..100.0 }
                            ?: throw BlockException(tr("„%s“ není průběh v procentech (0–100)", value))
                        card.countdownAt(percent / 100)!!
                    }
                }
                else -> now + (Countdowns.parseDuration(value)
                    ?: throw BlockException(tr("„%s“ není doba (sekundy, 1:30 nebo 1:00:00)", value)))
            }
            val name = text(block, "name").trim().ifEmpty { UUID.randomUUID().toString() }
            ScheduledRuns.add(context, ScheduledRun(name, at, target, text(block, "input"), cardId, cardEnd))
            BlockResult.Output(SimpleDateFormat("d. M. yyyy H:mm:ss", Locale.getDefault()).format(Date(at)))
        },

        BlockDef(
            type = CANCEL,
            summary = "Zrušit plán {name}",
            title = "Zrušit naplánované spuštění",
            description = "Zruší spuštění naplánované blokem Naplánovat spuštění podle názvu plánu. Vrací true, " +
                    "když takový plán byl.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.AlarmOff,
            params = listOf(ParamDef.Text("name", "Název plánu")),
            outputName = "Zrušeno",
        ) { block ->
            val name = text(block, "name").trim().ifEmpty { throw BlockException(tr("chybí název plánu")) }
            BlockResult.Output(ScheduledRuns.cancel(context, name).toString())
        },
    )
}
