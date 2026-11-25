package cz.natsu.automation.ui.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.BlockDef
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.ControlBlocks
import cz.natsu.automation.engine.DataBlocks
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.engine.Structure
import cz.natsu.automation.engine.formatNumber
import cz.natsu.automation.i18n.English
import cz.natsu.automation.i18n.tr
import java.text.Normalizer

/**
 * Writes an automation as a script in the language of the "Skript" block:
 *
 * ```
 * i = 0
 * result = calc(i & "+1")
 * if result > 5
 *     toast("big")
 * end
 * ```
 *
 * A step with an output becomes `name = …`, a step without one a call of its block.
 * The text can be pasted into a Script block: groups of steps have words too
 * (`script … end`, `try … catch … end`, `parallel … branch … end`, `lock … end`,
 * `time_limit(10) … end`), so the whole automation reads back into the same blocks.
 */
class ScriptWriter(private val blocks: List<Block>) {
    private val structure = Structure.analyze(blocks)

    /** Variable name of every step that has an output, by block id. */
    private val outputNames: Map<String, String> = buildMap {
        val taken = blocks.filter { it.type == Blocks.SET_VARIABLE }.mapNotNull { plain(it.params["name"]) }.toMutableSet()
        taken += RESERVED
        for (block in blocks) {
            val def = Blocks.get(block.type) ?: continue
            val output = def.outputName ?: continue
            // A name that is also a block or a function would read like "web = web(…)".
            val base = identifier(English.texts[output] ?: output).ifEmpty { "value" }
                .let { if (Blocks.get(it) != null || it in FUNCTIONS) it + "_result" else it }
            var name = base
            var n = 2
            while (name in taken) name = base + n++
            taken += name
            put(block.id, name)
        }
    }

    /** Ids of the steps whose output some later step uses (only those need a name). */
    private val used: Set<String> = blocks.flatMap { block ->
        block.params.values.filterIsInstance<ParamValue.Rich>().flatMap { it.segments }
    }.mapNotNull { ((it as? Segment.Var)?.ref as? VarRef.BlockOutput)?.blockId }.toSet()

    fun write(): String {
        val lines = mutableListOf<String>()
        for ((index, block) in blocks.withIndex()) {
            // The "else" of an "if" whose own branch is empty: the "if" is written the other way round instead.
            if (index > 0 && isEmptyIf(index - 1)) continue
            val def = Blocks.get(block.type)
            val indent = "    ".repeat(structure.depth.getOrElse(index) { 0 })
            val text = if (def == null) "# ? ${block.type}" else statement(block, def)
            for (line in text.split("\n")) {
                // A switched-off step is skipped by the run, so it is a comment here.
                // An empty line of a script gets no trailing spaces.
                lines += if (line.isEmpty() && !block.disabled) "" else
                    indent + (if (block.disabled && !line.startsWith("#")) "# " else "") + line
            }
        }
        return lines.joinToString("\n")
    }

    // ---------- statements ----------

    /** An "if" directly followed by its "else": nothing happens when it holds. */
    private fun isEmptyIf(index: Int) =
        blocks[index].type == Blocks.IF && structure.elseOf[index] == index + 1 && !blocks[index].disabled

    private fun statement(block: Block, def: BlockDef): String = when (block.type) {
        Blocks.SET_VARIABLE -> {
            val name = plain(block.params["name"]).orEmpty()
            val value = expr(block, def, "value")
            if (isIdentifier(name)) "$name = $value" else "set_variable(${quote(name)}, $value)"
        }
        Blocks.IF -> "if " + condition(block, def, negate = isEmptyIf(blocks.indexOf(block)))
        Blocks.ELSE_IF -> "else if " + condition(block, def)
        Blocks.ELSE -> "else"
        Blocks.WHILE -> "while " + condition(block, def)
        Blocks.END_IF, Blocks.END_WHILE, ControlBlocks.END_SWITCH -> "end"
        DataBlocks.FOR_EACH -> "for ${outputNames[block.id]} in ${expr(block, def, "list")}" + parallelNote(block)
        DataBlocks.REPEAT -> "for ${outputNames[block.id]} in range(${expr(block, def, "count")})" + parallelNote(block)
        DataBlocks.LOOP_CONTROL -> if (choice(block, def, "action") == "continue") "continue" else "break"
        "return" -> "return " + expr(block, def, "text")
        "comment" -> "# " + plain(block.params["text"]).orEmpty().replace("\n", "\n# ")
        // Its own group: a "return" inside ends only this step, as in the app.
        ControlBlocks.SCRIPT -> assign(block, "script") + "\n" +
            code(block.params["code"]).lines().joinToString("\n") { if (it.isBlank()) "" else "    $it" } + "\nend"
        "text" -> assign(block, expr(block, def, "text"))
        "math" -> assign(
            block,
            if (choice(block, def, "mode") == "expert") "calc(${expr(block, def, "expression")})"
            else "${operand(block, def, "a")} ${choice(block, def, "op")} ${operand(block, def, "b")}"
        )
        "calculate" -> assign(block, "calc(${expr(block, def, "expression")})")
        // "Choose by" is a chain of comparisons with the same value.
        ControlBlocks.SWITCH -> "# " + tr(def.title) + " " + expr(block, def, "value")
        ControlBlocks.CASE -> {
            val start = blocks.firstOrNull { it.groupId == block.groupId }
            val value = start?.let { expr(it, Blocks.get(it.type)!!, "value") } ?: "?"
            val first = blocks.firstOrNull { it.groupId == block.groupId && it.type == ControlBlocks.CASE }?.id == block.id
            val options = plainOrExpr(block, def, "value")
            (if (first) "if " else "else if ") + options.joinToString(" or ") { "$value == $it" }
        }
        Blocks.TRY -> {
            val attempts = (param(block, def, "attempts") as? ParamValue.Number)?.value ?: 1f
            val backoff = (param(block, def, "backoff") as? ParamValue.Bool)?.value == true
            val args = if (attempts <= 1f) "" else
                "(" + expr(block, def, "attempts") + ", " + expr(block, def, "delay") + (if (backoff) ", true" else "") + ")"
            assign(block, "try$args")
        }
        Blocks.CATCH -> "catch"
        ControlBlocks.PARALLEL -> "parallel"
        ControlBlocks.BRANCH -> "branch"
        ControlBlocks.LOCK -> plain(block.params["name"]).orEmpty().trim().let { if (it.isEmpty()) "lock" else "lock(${quote(it)})" }
        ControlBlocks.TIME_LIMIT -> "time_limit(${expr(block, def, "seconds")})"
        // Positional: html, title, internet, secure (the switches only when on).
        cz.natsu.automation.engine.HtmlBlocks.WINDOW -> assign(block, call(block, def))
        Blocks.END_TRY, ControlBlocks.END_PARALLEL, ControlBlocks.END_LOCK, ControlBlocks.END_TIME_LIMIT,
        cz.natsu.automation.engine.HtmlBlocks.END_WINDOW -> "end"
        else -> if (def.kind == BlockKind.Normal) assign(block, call(block, def)) else "# " + tr(def.title)
    }

    /** A loop whose rounds run at the same time is a plain loop in a script; the note says what the blocks do. */
    private fun parallelNote(block: Block): String =
        if ((block.params["parallel"] as? cz.natsu.automation.data.ParamValue.Bool)?.value == true) "  # " + tr("souběžně") else ""

    /** `name = value` when a later step uses the output, otherwise just the value / call. */
    private fun assign(block: Block, value: String): String {
        val name = outputNames[block.id]
        return if (name != null && (block.id in used || isPure(block))) "$name = $value" else value
    }

    /** Steps that only compute something are pointless without a name for the result. */
    private fun isPure(block: Block) = block.type in setOf("text", "math", "calculate")

    /** The block called as a function: positional when its set values are the first parameters, else by name. */
    private fun call(block: Block, def: BlockDef): String {
        val values = def.params.associate { it.key to (block.params[it.key] ?: it.defaultValue()) }
        // Everything the block shows, so the line says what it does; only switches and empty
        // fields left as they are at the end are dropped.
        fun skippable(param: ParamDef): Boolean {
            val value = values[param.key]
            if (value != param.defaultValue()) return false
            return param is ParamDef.Toggle || param is ParamDef.Number ||
                    (value is ParamValue.Rich && value.segments.all { it is Segment.Text && it.text.isEmpty() })
        }
        val shown = def.params.filter { !it.isHidden(values) }.dropLastWhile(::skippable)
        val args = shown.map { it.key to expr(block, def, it.key) }
        val name = if (def.type in SHADOWED) "block(${quote(def.type)}" + (if (args.isEmpty()) "" else ", ") else def.type + "("
        val positional = shown == def.params.take(shown.size)
        return name + (if (positional) args.joinToString(", ") { it.second }
        else "{" + args.joinToString(", ") { "${it.first}: ${it.second}" } + "}") + ")"
    }

    private fun condition(block: Block, def: BlockDef, negate: Boolean = false): String {
        if (choice(block, def, "mode") == "expert") {
            val written = code(block.params["expression"]).ifBlank { "true" }
            return if (negate) "not ($written)" else written
        }
        val left = expr(block, def, "left")
        val right = expr(block, def, "right")
        val op = choice(block, def, "op").let { if (negate) OPPOSITE[it] ?: return "not (${condition(block, def)})" else it }
        return when (op) {
            "equals" -> "$left == $right"
            "not_equals" -> "$left != $right"
            "contains" -> "contains($left, $right)"
            "not_contains" -> "not contains($left, $right)"
            "starts" -> "starts($left, $right)"
            "ends" -> "ends($left, $right)"
            "matches" -> "matches($left, $right)"
            "newer_version" -> "newer_version($left, $right)"
            "empty" -> "$left == \"\""
            "not_empty" -> "$left != \"\""
            "greater" -> "$left > $right"
            "less" -> "$left < $right"
            "greater_eq" -> "$left >= $right"
            "less_eq" -> "$left <= $right"
            else -> "$left == $right"
        }
    }

    // ---------- values ----------

    private fun param(block: Block, def: BlockDef, key: String) = block.params[key] ?: def.param(key)?.defaultValue()

    private fun choice(block: Block, def: BlockDef, key: String) = (param(block, def, key) as? ParamValue.Choice)?.value.orEmpty()

    /** A parameter as an expression of the language. */
    private fun expr(block: Block, def: BlockDef, key: String): String = when (val value = param(block, def, key)) {
        null -> "\"\""
        is ParamValue.Choice -> quote(value.value)
        is ParamValue.Number -> formatNumber(value.value.toDouble())
        is ParamValue.Bool -> value.value.toString()
        is ParamValue.Rich -> {
            val parts = value.segments.filter { it !is Segment.Text || it.text.isNotEmpty() }.map {
                when (it) {
                    is Segment.Text -> literal(it.text)
                    is Segment.Var -> reference(it.ref)
                }
            }
            if (parts.isEmpty()) "\"\"" else parts.joinToString(" & ")
        }
    }

    /** A number written as text stays a number; anything else is an expression in parentheses when needed. */
    private fun operand(block: Block, def: BlockDef, key: String): String {
        val value = expr(block, def, key)
        return if (" & " in value) "($value)" else value
    }

    /** The lines of a "When it is" branch, each as a value. */
    private fun plainOrExpr(block: Block, def: BlockDef, key: String): List<String> {
        val rich = param(block, def, key) as? ParamValue.Rich ?: return listOf("\"\"")
        if (rich.segments.any { it is Segment.Var }) return listOf(expr(block, def, key))
        return plain(rich).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::literal).ifEmpty { listOf("\"\"") }
    }

    /** A text as the language writes it: a number bare, the rest in quotes. */
    private fun literal(text: String): String =
        if (Regex("-?\\d+(\\.\\d+)?").matches(text)) text else quote(text)

    private fun quote(text: String) =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    private fun reference(ref: VarRef): String = when (ref) {
        VarRef.Input -> "input"
        VarRef.Clipboard -> "clipboard"
        VarRef.CurrentDate -> "date()"
        VarRef.Trigger -> "trigger"
        is VarRef.Event -> "event(${quote(ref.key)})"
        is VarRef.Secret -> "secret(${quote(ref.name)})"
        is VarRef.Named -> if (isIdentifier(ref.name)) ref.name else "var(${quote(ref.name)})"
        is VarRef.BlockOutput -> outputNames[ref.blockId] ?: "nic"
    }

    /** Code typed by the user (a script, an expression of a condition) with its variable chips as names. */
    private fun code(value: ParamValue?): String = (value as? ParamValue.Rich)?.segments.orEmpty().joinToString("") {
        when (it) {
            is Segment.Text -> it.text
            is Segment.Var -> reference(it.ref)
        }
    }

    private fun plain(value: ParamValue?): String? = (value as? ParamValue.Rich)?.segments
        ?.joinToString("") { if (it is Segment.Text) it.text else "" }

    private fun isIdentifier(name: String) =
        name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' } &&
                name.lowercase() !in RESERVED

    /** "Upravený text" -> "upraveny_text". */
    private fun identifier(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    private companion object {
        // Shared with ScriptReader, which reads this text back into blocks.
        val RESERVED = cz.natsu.automation.engine.ScriptWords.RESERVED
        val FUNCTIONS = cz.natsu.automation.engine.ScriptWords.FUNCTIONS
        val SHADOWED = cz.natsu.automation.engine.ScriptWords.SHADOWED

        val OPPOSITE = mapOf(
            "equals" to "not_equals", "not_equals" to "equals", "contains" to "not_contains",
            "not_contains" to "contains", "empty" to "not_empty", "not_empty" to "empty",
            "greater" to "less_eq", "less_eq" to "greater", "less" to "greater_eq", "greater_eq" to "less",
        )
    }
}

@Composable
fun AutomationTextDialog(viewModel: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember { ScriptWriter(viewModel.blocks.toList()).write() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Automatizace jako skript")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("Stejné kroky zapsané jazykem bloku Skript. Text jde zkopírovat a vložit do bloku Skript, " +
                            "nebo z něj automatizaci zase vytvořit (Importovat / Aktualizovat ze skriptu)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text.ifEmpty { tr("Žádné kroky.") },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = false,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(viewModel.name, text))
                Toast.makeText(context, tr("Zkopírováno"), Toast.LENGTH_SHORT).show()
            }) { Text(tr("Kopírovat")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
}
