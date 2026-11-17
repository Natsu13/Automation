package cz.natsu.automation.engine

import cz.natsu.automation.data.AutomationIcons
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.EventTrigger
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.ExportedAutomation
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.i18n.English
import java.text.Normalizer
import java.util.UUID

/** Words of the script language shared by [cz.natsu.automation.ui.editor.ScriptWriter] and [ScriptReader]. */
object ScriptWords {
    /** Words of the language and names the script already gives a meaning. */
    val RESERVED = setOf(
        "if", "pokud", "else", "jinak", "end", "konec", "while", "dokud", "for", "pro", "return", "vrat",
        "break", "ukonci", "continue", "pokracuj", "and", "or", "not", "nebo", "zaroven", "true", "false",
        "null", "nic", "ano", "input", "vstup", "clipboard", "trigger", "text", "date", "count", "list", "type",
    )

    /** Built-in functions a variable should not be named after. */
    val FUNCTIONS = setOf(
        "len", "upper", "lower", "trim", "contains", "starts", "ends", "substr", "str", "num", "int",
        "round", "floor", "ceil", "abs", "sqrt", "min", "max", "sum", "avg", "calc", "range", "keys",
        "values", "push", "tojson", "iif", "now", "event", "secret", "block", "exact", "matches",
    )

    /** Blocks named like a built-in function of the language: reached through block("type", …). */
    val SHADOWED = setOf("replace", "text", "random", "date", "split", "join", "find", "match", "type", "json")

    /** Blocks the language has no word for: written as "# Title  (note)". */
    val ONLY_BLOCKS = listOf(
        Blocks.TRY, Blocks.CATCH, Blocks.END_TRY, ControlBlocks.PARALLEL, ControlBlocks.BRANCH, ControlBlocks.END_PARALLEL,
        ControlBlocks.LOCK, ControlBlocks.END_LOCK, ControlBlocks.TIME_LIMIT, ControlBlocks.END_TIME_LIMIT,
    )

    /** The note of those lines, in both languages of the app. */
    val ONLY_BLOCKS_NOTE = setOf("ve skriptu jen jako bloky", "only as blocks, not in a script")
    val PARALLEL_NOTE = setOf("souběžně", "in parallel")

    /** Words of "# @triggers". */
    const val PROCESS_TEXT = "process_text"
    const val SHARE = "share"
    const val TILE = "tile"
    const val CLIPBOARD = "clipboard"

    /** "Upravený text" -> "upraveny_text". */
    fun identifier(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "_").trim('_').let { if (it.firstOrNull()?.isDigit() == true) "_$it" else it }

    fun isIdentifier(name: String) =
        name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' } &&
            name.lowercase() !in RESERVED
}

/**
 * Reads an automation written as a script – the text of "Show as script", maybe with "# @name",
 * "# @event", … lines in front (what an AI model writes from the guide) – back into blocks.
 *
 * A line that is a block call, if / for / while, a group marker and so on becomes that block;
 * anything else in the script language becomes a "Skript" block, which runs it as it is.
 * The web editor has the same reader (web/script.js).
 */
class ScriptReader private constructor(private val text: String) {
    /** Which settings a script's "# @" header sets; the rest of an automation it updates stays as it is. */
    data class Header(val name: Boolean, val icon: Boolean, val start: Boolean) {
        companion object { val ALL = Header(name = true, icon = true, start = true) }
    }

    companion object {
        fun read(text: String, fallbackName: String = "Import"): ExportedAutomation = ScriptReader(text).read(fallbackName)

        /** Which settings a script's "# @" lines set. */
        fun headerOf(text: String): Header {
            val words = Regex("^\\s*#\\s*@(\\w+)", RegexOption.MULTILINE).findAll(text).map { it.groupValues[1] }.toSet()
            return Header(
                name = "name" in words,
                icon = "icon" in words,
                start = words.any { it in setOf("triggers", "event", "condition", "conditions") },
            )
        }

        /** Whether a text looks like a script rather than an export file. */
        fun looksLikeScript(text: String): Boolean {
            val first = text.trimStart().removePrefix("﻿").firstOrNull() ?: return false
            return first != '{' && first != '['
        }
    }

    // ---------- tokens ----------

    private enum class T { Str, Num, Id, Op }
    private class Tok(val t: T, val v: String, val s: Int, val e: Int)
    private class Lexed(val tokens: List<Tok>, val comment: String?)
    private class LexError : Exception()

    private fun lex(src: String): Lexed {
        val tokens = mutableListOf<Tok>()
        var i = 0
        var comment: String? = null
        while (i < src.length) {
            val c = src[i]
            when {
                c == ' ' || c == '\t' || c == '\r' -> i++
                c == '#' -> { comment = src.substring(i + 1).trim(); i = src.length }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    val out = StringBuilder()
                    while (j < src.length && src[j] != c) {
                        if (src[j] == '\\' && j + 1 < src.length) {
                            out.append(when (val n = src[j + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
                            j += 2
                        } else out.append(src[j++])
                    }
                    if (j >= src.length) throw LexError()
                    tokens += Tok(T.Str, out.toString(), i, j + 1)
                    i = j + 1
                }
                c.isDigit() -> {
                    val m = Regex("^\\d+(\\.\\d+)?").find(src.substring(i))!!.value
                    tokens += Tok(T.Num, m, i, i + m.length); i += m.length
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                    tokens += Tok(T.Id, src.substring(i, j), i, j); i = j
                }
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    val op = if (two in setOf("==", "!=", ">=", "<=")) two else c.toString()
                    if (op.length == 1 && op !in "-+*/%^&(){}[],:=<>.!") throw LexError()
                    tokens += Tok(T.Op, op, i, i + op.length); i += op.length
                }
            }
        }
        return Lexed(tokens, comment)
    }

    private fun lexOrNull(src: String) = try { lex(src) } catch (e: LexError) { null }

    private fun isOpen(t: Tok) = t.t == T.Op && t.v in setOf("(", "[", "{")
    private fun isClose(t: Tok) = t.t == T.Op && t.v in setOf(")", "]", "}")

    private fun splitTop(tokens: List<Tok>, at: (Tok) -> Boolean): List<List<Tok>> {
        val parts = mutableListOf(mutableListOf<Tok>())
        var depth = 0
        for (t in tokens) {
            if (isOpen(t)) depth++
            if (isClose(t)) depth--
            if (depth == 0 && at(t)) { parts += mutableListOf<Tok>(); continue }
            parts.last() += t
        }
        return parts
    }

    /** tokens[0] is [open] and its partner is the last token. */
    private fun wrapped(tokens: List<Tok>, open: String = "(", close: String = ")"): Boolean {
        if (tokens.isEmpty() || tokens.first().v != open || tokens.last().v != close) return false
        var depth = 0
        for ((i, t) in tokens.withIndex()) {
            if (isOpen(t)) depth++
            if (isClose(t)) depth--
            if (depth == 0 && i < tokens.size - 1) return false
        }
        return true
    }

    // ---------- state ----------

    private class Draft(val id: String, val type: String, val params: MutableMap<String, ParamValue>, var groupId: String?, var disabled: Boolean) {
        var code = StringBuilder() // the text of a Skript block being collected
        fun toBlock() = Block(id = id, type = type, params = params.toMap(), groupId = groupId, disabled = disabled)
    }

    private class Open(val draft: Draft, val indent: Int)

    private val out = mutableListOf<Draft>()
    private val names = mutableMapOf<String, String>()      // output name -> block id
    private val variables = mutableSetOf<String>()
    private val stack = ArrayDeque<Open>()
    private var script: Pair<Draft, Int>? = null              // Skript block being collected, its indent
    private var comment: Draft? = null                        // Poznámka being collected
    /** How many steps got each default output name so far, to name them like ScriptWriter does. */
    private val defaultNames = mutableMapOf<String, Int>()

    private var name: String? = null
    private var icon: String? = null
    private val triggers = mutableSetOf<String>()
    private val events = mutableListOf<EventTrigger>()
    private val conditions = mutableListOf<Block>()
    private var conditionsAny = false

    // ---------- values ----------

    private fun refOf(tokens: List<Tok>): VarRef? {
        if (tokens.size == 1 && tokens[0].t == T.Id) {
            val v = tokens[0].v
            return when {
                v == "input" || v == "vstup" -> VarRef.Input
                v == "clipboard" -> VarRef.Clipboard
                v == "trigger" -> VarRef.Trigger
                names.containsKey(v) -> VarRef.BlockOutput(names.getValue(v))
                v.lowercase() in ScriptWords.RESERVED -> null
                else -> VarRef.Named(v)
            }
        }
        if (tokens.size >= 3 && tokens[0].t == T.Id && tokens[1].v == "(" && tokens.last().v == ")") {
            val fn = tokens[0].v
            val inner = tokens.subList(2, tokens.size - 1)
            if (fn == "date" && inner.isEmpty()) return VarRef.CurrentDate
            if (inner.size == 1 && inner[0].t == T.Str) return when (fn) {
                "event" -> VarRef.Event(inner[0].v)
                "secret" -> VarRef.Secret(inner[0].v)
                "var" -> VarRef.Named(inner[0].v)
                else -> null
            }
        }
        return null
    }

    /** "a" & name & 3 -> a text value, or null when it is not a plain chain of values. */
    private fun richOf(tokens: List<Tok>): ParamValue.Rich? {
        if (tokens.isEmpty()) return null
        val segments = mutableListOf<Segment>()
        for (whole in splitTop(tokens) { it.t == T.Op && it.v == "&" }) {
            var part = whole
            while (wrapped(part)) part = part.subList(1, part.size - 1)
            when {
                part.size == 1 && (part[0].t == T.Str || part[0].t == T.Num) -> if (part[0].v.isNotEmpty()) segments += Segment.Text(part[0].v)
                part.size == 2 && part[0].v == "-" && part[1].t == T.Num -> segments += Segment.Text("-" + part[1].v)
                part.size > 1 && part.any { it.v == "&" } -> segments += (richOf(part) ?: return null).segments
                else -> segments += Segment.Var(refOf(part) ?: return null)
            }
        }
        return ParamValue.Rich(segments)
    }

    private fun plainText(text: String) = ParamValue.Rich(if (text.isEmpty()) emptyList() else listOf(Segment.Text(text)))

    /** Code (a script, an expression) with the names of outputs and the built-in values as chips. */
    private fun codeOf(src: String): ParamValue.Rich {
        val segments = mutableListOf<Segment>()
        fun addText(t: String) {
            if (t.isEmpty()) return
            val last = segments.lastOrNull()
            if (last is Segment.Text) segments[segments.size - 1] = Segment.Text(last.text + t) else segments += Segment.Text(t)
        }
        val re = Regex("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|\\bdate\\(\\s*\\)|\\b(?:event|secret)\\(\\s*\"(?:\\\\.|[^\"\\\\])*\"\\s*\\)|[\\p{L}_][\\p{L}\\p{N}_]*)")
        var last = 0
        for (m in re.findAll(src)) {
            val tk = m.value
            if (tk[0] == '"' || tk[0] == '\'') continue
            if (m.range.first > 0 && src[m.range.first - 1] == '.') continue
            val after = src.getOrNull(m.range.last + 1)
            val ref: VarRef = when {
                tk.startsWith("date(") -> VarRef.CurrentDate
                tk.startsWith("event(") || tk.startsWith("secret(") -> {
                    val arg = tk.substring(tk.indexOf('"') + 1, tk.lastIndexOf('"')).replace("\\\"", "\"").replace("\\\\", "\\")
                    if (tk.startsWith("event")) VarRef.Event(arg) else VarRef.Secret(arg)
                }
                tk == "input" || tk == "vstup" -> VarRef.Input
                tk == "clipboard" -> VarRef.Clipboard
                tk == "trigger" -> VarRef.Trigger
                names.containsKey(tk) && after != '(' -> VarRef.BlockOutput(names.getValue(tk))
                else -> null
            } ?: continue
            addText(src.substring(last, m.range.first))
            segments += Segment.Var(ref)
            last = m.range.last + 1
        }
        addText(src.substring(last))
        return ParamValue.Rich(segments)
    }

    private fun valueFor(p: ParamDef?, tokens: List<Tok>): ParamValue? = when (p) {
        null -> null
        is ParamDef.Text -> richOf(tokens)
        is ParamDef.Choice, is ParamDef.Automation ->
            if (tokens.size == 1 && (tokens[0].t == T.Str || tokens[0].t == T.Num)) ParamValue.Choice(tokens[0].v) else null
        is ParamDef.Number -> {
            val negative = tokens.size == 2 && tokens[0].v == "-"
            val n = if (negative) tokens[1] else tokens.firstOrNull()
            if ((tokens.size == 1 || negative) && n?.t == T.Num) ParamValue.Number((if (negative) -1 else 1) * n.v.toFloat()) else null
        }
        is ParamDef.Toggle ->
            if (tokens.size == 1 && (tokens[0].v == "true" || tokens[0].v == "false")) ParamValue.Bool(tokens[0].v == "true") else null
    }

    // ---------- conditions ----------

    private class Simple(val op: String, val left: ParamValue.Rich, val right: ParamValue.Rich)

    private val opposite = mapOf(
        "equals" to "not_equals", "not_equals" to "equals", "contains" to "not_contains", "not_contains" to "contains",
        "empty" to "not_empty", "not_empty" to "empty", "greater" to "less_eq", "less_eq" to "greater",
        "less" to "greater_eq", "greater_eq" to "less",
    )
    private val comparisons = listOf("==" to "equals", "!=" to "not_equals", ">=" to "greater_eq", "<=" to "less_eq", ">" to "greater", "<" to "less")

    private fun simple(tokens: List<Tok>): Simple? {
        if (tokens.isEmpty()) return null
        if (tokens[0].v == "not" && tokens.getOrNull(1)?.v == "contains") {
            return simple(tokens.drop(1))?.takeIf { it.op == "contains" }?.let { Simple("not_contains", it.left, it.right) }
        }
        if (tokens[0].v == "not" && wrapped(tokens.drop(1))) {
            val inner = simple(tokens.subList(2, tokens.size - 1)) ?: return null
            return opposite[inner.op]?.let { Simple(it, inner.left, inner.right) }
        }
        if (tokens[0].t == T.Id && tokens[0].v in setOf("contains", "starts", "ends", "matches") && wrapped(tokens.drop(1))) {
            val args = splitTop(tokens.subList(2, tokens.size - 1)) { it.v == "," }
            if (args.size != 2) return null
            return Simple(tokens[0].v, richOf(args[0]) ?: return null, richOf(args[1]) ?: return null)
        }
        for ((symbol, op) in comparisons) {
            val parts = splitTop(tokens) { it.t == T.Op && it.v == symbol }
            if (parts.size != 2) continue
            val left = richOf(parts[0]) ?: return null
            val r = parts[1]
            if (r.size == 1 && r[0].t == T.Str && r[0].v.isEmpty() && (op == "equals" || op == "not_equals")) {
                return Simple(if (op == "equals") "empty" else "not_empty", left, ParamValue.Rich(emptyList()))
            }
            return Simple(op, left, richOf(r) ?: return null)
        }
        return null
    }

    private fun conditionParams(tokens: List<Tok>, src: String): Map<String, ParamValue> {
        val s = simple(tokens)
        return if (s != null) mapOf(
            "mode" to ParamValue.Choice("simple"), "left" to s.left, "op" to ParamValue.Choice(s.op), "right" to s.right,
        ) else mapOf("mode" to ParamValue.Choice("expert"), "expression" to codeOf(src.trim()))
    }

    // ---------- blocks ----------

    private fun finish() { script = null; comment = null }

    private fun emit(type: String, explicit: Map<String, ParamValue> = emptyMap(), outputName: String? = null, disabled: Boolean = false, indent: Int = 0): Draft {
        val def = Blocks.get(type)
        val params = mutableMapOf<String, ParamValue>()
        if (def != null) {
            for (p in def.params) params[p.key] = explicit[p.key] ?: p.defaultValue()
        } else params += explicit
        val draft = Draft(UUID.randomUUID().toString(), type, params, null, disabled)
        val kind = def?.kind
        when {
            kind != null && kind.isGroupStart -> {
                draft.groupId = UUID.randomUUID().toString()
                stack.addLast(Open(draft, indent))
            }
            kind == BlockKind.Else || kind == BlockKind.ElseIf || kind == BlockKind.Branch ->
                draft.groupId = stack.lastOrNull()?.draft?.groupId ?: UUID.randomUUID().toString()
            kind == BlockKind.End -> draft.groupId = stack.removeLastOrNull()?.draft?.groupId ?: UUID.randomUUID().toString()
        }
        out += draft
        // The name the step's output goes by: the one written, or the one "Show as script" gives it.
        def?.outputName?.let { output ->
            var base = ScriptWords.identifier(English.texts[output] ?: output).ifEmpty { "value" }
            if (Blocks.get(base) != null || base in ScriptWords.FUNCTIONS) base += "_result"
            val n = (defaultNames[base] ?: 0) + 1
            defaultNames[base] = n
            val default = if (n == 1) base else base + n
            if (outputName == null && !names.containsKey(default) && default !in variables) names[default] = draft.id
        }
        if (outputName != null) names[outputName] = draft.id
        if (type == Blocks.SET_VARIABLE) (params["name"] as? ParamValue.Rich)?.segments?.filterIsInstance<Segment.Text>()
            ?.joinToString("") { it.text }?.trim()?.let { variables += it }
        return draft
    }

    /** block_type(…) or block("type", …) with its parameters. */
    private fun callOf(tokens: List<Tok>): Pair<String, Map<String, ParamValue>>? {
        if (tokens.size < 3 || tokens[0].t != T.Id || !wrapped(tokens.drop(1))) return null
        var type = tokens[0].v
        var inner = tokens.subList(2, tokens.size - 1)
        if (type == "block") {
            if (inner.firstOrNull()?.t != T.Str) return null
            type = inner[0].v
            inner = inner.drop(1)
            if (inner.isNotEmpty()) { if (inner[0].v != ",") return null; inner = inner.drop(1) }
        } else if (type in ScriptWords.SHADOWED) return null
        val def = Blocks.get(type) ?: return null
        val params = mutableMapOf<String, ParamValue>()
        if (wrapped(inner, "{", "}")) {
            for (pair in splitTop(inner.subList(1, inner.size - 1)) { it.v == "," }) {
                if (pair.size < 3 || pair[0].t != T.Id || pair[1].v != ":") return null
                val p = def.param(pair[0].v) ?: return null
                params[p.key] = valueFor(p, pair.drop(2)) ?: return null
            }
        } else if (inner.isNotEmpty()) {
            val args = splitTop(inner) { it.v == "," }
            if (args.size > def.params.size) return null
            for ((i, arg) in args.withIndex()) params[def.params[i].key] = valueFor(def.params[i], arg) ?: return null
        }
        return type to params
    }

    /** One statement; false when the blocks cannot express it. */
    private fun statement(tokens: List<Tok>, src: String, indent: Int, disabled: Boolean, note: String?): Boolean {
        if (tokens.isEmpty()) return false
        val w = tokens[0].takeIf { it.t == T.Id }?.v
        val top = stack.lastOrNull()
        val inSwitch = top != null && top.draft.type == ControlBlocks.SWITCH && indent == top.indent

        if (w == "if" || (w == "else" && tokens.getOrNull(1)?.v == "if")) {
            val cond = tokens.drop(if (w == "if") 1 else 2)
            if (cond.isEmpty()) return false
            if (inSwitch) {
                // `if value == "a" or value == "b"` is a branch of "Vyber podle".
                val options = splitTop(cond) { it.t == T.Id && it.v == "or" }.map { part -> splitTop(part) { it.v == "==" } }
                if (options.all { it.size == 2 }) {
                    val rights = options.map { it[1] }
                    val value = if (rights.all { it.size == 1 && (it[0].t == T.Str || it[0].t == T.Num) }) plainText(rights.joinToString("\n") { it[0].v })
                    else richOf(rights[0])
                    if (value != null) { emit(ControlBlocks.CASE, mapOf("value" to value), disabled = disabled); return true }
                }
            }
            emit(if (w == "if") Blocks.IF else Blocks.ELSE_IF, conditionParams(cond, src.substring(cond[0].s)),
                disabled = w == "if" && disabled, indent = indent)
            return true
        }
        if (w == "else" && tokens.size == 1) { if (disabled) return false; emit(Blocks.ELSE); return true }
        if (w == "while" && tokens.size > 1) {
            emit(Blocks.WHILE, conditionParams(tokens.drop(1), src.substring(tokens[1].s)), disabled = disabled, indent = indent)
            return true
        }
        if (w == "end" && tokens.size == 1) {
            if (disabled || top == null) return false
            val end = when (top.draft.type) {
                Blocks.IF -> Blocks.END_IF
                ControlBlocks.SWITCH -> ControlBlocks.END_SWITCH
                Blocks.WHILE, DataBlocks.FOR_EACH, DataBlocks.REPEAT -> Blocks.END_WHILE
                else -> return false
            }
            emit(end)
            return true
        }
        if (w == "for" && tokens.getOrNull(1)?.t == T.Id && tokens.getOrNull(2)?.v == "in") {
            val itemName = tokens[1].v
            val rest = tokens.drop(3)
            val parallel = ParamValue.Bool(note != null && note in ScriptWords.PARALLEL_NOTE)
            if (rest.firstOrNull()?.v == "range" && wrapped(rest.drop(1))) {
                richOf(rest.subList(2, rest.size - 1))?.let {
                    emit(DataBlocks.REPEAT, mapOf("count" to it, "parallel" to parallel), itemName, disabled, indent); return true
                }
            }
            val list = richOf(rest) ?: return false
            emit(DataBlocks.FOR_EACH, mapOf("list" to list, "parallel" to parallel), itemName, disabled, indent)
            return true
        }
        if ((w == "continue" || w == "break") && tokens.size == 1) {
            emit(DataBlocks.LOOP_CONTROL, mapOf("action" to ParamValue.Choice(w)), disabled = disabled); return true
        }
        if (w == "return") {
            val value = if (tokens.size > 1) richOf(tokens.drop(1)) ?: return false else ParamValue.Rich(emptyList())
            emit("return", mapOf("text" to value), disabled = disabled); return true
        }

        // name = …
        var target: String? = null
        var value = tokens
        if (tokens[0].t == T.Id && tokens.getOrNull(1)?.v == "=" && tokens.size > 2) { target = tokens[0].v; value = tokens.drop(2) }
        callOf(value)?.let { (type, params) ->
            val def = Blocks.get(type)!!
            if (def.kind != BlockKind.Normal || (target != null && def.outputName == null)) return false
            emit(type, params, target, disabled)
            return true
        }
        if (target == null && w == Blocks.SET_VARIABLE && wrapped(tokens.drop(1))) {
            val args = splitTop(tokens.subList(2, tokens.size - 1)) { it.v == "," }
            if (args.size == 2 && args[0].size == 1 && args[0][0].t == T.Str) {
                val v = richOf(args[1]) ?: return false
                emit(Blocks.SET_VARIABLE, mapOf("name" to plainText(args[0][0].v), "value" to v), disabled = disabled)
                return true
            }
            return false
        }
        if (target == null) return false
        // "total = total * 0.9" changes an existing variable: only script code does exactly that.
        if ((names.containsKey(target) || target in variables) && richOf(value) == null) return false
        if (value.firstOrNull()?.v == "calc" && wrapped(value.drop(1))) {
            val expression = richOf(value.subList(2, value.size - 1)) ?: codeOf(src.substring(value[1].e, value.last().s))
            emit("math", mapOf("mode" to ParamValue.Choice("expert"), "expression" to expression), target, disabled)
            return true
        }
        for (op in listOf("+", "-", "*", "/", "%")) {
            val parts = splitTop(value) { it.t == T.Op && it.v == op }
            if (parts.size != 2 || parts[0].isEmpty()) continue
            val a = richOf(parts[0]) ?: continue
            val b = richOf(parts[1]) ?: continue
            emit("math", mapOf("mode" to ParamValue.Choice("simple"), "a" to a, "op" to ParamValue.Choice(op), "b" to b), target, disabled)
            return true
        }
        val rich = richOf(value) ?: return false
        emit(Blocks.SET_VARIABLE, mapOf("name" to plainText(target), "value" to rich), disabled = disabled)
        return true
    }

    /** "# Zkusit  (ve skriptu jen jako bloky)" – exact titles first, then key words in either language. */
    private fun markerOf(body: String): String? {
        val m = Regex("^(.*?)\\s*\\(([^()]*)\\)\\s*$").find(body) ?: return null
        val note = fold(m.groupValues[2])
        if (m.groupValues[2] !in ScriptWords.ONLY_BLOCKS_NOTE && !Regex("blok|block").containsMatchIn(note)) return null
        val title = m.groupValues[1].trim()
        ScriptWords.ONLY_BLOCKS.firstOrNull { type ->
            val def = Blocks.get(type)!!
            title == def.title || title == English.texts[def.title]
        }?.let { return it }
        val t = fold(title)
        val end = Regex("\\b(end|konec|ukonc)").containsMatchIn(t)
        return when {
            Regex("(time ?limit|casov|timeout)").containsMatchIn(t) -> if (end) ControlBlocks.END_TIME_LIMIT else ControlBlocks.TIME_LIMIT
            Regex("(lock|one at a time|jeden po|zamek)").containsMatchIn(t) -> if (end) ControlBlocks.END_LOCK else ControlBlocks.LOCK
            !end && Regex("(branch|vetev|zaroven|at the same time)").containsMatchIn(t) -> ControlBlocks.BRANCH
            Regex("(parallel|soubez)").containsMatchIn(t) -> if (end) ControlBlocks.END_PARALLEL else ControlBlocks.PARALLEL
            !end && Regex("(catch|fail|error|except|selz|chyb)").containsMatchIn(t) -> Blocks.CATCH
            Regex("(try|zkus)").containsMatchIn(t) -> if (end) Blocks.END_TRY else Blocks.TRY
            else -> null
        }
    }

    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

    /** "# @name …", "# @event …": the automation's settings. */
    private fun directive(word: String, rest: String): Boolean {
        fun str(s: String) = if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) lexOrNull(s)?.tokens?.singleOrNull()?.v ?: s.trim('"') else s
        when (word) {
            "name" -> name = str(rest)
            "icon" -> icon = str(rest)
            "triggers" -> triggers += rest.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            "event" -> {
                val m = Regex("^([a-z_]+)\\s*(?:\\((.*)\\))?\\s*$").find(rest) ?: return false
                val type = EventType.of(m.groupValues[1]) ?: return false
                val params = type.defaults.toMutableMap()
                for (a in Regex("(\\w+)\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|[^,\\s)]+)").findAll(m.groupValues[2])) {
                    params[a.groupValues[1]] = str(a.groupValues[2])
                }
                events += EventTrigger(type.key, params)
            }
            "conditions" -> {
                if (rest != "any" && rest != "all") return false
                conditionsAny = rest == "any"
            }
            "condition" -> {
                val call = if (rest.startsWith(Blocks.LAUNCH_CONDITION)) rest else "${Blocks.LAUNCH_CONDITION}($rest)"
                val (type, params) = lexOrNull(call)?.let { callOf(it.tokens) } ?: return false
                if (type != Blocks.LAUNCH_CONDITION) return false
                conditions += Blocks.get(type)!!.newBlock().let { it.copy(params = it.params + params) }
            }
            else -> return false
        }
        return true
    }

    private fun line(raw: String) {
        val indent = raw.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) { comment = null; return }

        Regex("^#\\s*@(\\w+)\\s*(.*)$").find(trimmed)?.let {
            if (directive(it.groupValues[1], it.groupValues[2].trim())) { finish(); return }
        }
        if (trimmed.startsWith("#")) {
            val body = trimmed.removePrefix("#").removePrefix(" ")
            markerOf(body)?.let { finish(); emit(it, indent = indent); return }
            val switch = Blocks.get(ControlBlocks.SWITCH)!!
            for (title in listOf(switch.title, English.texts[switch.title] ?: switch.title)) {
                if (body.startsWith("$title ")) {
                    val value = lexOrNull(body.substring(title.length + 1))?.let { richOf(it.tokens) }
                    if (value != null) { finish(); emit(ControlBlocks.SWITCH, mapOf("value" to value), indent = indent); return }
                }
            }
            // A switched-off step is written as a comment.
            val lexed = lexOrNull(body)
            if (lexed != null && lexed.tokens.isNotEmpty()) {
                val mark = out.size
                val savedStack = stack.toList()
                val savedNames = names.toMap()
                val savedVariables = variables.toSet()
                val savedDefaults = defaultNames.toMap()
                if (statement(lexed.tokens, body, indent, disabled = true, note = lexed.comment)) { finish(); return }
                while (out.size > mark) out.removeAt(out.size - 1)
                stack.clear(); stack.addAll(savedStack)
                names.clear(); names.putAll(savedNames)
                variables.clear(); variables.addAll(savedVariables)
                defaultNames.clear(); defaultNames.putAll(savedDefaults)
            }
            script = null
            val c = comment
            if (c != null) {
                val text = (c.params["text"] as ParamValue.Rich).segments.joinToString("") { (it as Segment.Text).text }
                c.params["text"] = plainText(text + "\n" + body)
            } else comment = emit("comment", mapOf("text" to plainText(body)))
            return
        }

        comment = null
        val lexed = lexOrNull(trimmed)
        if (lexed != null && statement(lexed.tokens, trimmed, indent, disabled = false, note = lexed.comment)) { script = null; return }
        // Anything else is script code; following lines of code go to the same Skript block.
        Regex("^([\\p{L}_][\\p{L}\\p{N}_]*)\\s*[-+*/&]?=(?!=)").find(trimmed)?.let { variables += it.groupValues[1] }
        // The code keeps its own indentation relative to its first line.
        val leading = raw.length - raw.trimStart().length
        val current = script
        if (current != null) {
            current.first.code.append('\n').append(raw.substring(minOf(leading, current.second)))
            current.first.params["code"] = codeOf(current.first.code.toString())
        } else {
            val draft = emit(ControlBlocks.SCRIPT, mapOf("code" to codeOf(trimmed)))
            draft.code.append(trimmed)
            script = draft to leading
        }
    }

    private fun read(fallbackName: String): ExportedAutomation {
        text.removePrefix("﻿").replace("\r", "").split("\n").forEach(::line)
        return ExportedAutomation(
            name = name?.takeIf { it.isNotBlank() } ?: fallbackName,
            icon = icon?.takeIf { Regex("[a-z0-9_]{1,40}").matches(it) } ?: AutomationIcons.DEFAULT,
            triggerProcessText = ScriptWords.PROCESS_TEXT in triggers,
            triggerShare = ScriptWords.SHARE in triggers,
            triggerTile = ScriptWords.TILE in triggers,
            triggerClipboard = ScriptWords.CLIPBOARD in triggers,
            conditions = conditions.toList(),
            conditionsAny = conditionsAny,
            events = events.toList(),
            blocks = out.map { it.toBlock() },
        )
    }
}
