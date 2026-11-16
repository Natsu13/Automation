package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FormatClear
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Output
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TextFormat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Catalog of all block types. */
object Blocks {
    private fun isExpert(values: Map<String, ParamValue>) = (values["mode"] as? ParamValue.Choice)?.value == "expert"

    const val IF = "if"
    const val ELSE = "else"
    const val ELSE_IF = "else_if"
    const val END_IF = "end_if"
    const val WHILE = "while"
    const val END_WHILE = "end_while"
    const val TRY = "try"
    const val CATCH = "catch"
    const val END_TRY = "end_try"

    /** The "end" block type that closes a group started by [kind]. */
    fun endTypeFor(kind: BlockKind): String = when (kind) {
        BlockKind.ParallelStart -> ControlBlocks.END_PARALLEL
        BlockKind.WhileStart -> END_WHILE
        BlockKind.TryStart -> END_TRY
        else -> END_IF
    }

    /** The "otherwise" block type for a group started by [kind]. */
    fun elseTypeFor(kind: BlockKind): String = if (kind == BlockKind.TryStart) CATCH else ELSE
    const val SET_VARIABLE = "set_variable"
    const val LAUNCH_CONDITION = "launch_condition"

    private val conditionOps = listOf(
        ChoiceOption("equals", "je rovno"),
        ChoiceOption("not_equals", "není rovno"),
        ChoiceOption("contains", "obsahuje"),
        ChoiceOption("not_contains", "neobsahuje"),
        ChoiceOption("starts", "začíná na"),
        ChoiceOption("ends", "končí na"),
        ChoiceOption("matches", "odpovídá regulárnímu výrazu"),
        ChoiceOption("empty", "je prázdné"),
        ChoiceOption("not_empty", "není prázdné"),
        ChoiceOption("greater", "je větší než"),
        ChoiceOption("less", "je menší než"),
        ChoiceOption("greater_eq", "je větší nebo rovno"),
        ChoiceOption("less_eq", "je menší nebo rovno"),
    )

    /**
     * What a launch condition checks. Launch conditions run before any step, so the
     * values of the event that started the automation are picked here, like in a
     * block, instead of being offered as variables. "custom" = the text field below.
     */
    val conditionSources = listOf(
        ChoiceOption("custom", "Vlastní hodnota (text a proměnné)", "vlastní hodnota"),
        ChoiceOption("input", "Vstup", "Vstup"),
        ChoiceOption("trigger", "Typ spuštění", "Typ spuštění"),
        ChoiceOption("wifi_name", "Název Wi-Fi", "Název Wi-Fi"),
        ChoiceOption("network", "Typ připojení", "Typ připojení"),
        ChoiceOption("battery", "Baterie (%)", "Baterie"),
        ChoiceOption("charging", "Nabíjí se", "Nabíjí se"),
        ChoiceOption("bt_device", "Bluetooth zařízení", "Bluetooth zařízení"),
        ChoiceOption("dnd", "Režim Nerušit", "Nerušit"),
        ChoiceOption("notif_app", "Oznámení – aplikace", "Aplikace oznámení"),
        ChoiceOption("notif_title", "Oznámení – nadpis", "Nadpis oznámení"),
        ChoiceOption("notif_text", "Oznámení – text", "Text oznámení"),
        ChoiceOption("notif_package", "Oznámení – balíček aplikace", "Balíček oznámení"),
        ChoiceOption("sms_sender", "SMS – odesílatel", "Odesílatel SMS"),
        ChoiceOption("sms_text", "SMS – text", "Text SMS"),
        ChoiceOption("call_number", "Hovor – číslo", "Číslo volajícího"),
        ChoiceOption("nfc_id", "NFC štítek – ID", "ID štítku"),
        ChoiceOption("nfc_text", "NFC štítek – text", "Text štítku"),
        ChoiceOption("app_package", "Aplikace – balíček", "Balíček aplikace"),
        ChoiceOption("app_name", "Aplikace – název", "Název aplikace"),
    )

    /** The source of a launch condition; null for "Pokud" and older conditions with a custom value. */
    fun conditionSource(values: Map<String, ParamValue>): String? =
        (values["source"] as? ParamValue.Choice)?.value?.takeIf { it != "custom" }

    /** Parameters shared by "Pokud" and the launch conditions. */
    private fun conditionParams() = listOf(
        ParamDef.Choice(
            "mode", "Zápis", listOf(
                ChoiceOption("simple", "Jednoduchý – hodnota a podmínka", ""),
                ChoiceOption("expert", "Výraz – a / nebo, čísla, funkce", ""),
            )
        ).apply { hiddenInSummaryWhen = { true } },
        ParamDef.Text("left", "Hodnota", defaultRef = VarRef.Input).apply {
            hiddenWhen = { conditionSource(it) != null || isExpert(it) }
        },
        ParamDef.Choice("op", "Podmínka", conditionOps).apply { hiddenWhen = { isExpert(it) } },
        ParamDef.Text("right", "Porovnat s", placeholder = "hodnota").apply {
            hiddenWhen = { isExpert(it) || (it["op"] as? ParamValue.Choice)?.value in setOf("empty", "not_empty") }
        },
        ParamDef.Text("expression", "Výraz", placeholder = "např. baterie < 20 nebo stav == \"vip\"").apply {
            hiddenWhen = { !isExpert(it) }
        },
    )

    /** Evaluates the left / op / right parameters of a condition block. */
    suspend fun evaluateCondition(ctx: RunContext, block: Block): Boolean {
        // Written as an expression of the Skript language: a / nebo, numbers, functions.
        if (ctx.choice(block, "mode") == "expert") return Script.truthy(ctx.script(block, "expression"))
        val left = when (val source = conditionSource(block.params)) {
            null -> ctx.text(block, "left")
            "input" -> ctx.resolve(VarRef.Input)
            "trigger" -> ctx.resolve(VarRef.Trigger)
            else -> ctx.resolve(VarRef.Event(source))
        }
        val right = ctx.text(block, "right")
        val op = ctx.choice(block, "op")
        fun check(left: String): Boolean = when (op) {
            "equals" -> left.equals(right, ignoreCase = true)
            "not_equals" -> !left.equals(right, ignoreCase = true)
            "contains" -> left.contains(right, ignoreCase = true)
            "not_contains" -> !left.contains(right, ignoreCase = true)
            "starts" -> left.startsWith(right, ignoreCase = true)
            "ends" -> left.endsWith(right, ignoreCase = true)
            "matches" -> try {
                Regex(right).containsMatchIn(left)
            } catch (e: Exception) {
                throw BlockException(tr("neplatný regulární výraz"))
            }
            "empty" -> left.isBlank()
            "not_empty" -> left.isNotBlank()
            "greater", "less", "greater_eq", "less_eq" -> {
                val a = parseNumber(left) ?: throw BlockException(tr("\"%s\" není číslo", left))
                val b = parseNumber(right) ?: throw BlockException(tr("\"%s\" není číslo", right))
                when (ctx.choice(block, "op")) {
                    "greater" -> a > b
                    "less" -> a < b
                    "greater_eq" -> a >= b
                    else -> a <= b
                }
            }
            else -> false
        }
        // "Sdílení" and "Sharing" are the same value (see Equivalents), whichever the app shows now.
        val results = Equivalents.variants(left).map { check(it) }
        return if (op == "not_equals" || op == "not_contains") results.all { it } else results.any { it }
    }

    private val yesNo = { value: Boolean -> if (value) "true" else "false" }

    val all: List<BlockDef> = listOf(
        // ---------- Text ----------
        BlockDef(
            type = "text",
            summary = "Text {text}",
            title = "Text",
            description = "Text, do kterého můžeš vložit proměnné. Výsledek můžeš použít v dalších krocích.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.TextFields,
            params = listOf(ParamDef.Text("text", "Text", placeholder = "Napiš text…")),
            outputName = "Text",
        ) { block -> BlockResult.Output(text(block, "text")) },

        BlockDef(
            type = "replace",
            summary = "Nahradit {find} za {replacement} v {source}{regex}",
            title = "Nahradit text",
            description = "Nahradí výskyty hledaného textu. Volitelně jako regulární výraz (\$1 = skupina).",
            category = BlockCategory.Text,
            icon = Icons.Rounded.FindReplace,
            params = listOf(
                ParamDef.Text("source", "V textu", defaultRef = VarRef.Input),
                ParamDef.Text("find", "Najít", placeholder = "co hledat"),
                ParamDef.Text("replacement", "Nahradit za", placeholder = "nic = smazat"),
                ParamDef.Toggle("regex", "Regulární výraz", summaryWhenOn = " (regex)"),
                ParamDef.Toggle("ignoreCase", "Ignorovat velikost písmen", default = true),
            ),
            outputName = "Upravený text",
        ) { block ->
            val source = text(block, "source")
            val find = text(block, "find")
            val replacement = text(block, "replacement")
            val ignoreCase = bool(block, "ignoreCase")
            if (find.isEmpty()) return@BlockDef BlockResult.Output(source)

            val result = if (bool(block, "regex")) {
                val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                val regex = try {
                    Regex(find, options)
                } catch (e: Exception) {
                    throw BlockException(tr("neplatný regulární výraz (%s)", e.message))
                }
                source.replace(regex, replacement)
            } else {
                source.replace(find, replacement, ignoreCase)
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "change_case",
            summary = "Změnit {source} na {mode}",
            title = "Změnit velikost písmen",
            description = "Převede text na velká, malá nebo počáteční velká písmena.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.TextFormat,
            params = listOf(
                ParamDef.Text("source", "Text", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "mode", "Na", listOf(
                        ChoiceOption("upper", "VELKÁ PÍSMENA"),
                        ChoiceOption("lower", "malá písmena"),
                        ChoiceOption("words", "Každé Slovo Velké"),
                        ChoiceOption("sentence", "První písmeno velké"),
                    )
                ),
            ),
            outputName = "Text",
        ) { block ->
            val source = text(block, "source")
            val locale = Locale.getDefault()
            val result = when (choice(block, "mode")) {
                "upper" -> source.uppercase(locale)
                "lower" -> source.lowercase(locale)
                "words" -> source.lowercase(locale).split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { it.titlecase(locale) }
                }
                else -> source.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "clean_text",
            summary = "{mode} v {source}",
            title = "Vyčistit text",
            description = "Odstraní mezery na začátku a konci, zdvojené mezery nebo zalomení řádků.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.FormatClear,
            params = listOf(
                ParamDef.Text("source", "Text", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "mode", "Co udělat", listOf(
                        ChoiceOption("trim", "Oříznout mezery na krajích"),
                        ChoiceOption("spaces", "Sloučit vícenásobné mezery"),
                        ChoiceOption("lines", "Spojit řádky do jednoho"),
                        ChoiceOption("all", "Všechno výše"),
                    )
                ),
            ),
            outputName = "Text",
        ) { block ->
            var value = text(block, "source")
            val mode = choice(block, "mode")
            if (mode == "lines" || mode == "all") value = value.replace(Regex("\\s*\\R\\s*"), " ")
            if (mode == "spaces" || mode == "all") value = value.replace(Regex("[ \\t]{2,}"), " ")
            if (mode == "trim" || mode == "all") value = value.trim()
            BlockResult.Output(value)
        },

        BlockDef(
            type = "count",
            summary = "Spočítat {what} v {source}",
            title = "Spočítat",
            description = "Spočítá znaky, slova nebo řádky v textu.",
            category = BlockCategory.Text,
            icon = Icons.Rounded.Numbers,
            params = listOf(
                ParamDef.Text("source", "Text", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "what", "Co", listOf(
                        ChoiceOption("chars", "Znaky", "znaky"),
                        ChoiceOption("words", "Slova", "slova"),
                        ChoiceOption("lines", "Řádky", "řádky"),
                    )
                ),
            ),
            outputName = "Počet",
        ) { block ->
            val source = text(block, "source")
            val count = when (choice(block, "what")) {
                "words" -> source.split(Regex("\\s+")).count { it.isNotBlank() }
                "lines" -> if (source.isEmpty()) 0 else source.lines().size
                else -> source.length
            }
            BlockResult.Output(count.toString())
        },

        BlockDef(
            type = "math",
            summary = "Vypočítat {a} {op} {b}{expression}",
            title = "Vypočítat",
            description = "Spočítá výsledek ze dvou čísel, nebo v režimu Expert celý výraz: " +
                    "závorky, mocniny, zaokrouhlení, min / max… (nápověda pod ?).",
            category = BlockCategory.Numbers,
            icon = Icons.Rounded.Calculate,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Zápis", listOf(
                        ChoiceOption("simple", "Jednoduchý – dvě čísla", ""),
                        ChoiceOption("expert", "Expert – výraz textem", ""),
                    )
                ).apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text("a", "Číslo", default = "0").apply { hiddenWhen = { isExpert(it) } },
                ParamDef.Choice(
                    "op", "Operace", listOf(
                        ChoiceOption("+", "+ sečíst", "+"),
                        ChoiceOption("-", "− odečíst", "−"),
                        ChoiceOption("*", "× násobit", "×"),
                        ChoiceOption("/", "÷ dělit", "÷"),
                        ChoiceOption("%", "zbytek po dělení", "mod"),
                    )
                ).apply { hiddenWhen = { isExpert(it) } },
                ParamDef.Text("b", "Číslo", default = "0").apply { hiddenWhen = { isExpert(it) } },
                ParamDef.Text("expression", "Výraz", placeholder = "např. round(cena * 1,21; 2)").apply {
                    hiddenWhen = { !isExpert(it) }
                },
            ),
            outputName = "Výsledek",
        ) { block ->
            val result = if (choice(block, "mode") == "expert") {
                // Rounding is part of the expression: round(x; places).
                Calc.evaluate(text(block, "expression"))
            } else {
                val a = parseNumber(text(block, "a")) ?: throw BlockException(tr("první hodnota není číslo"))
                val b = parseNumber(text(block, "b")) ?: throw BlockException(tr("druhá hodnota není číslo"))
                when (choice(block, "op")) {
                    "-" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0.0) throw BlockException(tr("dělení nulou")) else a / b
                    "%" -> if (b == 0.0) throw BlockException(tr("dělení nulou")) else a % b
                    else -> a + b
                }
            }
            BlockResult.Output(formatNumber(result))
        },

        // ---------- Clipboard ----------
        BlockDef(
            type = "get_clipboard",
            needsWindow = true,
            summary = "Získat obsah schránky",
            title = "Získat schránku",
            description = "Načte aktuální obsah schránky.",
            category = BlockCategory.Clipboard,
            icon = Icons.Rounded.ContentPaste,
            outputName = "Schránka",
        ) { BlockResult.Output(readClipboard()) },

        BlockDef(
            type = "set_clipboard",
            summary = "Zkopírovat {text} do schránky",
            title = "Nastavit schránku",
            description = "Zapíše text do schránky.",
            category = BlockCategory.Clipboard,
            icon = Icons.Rounded.ContentPasteGo,
            params = listOf(ParamDef.Text("text", "Text", defaultRef = VarRef.Input)),
        ) { block ->
            val value = text(block, "text")
            withContext(Dispatchers.Main) {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                    ?: throw BlockException(tr("schránka není dostupná"))
                cz.natsu.automation.trigger.ClipboardMonitor.markOwnWrite(value)
                clipboard.setPrimaryClip(ClipData.newPlainText("Automation", value))
            }
            BlockResult.None
        },

        // ---------- Variables ----------
        BlockDef(
            type = SET_VARIABLE,
            summary = "Nastavit {name} na {value}",
            title = "Nastavit proměnnou",
            description = "Uloží hodnotu pod jménem. Proměnnou pak vložíš do dalších kroků.",
            category = BlockCategory.Variables,
            icon = Icons.Rounded.DataObject,
            params = listOf(
                ParamDef.Text("name", "Název", default = "promenna", plain = true),
                ParamDef.Text("value", "Hodnota", defaultRef = VarRef.Input),
            ),
        ) { block ->
            val name = text(block, "name").trim()
            if (name.isEmpty()) throw BlockException(tr("proměnná nemá název"))
            variables[name] = text(block, "value")
            BlockResult.None
        },

        // ---------- Control ----------
        BlockDef(
            type = IF,
            summary = "Pokud {left} {op} {right}{expression}",
            title = "Pokud",
            description = "Spustí následující kroky jen když platí podmínka. Může mít větev Jinak.",
            category = BlockCategory.Control,
            icon = Icons.AutoMirrored.Rounded.AltRoute,
            kind = BlockKind.IfStart,
            params = conditionParams(),
        ) { block -> BlockResult.Condition(Blocks.evaluateCondition(this, block)) },

        BlockDef(
            type = WHILE,
            summary = "Opakuj dokud {left} {op} {right}{expression}",
            title = "Opakuj dokud",
            description = "Opakuje následující kroky, dokud platí podmínka.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Repeat,
            kind = BlockKind.WhileStart,
            params = conditionParams(),
        ) { block -> BlockResult.Condition(Blocks.evaluateCondition(this, block)) },

        BlockDef(
            type = LAUNCH_CONDITION,
            summary = "{source}{left} {op} {right}{expression}",
            title = "Podmínka spuštění",
            description = "Automatizace se nabídne, jen když podmínka platí.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.AltRoute,
            params = conditionParams().let { shared ->
                listOf(
                    shared.first(),
                    ParamDef.Choice("source", "Co kontrolovat", conditionSources, default = "custom").apply {
                        hiddenWhen = { isExpert(it) }
                        // In the summary "custom" is not shown – the text field's value is.
                        hiddenInSummaryWhen = { conditionSource(it) == null }
                    },
                ) + shared.drop(1)
            },
            showInLibrary = false,
        ) { block -> BlockResult.Condition(Blocks.evaluateCondition(this, block)) },

        BlockDef(
            type = ELSE,
            summary = "Jinak",
            title = "Jinak",
            description = "Kroky, které se spustí, když podmínka neplatí.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.AltRoute,
            kind = BlockKind.Else,
            showInLibrary = false,
        ),

        BlockDef(
            type = ELSE_IF,
            summary = "Jinak pokud {left} {op} {right}{expression}",
            title = "Jinak pokud",
            description = "Další podmínka, která se zkusí, když žádná předchozí neplatila.",
            category = BlockCategory.Control,
            icon = Icons.AutoMirrored.Rounded.AltRoute,
            kind = BlockKind.ElseIf,
            params = conditionParams(),
            showInLibrary = false,
        ) { block -> BlockResult.Condition(Blocks.evaluateCondition(this, block)) },

        BlockDef(
            type = END_IF,
            summary = "Konec podmínky",
            title = "Konec podmínky",
            description = "",
            category = BlockCategory.Control,
            icon = Icons.Rounded.DoneAll,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

        BlockDef(
            type = TRY,
            summary = "Zkusit {attempts}",
            title = "Zkusit",
            description = "Když některý krok uvnitř selže (třeba API nebo síť), akce neskončí: zkusí to znovu, " +
                    "nebo pokračuje větví Když selže. Výstup je text chyby (prázdný, když vše prošlo).",
            category = BlockCategory.Control,
            icon = Icons.Rounded.HealthAndSafety,
            kind = BlockKind.TryStart,
            params = listOf(
                ParamDef.Number("attempts", "Pokusů celkem", 1f, 10f, 1f, unit = "×").apply {
                    hiddenInSummaryWhen = { (it["attempts"] as? ParamValue.Number)?.value?.let { n -> n <= 1f } ?: true }
                },
                ParamDef.Number("delay", "Pauza před dalším pokusem", 0f, 60f, 2f, unit = "s").apply {
                    hiddenWhen = { (it["attempts"] as? ParamValue.Number)?.value?.let { n -> n <= 1f } ?: true }
                },
                ParamDef.Toggle("backoff", "Pauzu pokaždé zdvojnásobit").apply {
                    hiddenWhen = { (it["attempts"] as? ParamValue.Number)?.value?.let { n -> n <= 1f } ?: true }
                },
            ),
            outputName = "Chyba",
        ) { BlockResult.None },

        BlockDef(
            type = CATCH,
            summary = "Když selže",
            title = "Když selže",
            description = "Kroky, které se spustí, když něco v Zkusit selhalo. Text chyby je výstup bloku Zkusit.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.ErrorOutline,
            kind = BlockKind.Else,
            showInLibrary = false,
        ),

        BlockDef(
            type = END_TRY,
            summary = "Konec Zkusit",
            title = "Konec Zkusit",
            description = "",
            category = BlockCategory.Control,
            icon = Icons.Rounded.HealthAndSafety,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

        BlockDef(
            type = END_WHILE,
            summary = "Konec opakování",
            title = "Konec opakování",
            description = "",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Repeat,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

        BlockDef(
            type = "wait",
            summary = "Počkat {seconds} {unit}",
            title = "Počkat",
            description = "Pozastaví akci na zadanou dobu – sekundy, minuty nebo hodiny.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Timer,
            params = listOf(
                ParamDef.Number("seconds", "Doba", 0f, 60f, 1f, step = 0.5f),
                ParamDef.Choice(
                    "unit", "Jednotka", listOf(
                        ChoiceOption("s", "sekundy", "s"),
                        ChoiceOption("min", "minuty", "min"),
                        ChoiceOption("h", "hodiny", "h"),
                    )
                ),
            ),
        ) { block ->
            val factor = when (choice(block, "unit")) {
                "min" -> 60_000L
                "h" -> 3_600_000L
                else -> 1000L
            }
            delay((number(block, "seconds") * factor).toLong())
            BlockResult.None
        },

        BlockDef(
            type = "return",
            summary = "Vrátit {text}",
            title = "Vrátit výsledek",
            description = "Ukončí akci a vrátí text. Při spuštění z výběru textu nahradí označený text, " +
                    "jinak se výsledek zkopíruje do schránky.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Output,
            params = listOf(ParamDef.Text("text", "Výsledek", defaultRef = VarRef.Input)),
        ) { block -> BlockResult.Stop(text(block, "text")) },

        // ---------- Device ----------
        BlockDef(
            type = "volume",
            summary = "Nastavit hlasitost {stream} na {level}",
            title = "Nastavit hlasitost",
            description = "Změní hlasitost zvolené zvukové stopy.",
            category = BlockCategory.Device,
            icon = Icons.AutoMirrored.Rounded.VolumeUp,
            params = listOf(
                ParamDef.Choice(
                    "stream", "Zvuk", listOf(
                        ChoiceOption("music", "Média", "médií"),
                        ChoiceOption("ring", "Vyzvánění", "vyzvánění"),
                        ChoiceOption("notification", "Oznámení", "oznámení"),
                        ChoiceOption("alarm", "Budík", "budíku"),
                        ChoiceOption("call", "Hovor", "hovoru"),
                        ChoiceOption("system", "Systém", "systému"),
                    )
                ),
                ParamDef.Number("level", "Hlasitost", 0f, 100f, 50f, step = 5f, unit = "%"),
            ),
            requirement = null,
        ) { block ->
            val audio = context.getSystemService(AudioManager::class.java)
                ?: throw BlockException(tr("zvuk není dostupný"))
            val stream = when (choice(block, "stream")) {
                "ring" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "alarm" -> AudioManager.STREAM_ALARM
                "call" -> AudioManager.STREAM_VOICE_CALL
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audio.getStreamMaxVolume(stream)
            val min = audio.getStreamMinVolume(stream)
            val index = (max * number(block, "level") / 100f).roundToInt().coerceIn(min, max)
            val before = audio.getStreamVolume(stream)
            try {
                audio.setStreamVolume(stream, index, 0)
            } catch (e: SecurityException) {
                throw BlockException(
                    "během režimu Nerušit je potřeba oprávnění",
                    Requirement.NotificationPolicy
                )
            }
            // Android 17+ ignores the change silently when we have no window.
            if (ui == null && AudioHardening.active && !audio.isVolumeFixed &&
                index != before && audio.getStreamVolume(stream) == before
            ) throw AudioHardening.ignored("změnit hlasitost")
            BlockResult.None
        },

        BlockDef(
            type = "ringer",
            summary = "Přepnout zvonění na {mode}",
            title = "Režim zvonění",
            description = "Přepne telefon na zvonění, vibrace nebo tichý režim.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.Vibration,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Režim", listOf(
                        ChoiceOption("normal", "Zvonění", "zvonění"),
                        ChoiceOption("vibrate", "Vibrace", "vibrace"),
                        ChoiceOption("silent", "Tichý", "tichý režim"),
                    )
                ),
            ),
            requirement = Requirement.NotificationPolicy,
        ) { block ->
            val audio = context.getSystemService(AudioManager::class.java)
                ?: throw BlockException(tr("zvuk není dostupný"))
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (notifications?.isNotificationPolicyAccessGranted != true) {
                throw BlockException(tr("chybí přístup k režimu Nerušit"), Requirement.NotificationPolicy)
            }
            val mode = when (choice(block, "mode")) {
                "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                "silent" -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            val before = audio.ringerMode
            audio.ringerMode = mode
            // Android 17+ ignores the change silently when we have no window.
            if (ui == null && AudioHardening.active && mode != before && audio.ringerMode == before) {
                throw AudioHardening.ignored("přepnout režim zvonění")
            }
            BlockResult.None
        },

        BlockDef(
            type = "vibrate",
            summary = "Zavibrovat {duration}",
            title = "Zavibrovat",
            description = "Krátce zavibruje telefonem.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.Vibration,
            params = listOf(ParamDef.Number("duration", "Délka", 50f, 2000f, 300f, step = 50f, unit = "ms")),
        ) { block ->
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            } ?: throw BlockException(tr("vibrace nejsou dostupné"))
            vibrator.vibrate(
                VibrationEffect.createOneShot(number(block, "duration").toLong(), VibrationEffect.DEFAULT_AMPLITUDE)
            )
            BlockResult.None
        },

        BlockDef(
            type = "network",
            summary = "Zjistit {info}",
            title = "Informace o síti",
            description = "Typ připojení vrací wifi / mobile / ethernet / none, název Wi-Fi potřebuje přístup k poloze, ostatní volby true / false.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.Wifi,
            params = listOf(
                ParamDef.Choice(
                    "info", "Zjistit", listOf(
                        ChoiceOption("type", "Typ připojení", "typ připojení"),
                        ChoiceOption("wifi_name", "Název Wi-Fi", "název Wi-Fi"),
                        ChoiceOption("connected", "Je připojeno k internetu", "zda je internet"),
                        ChoiceOption("metered", "Je připojení měřené", "zda je připojení měřené"),
                    )
                ),
            ),
            outputName = "Síť",
        ) { block ->
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            val value = when (choice(block, "info")) {
                "connected" -> yesNo(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                "metered" -> yesNo(caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                "wifi_name" -> DeviceState.wifiName(context)
                else -> when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                    else -> "other"
                }
            }
            BlockResult.Output(value)
        },

        BlockDef(
            type = "orientation",
            summary = "Zjistit orientaci zařízení",
            title = "Orientace zařízení",
            description = "Vrací portrait (na výšku) nebo landscape (na šířku).",
            category = BlockCategory.Device,
            icon = Icons.Rounded.ScreenRotation,
            outputName = "Orientace",
        ) {
            val orientation = context.resources.configuration.orientation
            BlockResult.Output(if (orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        },

        BlockDef(
            type = "battery",
            summary = "Zjistit {info}",
            title = "Stav baterie",
            description = "Úroveň nabití v procentech, nebo zda se nabíjí (true / false).",
            category = BlockCategory.Device,
            icon = Icons.Rounded.BatteryFull,
            params = listOf(
                ParamDef.Choice(
                    "info", "Zjistit", listOf(
                        ChoiceOption("level", "Úroveň nabití (%)", "úroveň baterie"),
                        ChoiceOption("charging", "Nabíjí se", "zda se nabíjí"),
                    )
                ),
            ),
            outputName = "Baterie",
        ) { block ->
            val battery = context.getSystemService(BatteryManager::class.java)
                ?: throw BlockException(tr("baterie není dostupná"))
            val value = when (choice(block, "info")) {
                "charging" -> yesNo(battery.isCharging)
                else -> battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toString()
            }
            BlockResult.Output(value)
        },

        // ---------- Other ----------
        BlockDef(
            type = "toast",
            summary = "Zobrazit zprávu {text}",
            title = "Zobrazit zprávu",
            description = "Ukáže krátkou zprávu dole na obrazovce.",
            category = BlockCategory.Other,
            icon = Icons.Rounded.ChatBubble,
            params = listOf(ParamDef.Text("text", "Zpráva", defaultRef = VarRef.Input)),
        ) { block ->
            val message = text(block, "text")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            BlockResult.None
        },

        BlockDef(
            type = "open_url",
            needsWindow = true,
            summary = "Otevřít {url}",
            title = "Otevřít odkaz",
            description = "Otevře webovou adresu nebo jiný odkaz (tel:, mailto:, geo:…).",
            category = BlockCategory.Other,
            icon = Icons.Rounded.OpenInBrowser,
            params = listOf(ParamDef.Text("url", "Adresa", placeholder = "https://…")),
        ) { block ->
            var url = text(block, "url").trim()
            if (url.isEmpty()) throw BlockException(tr("adresa je prázdná"))
            if (!url.contains(":")) url = "https://$url"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            } catch (e: ActivityNotFoundException) {
                throw BlockException(tr("žádná aplikace neumí otevřít \"%s\"", url))
            }
            BlockResult.None
        },

        BlockDef(
            type = "date",
            summary = "Datum ve formátu {format}",
            title = "Datum a čas",
            description = "Aktuální datum ve zvoleném formátu (např. d. M. yyyy, HH:mm, EEEE).",
            category = BlockCategory.Other,
            icon = Icons.Rounded.Event,
            params = listOf(ParamDef.Text("format", "Formát", default = "d. M. yyyy HH:mm", plain = true)),
            outputName = "Datum",
        ) { block ->
            val format = text(block, "format")
            val value = try {
                SimpleDateFormat(format, Locale.getDefault()).format(Date())
            } catch (e: IllegalArgumentException) {
                throw BlockException(tr("neplatný formát data"))
            }
            BlockResult.Output(value)
        },
    ) + MoreBlocks.all + DataBlocks.all + HttpBlocks.all + ImageBlocks.all + ExtraBlocks.all + NotificationBlock.def +
            ControlBlocks.all + AppBlocks.all + DeviceBlocks.all

    private val byType = all.associateBy { it.type }

    fun get(type: String): BlockDef? = byType[type]

    val library: Map<BlockCategory, List<BlockDef>> =
        all.filter { it.showInLibrary }.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })
}

fun parseNumber(text: String): Double? =
    text.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()
