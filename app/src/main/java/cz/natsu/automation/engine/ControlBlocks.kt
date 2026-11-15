package cz.natsu.automation.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Report
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.delay

/** Script, calling another automation, waiting for a condition, failing on purpose, notes. */
object ControlBlocks {
    const val SCRIPT = "script"
    const val RUN_AUTOMATION = "run_automation"

    /** How deep "Spustit automatizaci" may nest (an automation calling itself would never end). */
    private const val MAX_DEPTH = 8

    private val conditionOps = listOf(
        ChoiceOption("equals", "je rovno"),
        ChoiceOption("not_equals", "není rovno"),
        ChoiceOption("contains", "obsahuje"),
        ChoiceOption("not_contains", "neobsahuje"),
        ChoiceOption("empty", "je prázdné"),
        ChoiceOption("not_empty", "není prázdné"),
        ChoiceOption("greater", "je větší než"),
        ChoiceOption("less", "je menší než"),
        ChoiceOption("greater_eq", "je větší nebo rovno"),
        ChoiceOption("less_eq", "je menší nebo rovno"),
    )

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = SCRIPT,
            summary = "Skript {code}",
            title = "Skript",
            description = "Několik řádků kódu místo mnoha bloků: výpočty, podmínky (a / nebo), opakování, " +
                    "práce s texty, seznamy a JSON. Čte i nastavuje proměnné automatizace. Nápověda pod ?.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Code,
            params = listOf(
                ParamDef.Text(
                    "code", "Kód",
                    placeholder = "celkem = cena * 1.21\npokud celkem > 500\n    vrat \"drahé\"\nkonec\nvrat round(celkem, 2)",
                    code = true,
                ),
            ),
            outputName = "Výsledek skriptu",
        ) { block -> BlockResult.Output(Script.text(script(block, "code"))) },

        BlockDef(
            type = RUN_AUTOMATION,
            summary = "Spustit {automation} se vstupem {input}",
            title = "Spustit automatizaci",
            description = "Spustí jinou automatizaci jako podprogram: předá jí vstup, počká a vrátí to, co " +
                    "vrátila blokem Vrátit výsledek. Tak jde jednu věc napsat jednou a použít víckrát.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.PlayCircle,
            params = listOf(
                ParamDef.Automation("automation", "Automatizace"),
                ParamDef.Text("input", "Vstup pro automatizaci", defaultRef = VarRef.Input),
            ),
            outputName = "Výsledek automatizace",
        ) { block ->
            val id = choice(block, "automation").toLongOrNull() ?: throw BlockException("není vybraná automatizace")
            val repository = (context.applicationContext as? AutomationApplication)?.repository
                ?: throw BlockException("automatizace nejsou dostupné")
            val target = repository.get(id) ?: throw BlockException("automatizace už neexistuje")
            if (target.needsReview) {
                throw BlockException("\"${target.name}\" je importovaná a čeká na kontrolu – otevři ji a povol")
            }
            if (depth >= MAX_DEPTH) {
                throw BlockException("automatizace se volají moc hluboko (spouští \"${target.name}\" sama sebe?)")
            }
            val result = AutomationRunner.run(
                context, target.blocks, text(block, "input"), ui, trigger, event,
                automationId = target.id, depth = depth + 1,
            )
            if (result.cancelled) throw RunCancelledException()
            result.error?.let { throw BlockException("${target.name}: $it", result.requirement) }
            BlockResult.Output(result.output ?: "")
        },

        BlockDef(
            type = "wait_until",
            summary = "Počkat, až {left} {op} {right} (nejdéle {timeout})",
            title = "Počkat dokud",
            description = "Čeká, dokud podmínka nezačne platit – třeba až se telefon připojí k Wi-Fi nebo se změní " +
                    "schránka. Hodnotu zjišťuje znovu každých pár sekund. Vrací true, nebo false po vypršení času.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.HourglassTop,
            params = listOf(
                ParamDef.Text("left", "Hodnota", placeholder = "vlož proměnnou, která se mění"),
                ParamDef.Choice("op", "Podmínka", conditionOps),
                ParamDef.Text("right", "Porovnat s", placeholder = "hodnota").apply {
                    hiddenWhen = { (it["op"] as? ParamValue.Choice)?.value in setOf("empty", "not_empty") }
                },
                ParamDef.Number("timeout", "Čekat nejdéle", 5f, 600f, 60f, step = 5f, unit = "s"),
                ParamDef.Number("interval", "Kontrolovat každých", 1f, 60f, 2f, unit = "s").apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Toggle("fail", "Chyba, když se nedočká", summaryWhenOn = ", jinak chyba"),
            ),
            outputName = "Dočkal se",
        ) { block ->
            val deadline = System.currentTimeMillis() + (number(block, "timeout") * 1000).toLong()
            val interval = (number(block, "interval") * 1000).toLong().coerceAtLeast(500)
            while (true) {
                // Variables like the clipboard, Wi-Fi name or battery are read again each time.
                if (Blocks.evaluateCondition(this, block)) return@BlockDef BlockResult.Output("true")
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                delay(minOf(interval, left))
            }
            if (bool(block, "fail")) throw BlockException("podmínka se do ${formatNumber(number(block, "timeout").toDouble())} s nesplnila")
            BlockResult.Output("false")
        },

        BlockDef(
            type = "fail",
            summary = "Zastavit s chybou {message}",
            title = "Zastavit s chybou",
            description = "Ukončí akci vlastní chybou. Uvnitř bloku Zkusit ji zachytí větev Když selže.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Report,
            params = listOf(ParamDef.Text("message", "Text chyby", default = "Něco se nepovedlo")),
        ) { block -> throw BlockException(text(block, "message").ifBlank { "zastaveno" }) },

        BlockDef(
            type = "comment",
            summary = "{text}",
            title = "Poznámka",
            description = "Nic nedělá – jen popisek, co následující kroky dělají.",
            category = BlockCategory.Control,
            icon = Icons.AutoMirrored.Rounded.Notes,
            params = listOf(ParamDef.Text("text", "Poznámka", placeholder = "co dělají další kroky", plain = true)),
        ) { BlockResult.None },
    )
}
