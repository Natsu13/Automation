package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CallSplit
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
    const val SWITCH = "switch"
    const val CASE = "case"
    const val END_SWITCH = "end_switch"
    const val PARALLEL = "parallel"
    const val BRANCH = "parallel_branch"
    const val END_PARALLEL = "end_parallel"

    /** The "end" block type that closes the group started by [def]. */
    fun endTypeFor(def: BlockDef): String = when (def.type) {
        SWITCH -> END_SWITCH
        else -> Blocks.endTypeFor(def.kind)
    }

    /** The block type of another branch in the group started by a block of [startType]; null = no branches. */
    fun branchTypeFor(startType: String): String? = when (startType) {
        Blocks.IF -> Blocks.ELSE_IF
        SWITCH -> CASE
        PARALLEL -> BRANCH
        else -> null
    }

    /** Whether [value] is one of the lines of [options]; numbers are compared as numbers. */
    private fun matchesAny(value: String, options: String): Boolean {
        val wanted = Equivalents.variants(value.trim())
        val number = parseNumber(value.trim())
        return options.lines().map { it.trim() }.filter { it.isNotEmpty() }.any { option ->
            wanted.any { option.equals(it, ignoreCase = true) } || (number != null && parseNumber(option) == number)
        }
    }

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
            val id = choice(block, "automation").toLongOrNull() ?: throw BlockException(tr("není vybraná automatizace"))
            val repository = (context.applicationContext as? AutomationApplication)?.repository
                ?: throw BlockException(tr("automatizace nejsou dostupné"))
            val target = repository.get(id) ?: throw BlockException(tr("automatizace už neexistuje"))
            if (target.needsReview) {
                throw BlockException(tr("\"%s\" je importovaná a čeká na kontrolu – otevři ji a povol", target.name))
            }
            if (depth >= MAX_DEPTH) {
                throw BlockException(tr("automatizace se volají moc hluboko (spouští \"%s\" sama sebe?)", target.name))
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
            if (bool(block, "fail")) throw BlockException(tr("podmínka se do %s s nesplnila", formatNumber(number(block, "timeout").toDouble())))
            BlockResult.Output("false")
        },

        // ---------- choose by value ----------
        BlockDef(
            type = SWITCH,
            summary = "Vyber podle {value}",
            title = "Vyber podle",
            description = "Podle hodnoty spustí jednu z větví Když je. Přehlednější než řada Pokud / Jinak pokud, " +
                    "když porovnáváš pořád tu samou hodnotu. Další větve přidáš v nabídce ⋮.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.IfStart,
            params = listOf(ParamDef.Text("value", "Hodnota", defaultRef = VarRef.Input)),
        ) { block ->
            val value = text(block, "value")
            switchValues[block.groupId ?: block.id] = value
            // Never "passes": the run goes to the first "Když je" branch.
            BlockResult.Condition(false, value)
        },

        BlockDef(
            type = CASE,
            summary = "Když je {value}",
            title = "Když je",
            description = "Větev bloku Vyber podle: spustí se, když se hodnota rovná některému z řádků.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.ElseIf,
            params = listOf(ParamDef.Text("value", "Hodnota (víc možností = každá na řádek)", placeholder = "hodnota")),
            showInLibrary = false,
        ) { block ->
            val value = switchValues[block.groupId ?: ""] ?: throw BlockException(tr("větev není uvnitř bloku Vyber podle"))
            BlockResult.Condition(matchesAny(value, text(block, "value")))
        },

        BlockDef(
            type = END_SWITCH,
            summary = "Konec výběru",
            title = "Konec výběru",
            description = "",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

        // ---------- parallel ----------
        BlockDef(
            type = PARALLEL,
            summary = "Souběžně",
            title = "Souběžně",
            description = "Větve uvnitř běží najednou a pokračuje se, až doběhnou všechny – třeba dvě API volání " +
                    "místo čekání na jedno po druhém. Chyba v některé větvi je chyba celého bloku.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.ParallelStart,
        ),

        BlockDef(
            type = BRANCH,
            summary = "Zároveň",
            title = "Zároveň",
            description = "Začátek další větve bloku Souběžně.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.Branch,
            showInLibrary = false,
        ),

        BlockDef(
            type = END_PARALLEL,
            summary = "Konec souběžných větví",
            title = "Konec souběžných větví",
            description = "",
            category = BlockCategory.Control,
            icon = Icons.Rounded.CallSplit,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

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
