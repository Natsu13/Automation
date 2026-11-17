package cz.natsu.automation

import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.EventPermission
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.EventValue
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.BlockCategory
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.BlockHelp
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.ControlBlocks
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.engine.RunTrigger
import cz.natsu.automation.engine.formatNumber
import cz.natsu.automation.i18n.English
import cz.natsu.automation.i18n.I18n
import cz.natsu.automation.i18n.Language
import cz.natsu.automation.ui.editor.ScriptWriter
import cz.natsu.automation.ui.list.Examples
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Writes `web/ai-guide.md`: everything an AI model needs to write automations for the app on a
 * user's request – how to hand them over, the script format, every block with its parameters,
 * events and real examples (written by the app's own ScriptWriter, so they are always valid).
 *
 * Runs with the other unit tests (`./gradlew :app:testDebugUnitTest`) and before every deploy of
 * the web editor, so the guide follows the blocks.
 */
class AiGuideExport {
    private val editorUrl = "https://natsu13.github.io/Automation/"

    private fun en(czech: String) = English.texts[czech] ?: czech

    /** A parameter value as the script language writes it. */
    private fun literal(value: ParamValue): String = when (value) {
        is ParamValue.Choice -> quote(value.value)
        is ParamValue.Number -> formatNumber(value.value.toDouble())
        is ParamValue.Bool -> value.value.toString()
        is ParamValue.Rich -> value.segments.filter { it !is Segment.Text || it.text.isNotEmpty() }.joinToString(" & ") {
            when (it) {
                is Segment.Text -> if (Regex("-?\\d+(\\.\\d+)?").matches(it.text)) it.text else quote(it.text)
                is Segment.Var -> when (val ref = it.ref) {
                    VarRef.Input -> "input"
                    VarRef.Clipboard -> "clipboard"
                    VarRef.CurrentDate -> "date()"
                    VarRef.Trigger -> "trigger"
                    is VarRef.Event -> "event(${quote(ref.key)})"
                    is VarRef.Secret -> "secret(${quote(ref.name)})"
                    is VarRef.Named -> ref.name
                    is VarRef.BlockOutput -> "…"
                }
            }
        }.ifEmpty { "\"\"" }
    }

    private fun quote(text: String) =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    private val shadowed = setOf("replace", "text", "random", "date", "split", "join", "find", "match", "type", "json")

    /** "only when method is POST, PUT" – from the visibility rule the web catalog records. */
    private fun usedWhen(def: BlockDef, param: ParamDef): String? {
        val rule = WebCatalogExport().hiddenRule(def.params, param)
        if (rule is JsonPrimitive) return if (rule.content == "true") "not shown (kept for older versions)" else null
        rule as JsonObject
        val keys = rule["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        val kinds = rule["kinds"]!!.jsonArray
        val hidden = rule["hiddenFor"]!!.jsonArray.map { combo -> combo.jsonArray.map { it.jsonPrimitive.content } }.toSet()
        if (keys.size == 1) {
            val key = keys[0]
            val controller = def.param(key)
            return when {
                kinds[0] is JsonPrimitive && kinds[0].jsonPrimitive.content == "text" -> "only when `$key` is filled in"
                controller is ParamDef.Choice -> {
                    val shown = controller.options.map { it.value }.filter { listOf(it) !in hidden }
                    "only when `$key` is " + shown.joinToString(" / ") { "\"$it\"" }
                }
                controller is ParamDef.Toggle -> {
                    val shown = listOf("true", "false").filter { listOf(it) !in hidden }
                    "only when `$key` is " + shown.joinToString(" / ")
                }
                controller is ParamDef.Number -> "only for some values of `$key`" + (if (key == "attempts") " (more than 1)" else "")
                else -> "depends on `$key`"
            }
        }
        // Several controllers: list the combinations where it is shown when they are few.
        val choices = keys.map { def.param(it) }
        if (choices.all { it is ParamDef.Choice || it is ParamDef.Toggle }) {
            var combos: List<List<String>> = listOf(emptyList())
            for (c in choices) combos = combos.flatMap { combo ->
                (if (c is ParamDef.Choice) c.options.map { it.value } else listOf("true", "false")).map { combo + it }
            }
            val shown = combos.filter { it !in hidden }
            // Describe each controller by the values that appear in the shown combinations.
            return "only when " + keys.mapIndexed { i, key ->
                "`$key` is " + shown.map { it[i] }.distinct().joinToString(" / ") { "\"$it\"" }
            }.joinToString(" and ")
        }
        return "depends on " + keys.joinToString(", ") { "`$it`" }
    }

    private fun kindName(p: ParamDef) = when (p) {
        is ParamDef.Text -> if (p.code) "code" else if (p.plain) "text (plain)" else "text"
        is ParamDef.Choice -> "choice"
        is ParamDef.Number -> "number"
        is ParamDef.Toggle -> "true/false"
        is ParamDef.Automation -> "automation id"
    }

    private fun outputName(def: BlockDef): String? {
        val output = def.outputName ?: return null
        val base = java.text.Normalizer.normalize(en(output).lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return if (Blocks.get(base) != null) base + "_result" else base
    }

    private fun permissionText(p: EventPermission) = when (p) {
        EventPermission.Location -> "location access (for the Wi-Fi name)"
        EventPermission.Bluetooth -> "Nearby devices (Bluetooth) permission"
        EventPermission.NotificationAccess -> "notification access (system settings)"
        EventPermission.Place -> "location access \"Allow all the time\""
        EventPermission.Nfc -> "NFC switched on"
        EventPermission.Sms -> "SMS permission"
        EventPermission.Phone -> "phone and call log permission"
        EventPermission.Accessibility -> "the app's accessibility service switched on"
    }

    private fun header(entity: AutomationEntity): String = buildString {
        appendLine("# @name ${quote(entity.name)}")
        appendLine("# @icon ${entity.icon}")
        val triggers = listOfNotNull(
            "process_text".takeIf { entity.triggerProcessText }, "share".takeIf { entity.triggerShare },
            "tile".takeIf { entity.triggerTile }, "clipboard".takeIf { entity.triggerClipboard },
        )
        if (triggers.isNotEmpty()) appendLine("# @triggers " + triggers.joinToString(", "))
        for (e in entity.events) {
            val args = e.params.entries.joinToString(", ") { "${it.key}: ${quote(it.value)}" }
            appendLine("# @event ${e.type}" + if (args.isEmpty()) "" else "($args)")
        }
    }.trimEnd()

    @Test
    fun export() {
        I18n.force(Language.English)
        try {
            val out = File("../web/ai-guide.md")
            out.writeText(guide())
            println("Wrote ${out.canonicalPath}")
        } finally {
            I18n.force(Language.Czech)
        }
    }

    private fun guide(): String = buildString {
        val title = { type: String -> en(Blocks.get(type)!!.title) }
        appendLine("# Automation app – guide for AI assistants")
        appendLine()
        appendLine("<!-- Generated by app/src/test/java/cz/natsu/automation/AiGuideExport.kt – do not edit by hand. -->")
        appendLine()
        appendLine(
            """
            **Automation** is an Android app (package `cz.natsu.automation`) where users build automations from blocks
            (steps): text tools, clipboard, sharing, HTTP/JSON APIs, dialogs, notifications, files, device settings,
            conditions and loops. An automation is started by hand (selected text, Share sheet, quick-settings tile,
            copying to the clipboard) or by a system event (Wi-Fi, charger, time, NFC tag, notification, …).

            When a user asks you for an automation, **write it as a script** in the format described below. It is the
            same text the app shows under *automation settings → Show as script*, plus a few `# @` header lines for
            how the automation starts. Answer the user in their language (the app is in English and Czech).
            """.trimIndent()
        )
        appendLine()
        appendLine("## 1. What to give the user")
        appendLine()
        appendLine(
            """
            1. **The script** in one code block (start it with the `# @name` header). Use only the blocks, parameters,
               values and events listed in this guide.
            2. **How to get it into the phone** – copy these steps for the user:
               - **Straight in the app** (simplest): copy the script, open the Automation app, **⋮ → Paste a script**
                 (the copied script is filled in) → **Import**. A `.txt` file with the script works too:
                 **⋮ → Import from file**.
               - **Through the web editor** (to look at the blocks or change them on a computer first):
                 1. Open $editorUrl, click **Import**, paste the script and click **Import**. The steps appear as
                    blocks (the *Code* tab shows the script again).
                 2. **Export → This automation** downloads a `.automation.json` file; send it to the phone
                    (e-mail, cloud, cable…) and in the app use **⋮ → Import from file** (or open the file with the app).
               - Either way the `# @` lines set the name, icon and how the automation starts.
               - An imported automation does not run until the user opens it, checks the steps and taps the banner
                 to allow it.
            3. **What to allow on the phone** – list the permissions of the blocks and events you used (see
               *Requires* in the reference, e.g. notifications, location, “Do not disturb” access). The app asks for
               them in the editor, but saying it up front helps.
            4. **How to start it** – e.g. “select text → menu → *your automation name*”, “Share → Automation”,
               the quick-settings tile, or that it runs by itself on the chosen event.
            5. If something needs a secret (API key, token), name it in `secret("name")` and tell the user which
               value to get (e.g. where to create the API key). Secrets never get into a script or an export: after
               the import the app says which secrets are missing and asks for them when the user allows the
               automation (or **Settings → Secret values**).
            """.trimIndent()
        )
        appendLine()

        // ---------- quick example ----------
        appendLine("## 2. Quick example")
        appendLine()
        appendLine("A user asks: *“When I share a link, remove the tracking parameters and copy it.”*")
        appendLine()
        appendLine("```")
        appendLine(
            """
            # @name "Clean link"
            # @icon link
            # @triggers share, process_text
            url = extract({source: input, what: "url", which: "first"})
            if url != ""
                clean = block("replace", url, "(?<=[?&])(utm_[a-z_]+|fbclid|gclid|si|igsh|ref|ref_src)=[^&#]*&?", "", true)
                clean2 = block("replace", clean, "[?&]+(?=#|${'$'})", "", true)
                set_clipboard(clean2)
                toast("Copied: " & clean2)
            else
                toast("No link in: " & input)
            end
            """.trimIndent()
        )
        appendLine("```")
        appendLine()
        appendLine(
            """
            - `url = extract(…)` runs the block **${title("extract")}** and names its output `url`; later steps use it by that name.
            - `block("replace", …)` – **${title("replace")}** is named like a script function, so it is called through `block("type", …)`.
            - `input` is the text the automation was started with (the shared or selected text).
            - Parameters go in the block's order (`source, what, which`), or by name in `{ }` to skip some.
            """.trimIndent()
        )
        appendLine()

        // ---------- format ----------
        appendLine("## 3. The script format")
        appendLine()
        appendLine("### Header (how the automation starts)")
        appendLine()
        appendLine(
            """
            Comment lines at the top. Only `# @name` is needed; leave out what does not apply.

            ```
            # @name "Morning summary"
            # @icon schedule
            # @triggers share, process_text, tile, clipboard
            # @event time_daily(time: "07:30", days: "12345")
            # @conditions all
            # @condition launch_condition({source: "wifi_name", op: "equals", right: "Home"})
            ```

            - `@triggers` – manual starts: `process_text` (select text → menu), `share` (Share sheet), `tile`
              (quick-settings tile), `clipboard` (when the user copies text).
            - `@event type(param: "value", …)` – one line per event; all values are text in quotes. See *Events*.
            - `@condition` – launch conditions checked before the first step (block *${title(Blocks.LAUNCH_CONDITION)}*, see below);
              `@conditions all` = all must hold, `any` = one is enough.
            - `@icon` – one of: ${cz.natsu.automation.ui.common.IconCatalog.all.joinToString(", ") { "`${it.key}`" }}.
            """.trimIndent()
        )
        appendLine()
        appendLine("### Steps")
        appendLine()
        appendLine(
            """
            One step per line, indented by 4 spaces inside `if` / `for` / `while` / groups.

            | Write | Block |
            |---|---|
            | `name = block_type(arg1, arg2)` | a block; `name` holds its output for later steps (only blocks with an output) |
            | `block_type({param: value, other: value})` | the same with parameters by name (to skip some) |
            | `name = block("replace", …)` | blocks named like a function: ${shadowed.filter { Blocks.get(it) != null }.joinToString(", ") { "`$it`" }} |
            | `name = value` | **${title(Blocks.SET_VARIABLE)}** (a variable) |
            | `name = "text " & input` | for an existing **${title("text")}** step; in new scripts it makes a variable |
            | `name = a + b` / `name = calc("2 * (x + 1)")` | **${title("math")}** (simple / expression) |
            | `if cond` … `else if cond` … `else` … `end` | **${title(Blocks.IF)}**, **${title(Blocks.ELSE_IF)}**, **${title(Blocks.ELSE)}** |
            | `while cond` … `end` | **${title(Blocks.WHILE)}** |
            | `for item in list` … `end` | **${title("for_each")}** – `item` is the current item |
            | `for i in range(5)` … `end` | **${title("repeat")}** – `i` is the round number |
            | `break` / `continue` | **${title("loop_control")}** |
            | `return value` | **${title("return")}** – ends the run with a result |
            | `# text` | **${title("comment")}** (a note); a commented-out block call is a switched-off step |
            | anything else in the script language | **${title(ControlBlocks.SCRIPT)}** – a few lines of code (see *Script language*) |
            """.trimIndent()
        )
        appendLine()
        appendLine("### Values (block parameters)")
        appendLine()
        appendLine(
            """
            - Text: `"Hello\n"` (`\n` new line, `\"` quote, `\\` backslash), numbers bare: `5`, `2.5`.
            - Join with `&`: `"Battery " & event("battery") & " %"`.
            - Built-in values: `input` (the started-with text), `trigger` (how it was started), `clipboard`, `date()`
              (current date and time).
            - Step outputs and variables by name: `url`, `total`; a variable with spaces: `var("my value")`.
            - `secret("api_key")` – a secret stored on the phone.
            - `event("wifi_name")` – a value of the event that started the run (see *Event values*; the block
              **${title("event_value")}** does the same).
            - Choice parameters take one of the listed values in quotes: `"url"`. Switches: `true` / `false`.
            - A text parameter takes only texts, numbers, names and `&`. To compute (upper case, length, maths, JSON),
              use a block for it, or a line of script code: `n = len(input)` becomes a Script step.
            - `trigger` is the start type in the app's language: ${RunTrigger.entries.joinToString(", ") { "\"${en(it.label)}\" / \"${it.label}\"" }}
              (for an event: the event's title). Prefer `contains(trigger, "Share") or contains(trigger, "Sdílení")`.
            """.trimIndent()
        )
        appendLine()
        appendLine("### Conditions (`if`, `else if`, `while`)")
        appendLine()
        appendLine(
            """
            | Write | Meaning |
            |---|---|
            | `a == b`, `a != b` | equal / not equal (letters' case is ignored) |
            | `a == ""`, `a != ""` | empty / not empty |
            | `a > b`, `a < b`, `a >= b`, `a <= b` | numbers |
            | `contains(a, b)`, `not contains(a, b)` | text contains |
            | `starts(a, b)`, `ends(a, b)` | starts / ends with |
            | `matches(a, "regex")` | regular expression |
            | anything else, e.g. `x > 5 and contains(input, "a")` | an expression (and / or / not, functions) |
            """.trimIndent()
        )
        appendLine()
        appendLine("### Groups that exist only as blocks")
        appendLine()
        appendLine(
            "Try, parallel branches, one-at-a-time and time limits have no script keyword. Write their marker lines exactly " +
                "(the title, two spaces, the note in parentheses) and indent the steps inside by 4 spaces. " +
                "Their settings (retries, time limit, lock name) keep their defaults; the user can change them in the editor."
        )
        appendLine()
        appendLine("```")
        val note = "(only as blocks, not in a script)"
        appendLine("# ${title(Blocks.TRY)}  $note")
        appendLine("    response = http_request({url: \"https://api.example.com/items\", only_body: true})")
        appendLine("# ${title(Blocks.CATCH)}  $note")
        appendLine("    toast(\"API not available\")")
        appendLine("# ${title(Blocks.END_TRY)}  $note")
        appendLine()
        appendLine("# ${title(ControlBlocks.PARALLEL)}  $note")
        appendLine("    a = http_request({url: \"https://api.example.com/a\", only_body: true})")
        appendLine("# ${title(ControlBlocks.BRANCH)}  $note")
        appendLine("    b = http_request({url: \"https://api.example.com/b\", only_body: true})")
        appendLine("# ${title(ControlBlocks.END_PARALLEL)}  $note")
        appendLine("```")
        appendLine()
        appendLine(
            "Also: `# ${title(ControlBlocks.LOCK)}  $note` … `# ${title(ControlBlocks.END_LOCK)}  $note`, " +
                "`# ${title(ControlBlocks.TIME_LIMIT)}  $note` … `# ${title(ControlBlocks.END_TIME_LIMIT)}  $note`."
        )
        appendLine()
        appendLine("### Choose by (switch)")
        appendLine()
        appendLine("```")
        appendLine("# ${title(ControlBlocks.SWITCH)} input")
        appendLine("if input == \"morning\" or input == \"am\"")
        appendLine("    toast(\"Good morning\")")
        appendLine("else if input == \"evening\"")
        appendLine("    toast(\"Good evening\")")
        appendLine("else")
        appendLine("    toast(\"Hello\")")
        appendLine("end")
        appendLine("```")
        appendLine()
        appendLine("The `if` / `else if` / `else` / `end` of a switch are indented exactly like its `# ${title(ControlBlocks.SWITCH)}` line.")
        appendLine()
        appendLine("### Script language")
        appendLine()
        appendLine("Lines that are not a block become a **${title(ControlBlocks.SCRIPT)}** step. Its language:")
        appendLine()
        appendLine("```text")
        appendLine(BlockHelp.of(ControlBlocks.SCRIPT)!!.trimIndent())
        appendLine("```")
        appendLine()
        appendLine("### Checklist before answering")
        appendLine()
        appendLine(
            """
            - Every block type, parameter name and choice value comes from the reference below – do not invent any.
            - Steps whose output is used later are named (`name = …`); names are letters, digits and `_`, not a
              script word (`if`, `end`, `input`, `text`, `date`, `list`, `count`, `type`, …).
            - Every `if`, `while`, `for` and `# ${title(ControlBlocks.SWITCH)}` has its `end`; marker groups have their end line.
            - Blocks named like a function go through `block("…", …)`.
            - Dialogs, the clipboard and opening apps need the screen; in background events the app opens a window,
              which needs *Display over other apps*.
            - Events only from the list; their parameters are text in quotes.
            """.trimIndent()
        )
        appendLine()

        // ---------- events ----------
        appendLine("## 4. Events")
        appendLine()
        appendLine("`# @event key(param: \"value\")`. `input` = what the started run gets as its input.")
        appendLine()
        appendLine("| Key | Event | Parameters (default) | `input` | Needs |")
        appendLine("|---|---|---|---|---|")
        for (e in EventType.entries) {
            val params = when (e) {
                EventType.TimeDaily -> "`time` \"HH:MM\" (\"07:00\"), `days` digits 1=Mon…7=Sun (\"1234567\")"
                EventType.TimeInterval -> "`minutes` (\"60\", at least 15)"
                EventType.BatteryBelow, EventType.BatteryAbove -> "`percent` (\"${e.defaults["percent"]}\")"
                EventType.PlaceEntered, EventType.PlaceLeft -> "`place` \"lat, lng\" or an address, `radius` metres (\"200\")"
                EventType.Sunrise, EventType.Sunset -> "`place` \"lat, lng\" or an address"
                else -> "–"
            }
            appendLine("| `${e.key}` | ${en(e.title)} – ${en(e.description)} | $params | ${e.input?.let { en(it) } ?: "–"} | ${e.permission?.let { permissionText(it) } ?: "–"} |")
        }
        appendLine()
        appendLine("### Event values")
        appendLine()
        appendLine("`event(\"key\")` in a step, or the block **${title("event_value")}**:")
        appendLine()
        for (v in EventValue.entries) appendLine("- `${v.key}` – ${en(v.label)}: ${en(v.description)}")
        appendLine()
        appendLine("### Launch conditions")
        appendLine()
        val condition = Blocks.get(Blocks.LAUNCH_CONDITION)!!
        appendLine("`# @condition launch_condition({…})` with these parameters:")
        appendLine()
        appendParams(condition)
        appendLine()

        // ---------- blocks ----------
        appendLine("## 5. Blocks")
        appendLine()
        appendLine(
            "Signature = the parameters in order for positional calls. Defaults are used for parameters you leave out. " +
                "*Output* is the kind of value the step gives (name it in the script). Titles are English / Czech, as the app shows them."
        )
        appendLine()
        val markers = setOf(BlockKind.Else, BlockKind.ElseIf, BlockKind.End, BlockKind.Branch)
        for (category in BlockCategory.entries) {
            val defs = Blocks.all.filter { it.category == category && it.kind !in markers && it.type != Blocks.LAUNCH_CONDITION && it.showInLibrary }
            if (defs.isEmpty()) continue
            appendLine("### ${en(category.title)}")
            appendLine()
            for (def in defs) appendBlock(def)
        }

        // ---------- examples ----------
        appendLine("## 6. Examples from the app")
        appendLine()
        appendLine("Real automations that ship with the app, written by the app itself.")
        appendLine()
        for (example in Examples.all) {
            val built = example.build()
            val entity = built.triggers(AutomationEntity(name = en(example.name), icon = example.icon))
            appendLine("### ${en(example.name)}")
            appendLine()
            appendLine(en(example.description))
            appendLine()
            appendLine("```")
            appendLine(header(entity))
            appendLine(ScriptWriter(built.blocks).write())
            appendLine("```")
            appendLine()
        }
    }

    private fun StringBuilder.appendBlock(def: BlockDef) {
        val type = def.type
        appendLine("#### `$type` – ${en(def.title)} / ${def.title}")
        appendLine()
        appendLine(en(def.description))
        appendLine()
        val call = if (type in shadowed) "block(\"$type\"" + (if (def.params.isEmpty()) ")" else ", ") else "$type("
        val signature = call + def.params.joinToString(", ") { it.key } + (if (type in shadowed && def.params.isEmpty()) "" else ")")
        val facts = mutableListOf("Signature: `$signature`")
        outputName(def)?.let { facts += "Output: ${en(def.outputName!!)} (e.g. `$it = …`)" }
        when (def.kind) {
            BlockKind.IfStart, BlockKind.WhileStart -> facts += "Group: the steps up to `end`"
            BlockKind.TryStart, BlockKind.ParallelStart, BlockKind.Scope -> facts += "Group: marker lines, see *Groups that exist only as blocks*"
            else -> {}
        }
        def.requirement?.let { facts += "Requires: ${en(it.title)} – ${en(it.description)}" }
        if (def.needsWindow) facts += "Shows something on screen"
        for (f in facts) appendLine("- $f")
        appendLine()
        if (def.params.isNotEmpty()) appendParams(def)
        BlockHelp.of(type)?.let { help ->
            appendLine("<details><summary>Help</summary>")
            appendLine()
            appendLine("```text")
            appendLine(help.trimIndent())
            appendLine("```")
            appendLine()
            appendLine("</details>")
            appendLine()
        }
    }

    private fun StringBuilder.appendParams(def: BlockDef) {
        appendLine("| Parameter | Kind | Default | Values / notes |")
        appendLine("|---|---|---|---|")
        for (p in def.params) {
            val notes = mutableListOf<String>()
            if (p.label.isNotBlank()) notes += en(p.label)
            when (p) {
                is ParamDef.Choice -> notes += p.options.joinToString(", ") { "`\"${it.value}\"` ${en(it.label)}" }
                is ParamDef.Number -> notes += "${formatNumber(p.min.toDouble())}–${formatNumber(p.max.toDouble())}" + (if (p.unit.isNotEmpty()) " ${en(p.unit)}" else "")
                is ParamDef.Text -> {
                    if (p.placeholder.isNotBlank()) {
                        val example = en(p.placeholder).replace("\n", " ⏎ ").removePrefix("e.g. ").removePrefix("např. ")
                        notes += "e.g. $example"
                    }
                    if (p.suggestions.isNotEmpty()) notes += "common: " + p.suggestions.joinToString(", ") { "`$it`" }
                    if (p.appPicker) notes += "an app name or package"
                }
                is ParamDef.Automation -> notes += "the number of another automation on the phone, \"\" = none"
                else -> {}
            }
            usedWhen(def, p)?.let { notes += "*$it*" }
            val cell = notes.joinToString(" · ").replace("|", "\\|")
            appendLine("| `${p.key}` | ${kindName(p)} | `${literal(p.defaultValue()).replace("|", "\\|")}` | $cell |")
        }
        appendLine()
    }
}
