package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HighlightOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewAgenda
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationIcons
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.CardButton
import cz.natsu.automation.data.Countdowns
import cz.natsu.automation.data.HomeCard
import cz.natsu.automation.data.HomeCards
import cz.natsu.automation.data.ParamValue

/**
 * Cards on the home screen of the app: a text, a progress bar and up to two buttons.
 * Showing, waiting for a button and removing are three separate blocks.
 */
object CardBlocks {
    const val SHOW = "home_card"
    const val WAIT = "home_card_wait"
    const val REMOVE = "home_card_remove"
    const val EDIT = "home_card_edit"

    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    private fun choiceNot(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value !in values
    }

    private fun textEmpty(key: String): (Map<String, ParamValue>) -> Boolean = { map ->
        when (val value = map[key]) {
            is ParamValue.Rich -> value.segments.isEmpty() ||
                    value.segments.all { it is cz.natsu.automation.data.Segment.Text && it.text.isBlank() }
            null -> true
            else -> false
        }
    }

    /** The card has a countdown (blocks from before it existed have none). */
    private val countdownOn: (Map<String, ParamValue>) -> Boolean = { map ->
        (map["countdown"] as? ParamValue.Choice)?.value.let { it != null && it != "none" }
    }

    private val actions = listOf(
        ChoiceOption(HomeCards.WAIT, "Odpovědět (blok Počkat na tlačítko karty)", "odpověď"),
        ChoiceOption(HomeCards.STOP, "Zastavit tento běh", "zastaví běh"),
        ChoiceOption(HomeCards.RUN, "Spustit automatizaci", "spustí automatizaci"),
        ChoiceOption(HomeCards.CLOSE, "Jen zavřít kartu", "zavře kartu"),
    )

    private fun buttonParams(n: Int): List<ParamDef> = listOf(
        ParamDef.Text("button$n", "Tlačítko $n", placeholder = "bez tlačítka"),
        ParamDef.Choice("action$n", "Co tlačítko $n udělá", actions).apply {
            hiddenWhen = textEmpty("button$n")
            hiddenInSummaryWhen = { true }
        },
        ParamDef.Automation("automation$n", "Automatizace pro tlačítko $n").apply {
            hiddenWhen = { textEmpty("button$n")(it) || choiceNot("action$n", HomeCards.RUN)(it) }
            hiddenInSummaryWhen = { true }
        },
        ParamDef.Text("input$n", "Vstup pro automatizaci (tlačítko $n)", placeholder = "text tlačítka").apply {
            hiddenWhen = { textEmpty("button$n")(it) || choiceNot("action$n", HomeCards.RUN)(it) }
            hiddenInSummaryWhen = { true }
        },
    )

    private fun countdownParams(): List<ParamDef> {
        val off: (Map<String, ParamValue>) -> Boolean = { !countdownOn(it) }
        return listOf(
            ParamDef.Choice(
                "countdown", "Odpočet", listOf(
                    ChoiceOption("none", "Bez odpočtu", ""),
                    ChoiceOption("in", "Za dobu (sekundy, 1:30 nebo 1:00:00)", "odpočet"),
                    ChoiceOption("at", "Do času (18:30)", "odpočet do času"),
                )
            ),
            ParamDef.Text("countdown_value", "Jak dlouho / do kdy", placeholder = "např. 45:00 nebo 18:30").apply {
                hiddenWhen = off
                hiddenInSummaryWhen = { true }
            },
            ParamDef.Toggle("end_remove", "Po doběhnutí kartu odebrat").apply { hiddenWhen = off },
        )
    }

    /** When the countdown of the block ends (ms), or null without one. */
    private suspend fun RunContext.countdownEnd(block: Block, now: Long): Long? {
        val value = text(block, "countdown_value").trim()
        return when (choice(block, "countdown")) {
            "in" -> now + (Countdowns.parseDuration(value)?.takeIf { it > 0 }
                ?: throw BlockException(tr("odpočet „%s“ není doba (sekundy, 1:30 nebo 1:00:00)", value)))
            "at" -> Countdowns.parseTime(value, now)
                ?: throw BlockException(tr("„%s“ není čas (např. 18:30)", value))
            else -> null
        }
    }

    private suspend fun RunContext.button(block: Block, n: Int): CardButton? {
        val label = text(block, "button$n").trim().takeIf { it.isNotEmpty() } ?: return null
        val action = choice(block, "action$n").ifEmpty { HomeCards.WAIT }
        val target = if (action == HomeCards.RUN) {
            choice(block, "automation$n").toLongOrNull()
                ?: throw BlockException(tr("tlačítko %s nemá vybranou automatizaci", n))
        } else null
        val input = if (action == HomeCards.RUN) text(block, "input$n").takeIf { it.isNotEmpty() } else null
        return CardButton(label, action, target, input)
    }

    private suspend fun RunContext.cardId(block: Block): String =
        text(block, "id").trim().ifEmpty { throw BlockException(tr("karta nemá název (id)")) }

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = SHOW,
            summary = "Karta {id}: {title} {countdown}",
            title = "Karta na úvodu",
            description = "Ukáže na úvodní obrazovce aplikace kartu s textem, průběhem a až dvěma tlačítky. " +
                    "Stejný název (id) kartu změní – třeba posune průběh. Karta zmizí, až běh skončí, pokud ji nenecháš. " +
                    "Může odpočítávat čas ({zbyva} v textu); co se má při odpočtu stát, naplánuj blokem Naplánovat spuštění.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.ViewAgenda,
            params = listOf(
                ParamDef.Text("id", "Název karty (id)", default = "karta", placeholder = "např. aktualizace"),
                ParamDef.Text("title", "Nadpis", placeholder = "{zbyva} = zbývající čas odpočtu"),
                ParamDef.Text("text", "Text", placeholder = "nepovinný; {zbyva} = zbývající čas odpočtu").apply { hiddenInSummaryWhen = { true } },
                ParamDef.Choice(
                    "progress", "Průběh", listOf(
                        ChoiceOption("none", "Bez průběhu", ""),
                        ChoiceOption("percent", "Procenta", "průběh"),
                        ChoiceOption("busy", "Neurčitý (pracuje se)", "pracuje se"),
                    )
                ).apply { hiddenWhen = countdownOn },
                ParamDef.Text("percent", "Hotovo (0–100 %)", default = "0").apply {
                    hiddenWhen = { choiceNot("progress", "percent")(it) || countdownOn(it) }
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Text("icon", "Ikona", placeholder = "ikona automatizace", iconPicker = true).apply {
                    hiddenInSummaryWhen = { true }
                },
            ) + buttonParams(1) + buttonParams(2) + countdownParams() + listOf(
                ParamDef.Toggle("closable", "Jde zavřít křížkem", default = true),
                ParamDef.Toggle("keep", "Nechat i po skončení běhu", summaryWhenOn = " (zůstane)").apply {
                    // A card with a countdown always stays: its alarms belong to it, not to the run.
                    hiddenWhen = countdownOn
                },
            ),
        ) { block ->
            val id = cardId(block)
            val progress = if (choice(block, "countdown").let { it.isNotEmpty() && it != "none" }) null else when (choice(block, "progress")) {
                "percent" -> {
                    val value = parseNumber(text(block, "percent"))
                        ?: throw BlockException(tr("\"Hotovo\" není číslo"))
                    (value.toFloat() / 100f).coerceIn(0f, 1f)
                }
                "busy" -> -1f
                else -> null
            }
            val now = System.currentTimeMillis()
            val end = countdownEnd(block, now)
            // The same countdown shown again (to change the text, say) keeps running from its start.
            val start = HomeCards.get(id)?.takeIf { end != null && it.countdownEnd == end }?.countdownStart ?: now
            val automation = automationId?.let {
                (context.applicationContext as? AutomationApplication)?.repository?.get(it)
            }
            HomeCards.show(
                context, HomeCard(
                    id = id,
                    title = text(block, "title"),
                    text = text(block, "text"),
                    icon = text(block, "icon").trim(),
                    progress = progress,
                    progressText = HomeCards.get(id)?.progressText?.takeIf { progress != null }.orEmpty(),
                    buttons = listOfNotNull(button(block, 1), button(block, 2)),
                    closable = bool(block, "closable"),
                    keep = bool(block, "keep") || end != null,
                    runId = runId,
                    automationId = automationId,
                    automationIcon = automation?.icon ?: AutomationIcons.DEFAULT,
                    countdownStart = end?.let { start },
                    countdownEnd = end,
                    removeAtEnd = end != null && bool(block, "end_remove"),
                )
            )
            BlockResult.None
        },

        BlockDef(
            type = EDIT,
            summary = "Upravit kartu {id}: {title}",
            title = "Upravit kartu",
            description = "Změní jen vyplněné části karty na úvodu – nadpis, text, ikonu nebo průběh. Odpočet, " +
                    "tlačítka a naplánovaná spuštění zůstanou. {zbyva} v textu ukazuje zbývající čas odpočtu.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.EditNote,
            params = listOf(
                ParamDef.Text("id", "Název karty (id)", default = "karta"),
                ParamDef.Text("title", "Nadpis", placeholder = "beze změny"),
                ParamDef.Text("text", "Text", placeholder = "beze změny; {zbyva} = zbývající čas odpočtu").apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text("icon", "Ikona", placeholder = "beze změny", iconPicker = true).apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Choice(
                    "progress", "Průběh", listOf(
                        ChoiceOption("keep", "Beze změny", ""),
                        ChoiceOption("none", "Bez průběhu", ""),
                        ChoiceOption("percent", "Procenta", "průběh"),
                        ChoiceOption("busy", "Neurčitý (pracuje se)", "pracuje se"),
                    )
                ).apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text("percent", "Hotovo (0–100 %)", default = "0").apply {
                    hiddenWhen = choiceNot("progress", "percent")
                    hiddenInSummaryWhen = { true }
                },
            ),
        ) { block ->
            val id = cardId(block)
            val title = text(block, "title")
            val body = text(block, "text")
            val icon = text(block, "icon").trim()
            val mode = choice(block, "progress").ifEmpty { "keep" }
            val percent = if (mode == "percent") {
                (parseNumber(text(block, "percent")) ?: throw BlockException(tr("\"Hotovo\" není číslo"))).toFloat()
            } else 0f
            val found = HomeCards.edit(context, id) { card ->
                card.copy(
                    title = title.ifEmpty { card.title },
                    text = body.ifEmpty { card.text },
                    icon = icon.ifEmpty { card.icon },
                    progress = when (mode) {
                        "none" -> null
                        "percent" -> (percent / 100f).coerceIn(0f, 1f)
                        "busy" -> -1f
                        else -> card.progress
                    },
                    progressText = if (mode == "keep") card.progressText else "",
                )
            }
            if (!found) throw BlockException(tr("karta \"%s\" není na úvodu", id))
            BlockResult.None
        },

        BlockDef(
            type = WAIT,
            summary = "Počkat na tlačítko karty {id}",
            title = "Počkat na tlačítko karty",
            description = "Počká, až na kartě na úvodu klepneš na tlačítko, které odpovídá. Výstupem je jeho text " +
                    "(prázdný, když kartu zavřeš křížkem).",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.TouchApp,
            params = listOf(ParamDef.Text("id", "Název karty (id)", default = "karta")),
            outputName = "Stisknuté tlačítko",
        ) { block ->
            val id = cardId(block)
            val card = HomeCards.get(id) ?: throw BlockException(tr("karta \"%s\" není na úvodu", id))
            if (card.buttons.none { it.action == HomeCards.WAIT } && !card.closable) {
                throw BlockException(tr("karta \"%s\" nemá tlačítko, které odpovídá", id))
            }
            BlockResult.Output(HomeCards.awaitPress(id))
        },

        BlockDef(
            type = REMOVE,
            summary = "Odebrat kartu {id}",
            title = "Odebrat kartu",
            description = "Odebere kartu z úvodní obrazovky (i takovou, která měla zůstat).",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.HighlightOff,
            params = listOf(ParamDef.Text("id", "Název karty (id)", default = "karta")),
        ) { block ->
            HomeCards.remove(context, cardId(block))
            BlockResult.None
        },
    )
}
