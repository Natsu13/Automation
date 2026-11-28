package cz.natsu.automation.ui.editor

/** The language of a code field: how it is coloured and formatted. */
enum class CodeLanguage(val indent: String) {
    Html("  "), Css("  "), Js("  "),

    /** The language of the "Skript" block (pokud / konec …). */
    Script("    ");

    companion object {
        /** Which language a code parameter holds, by its key. */
        fun forParam(key: String): CodeLanguage = when {
            key.contains("html") -> Html
            key == "script" -> Js
            else -> Script
        }
    }
}

/** What a piece of code is, for its colour. */
enum class CodeKind {
    Keyword, Str, Comment, Number, Tag, Attribute, Function, Punctuation,

    /** data-tap, data-run and data-input of a widget, with their values: what reacts to a tap. */
    Action,
}

/**
 * Colouring and formatting of code fields. Works on the edited text, where a variable chip is a single
 * private-use character: it counts as part of a word, so it is never split or moved apart from its place.
 */
object CodeTools {
    private val jsKeywords = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "break", "continue",
        "new", "this", "true", "false", "null", "undefined", "typeof", "instanceof", "in", "of", "switch", "case",
        "default", "try", "catch", "finally", "throw", "async", "await", "class", "extends",
    )
    private val scriptKeywords = setOf(
        "if", "pokud", "else", "jinak", "end", "konec", "while", "dokud", "for", "pro", "in", "v",
        "return", "vrat", "vrať", "break", "ukonci", "ukonči", "continue", "pokracuj", "pokračuj",
        "and", "zaroven", "zároveň", "or", "nebo", "not", "true", "false", "null",
    )

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || ChipCodec.isChipChar(c)

    // ---------- colours ----------

    fun tokens(text: String, language: CodeLanguage): List<Pair<IntRange, CodeKind>> {
        val out = mutableListOf<Pair<IntRange, CodeKind>>()
        when (language) {
            CodeLanguage.Html -> html(text, 0, text.length, out)
            CodeLanguage.Css -> css(text, 0, text.length, out)
            CodeLanguage.Js -> code(text, 0, text.length, jsKeywords, hashComments = false, out)
            CodeLanguage.Script -> code(text, 0, text.length, scriptKeywords, hashComments = true, out)
        }
        return out
    }

    /** JavaScript or the script language: strings, comments, numbers, keywords and function names. */
    private fun code(t: String, from: Int, to: Int, keywords: Set<String>, hashComments: Boolean, out: MutableList<Pair<IntRange, CodeKind>>) {
        var i = from
        while (i < to) {
            val c = t[i]
            when {
                (c == '/' && t.getOrNull(i + 1) == '/') || (hashComments && c == '#') -> {
                    val end = t.indexOf('\n', i).let { if (it < 0 || it > to) to else it }
                    out += (i until end) to CodeKind.Comment; i = end
                }
                c == '/' && t.getOrNull(i + 1) == '*' -> {
                    val end = t.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > to) to else it + 2 }
                    out += (i until end) to CodeKind.Comment; i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < to && t[j] != c) { if (t[j] == '\\') j++; j++ }
                    val end = minOf(j + 1, to)
                    out += (i until end) to CodeKind.Str; i = end
                }
                c.isDigit() -> {
                    var j = i
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '.')) j++
                    out += (i until j) to CodeKind.Number; i = j
                }
                isWordChar(c) -> {
                    var j = i
                    while (j < to && isWordChar(t[j])) j++
                    val word = t.substring(i, j)
                    var k = j
                    while (k < to && t[k] == ' ') k++
                    when {
                        word.lowercase() in keywords -> out += (i until j) to CodeKind.Keyword
                        k < to && t[k] == '(' && word.none { ChipCodec.isChipChar(it) } -> out += (i until j) to CodeKind.Function
                    }
                    i = j
                }
                c in "{}()[];" -> { out += (i..i) to CodeKind.Punctuation; i++ }
                else -> i++
            }
        }
    }

    private fun css(t: String, from: Int, to: Int, out: MutableList<Pair<IntRange, CodeKind>>) {
        var i = from
        var inBlock = false
        while (i < to) {
            val c = t[i]
            when {
                c == '/' && t.getOrNull(i + 1) == '*' -> {
                    val end = t.indexOf("*/", i + 2).let { if (it < 0 || it + 2 > to) to else it + 2 }
                    out += (i until end) to CodeKind.Comment; i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < to && t[j] != c) j++
                    val end = minOf(j + 1, to)
                    out += (i until end) to CodeKind.Str; i = end
                }
                c == '{' -> { inBlock = true; out += (i..i) to CodeKind.Punctuation; i++ }
                c == '}' -> { inBlock = false; out += (i..i) to CodeKind.Punctuation; i++ }
                !inBlock && !c.isWhitespace() -> {
                    // A selector up to "{".
                    var j = i
                    while (j < to && t[j] != '{' && t[j] != '}') j++
                    out += (i until j) to CodeKind.Tag; i = j
                }
                inBlock && (c.isLetter() || c == '-') -> {
                    var j = i
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '-')) j++
                    var k = j
                    while (k < to && t[k] == ' ') k++
                    // "name:" is a property; after the colon comes its value.
                    if (k < to && t[k] == ':') out += (i until j) to CodeKind.Attribute
                    i = j
                }
                inBlock && c.isDigit() -> {
                    var j = i
                    while (j < to && (t[j].isLetterOrDigit() || t[j] == '.' || t[j] == '%')) j++
                    out += (i until j) to CodeKind.Number; i = j
                }
                else -> i++
            }
        }
    }

    /** Attributes that make a part of an HTML widget tappable. */
    private val actionAttributes = setOf("data-tap", "data-run", "data-input")

    private fun html(t: String, from: Int, to: Int, out: MutableList<Pair<IntRange, CodeKind>>) {
        var i = from
        while (i < to) {
            if (t.startsWith("<!--", i)) {
                val end = t.indexOf("-->", i + 4).let { if (it < 0) to else minOf(it + 3, to) }
                out += (i until end) to CodeKind.Comment; i = end; continue
            }
            if (t[i] != '<' || i + 1 >= to || !(t[i + 1].isLetter() || t[i + 1] == '/' || t[i + 1] == '!')) { i++; continue }
            // A tag: its name, then attributes and their values.
            val tagStart = i
            var j = i + 1
            if (t[j] == '/' || t[j] == '!') j++
            while (j < to && (t[j].isLetterOrDigit() || t[j] == '-')) j++
            val name = t.substring(i + 1, j).trimStart('/', '!').lowercase()
            out += (i until j) to CodeKind.Tag
            // The value after data-tap / data-run / data-input is marked the same way as the attribute.
            var action = false
            while (j < to && t[j] != '>') {
                val c = t[j]
                when {
                    c == '"' || c == '\'' -> {
                        var k = j + 1
                        while (k < to && t[k] != c) k++
                        val end = minOf(k + 1, to)
                        out += (j until end) to (if (action) CodeKind.Action else CodeKind.Str); j = end
                        action = false
                    }
                    c.isLetter() || c == '-' || c == '_' || c == ':' || c == '@' -> {
                        var k = j
                        while (k < to && (t[k].isLetterOrDigit() || t[k] in "-_:@.")) k++
                        action = t.substring(j, k).lowercase() in actionAttributes
                        out += (j until k) to (if (action) CodeKind.Action else CodeKind.Attribute); j = k
                    }
                    else -> j++
                }
            }
            if (j < to) { out += (j..j) to CodeKind.Tag; j++ }
            i = j
            // The inside of <script> and <style> is code of its own.
            val opening = t.getOrNull(tagStart + 1) != '/' && t.getOrNull(i - 2) != '/'
            val closing = if (opening && (name == "script" || name == "style")) "</$name" else null
            if (closing != null) {
                val end = t.indexOf(closing, i, ignoreCase = true).let { if (it < 0) to else it }
                if (name == "script") code(t, i, end, jsKeywords, hashComments = false, out) else css(t, i, end, out)
                i = end
            }
        }
    }

    // ---------- formatting ----------

    fun format(text: String, language: CodeLanguage): String = when (language) {
        CodeLanguage.Html -> formatHtml(text)
        CodeLanguage.Css -> reindentBraces(breakCss(text), language.indent)
        CodeLanguage.Js -> reindentBraces(breakJs(text), language.indent)
        CodeLanguage.Script -> reindentScript(text, language.indent)
    }

    private val blockTags = setOf(
        "html", "head", "body", "div", "section", "article", "header", "footer", "main", "nav", "aside",
        "p", "ul", "ol", "li", "table", "thead", "tbody", "tr", "td", "th", "form", "h1", "h2", "h3", "h4",
        "h5", "h6", "title", "meta", "link", "style", "script", "select", "option", "label", "button",
        "svg", "canvas", "details", "summary", "figure", "blockquote", "hr", "br", "input", "img", "textarea", "pre",
    )
    private val voidTags = setOf("meta", "link", "br", "hr", "img", "input", "area", "base", "col", "source", "wbr")

    /** Every block element on a line of its own, indented by nesting; inline text and tags stay together. */
    private fun formatHtml(t: String): String {
        val out = StringBuilder()
        var level = 0
        val line = StringBuilder()
        fun flush() {
            val trimmed = line.toString().trim()
            if (trimmed.isNotEmpty()) out.append(CodeLanguage.Html.indent.repeat(level)).append(trimmed).append('\n')
            line.clear()
        }
        var i = 0
        while (i < t.length) {
            if (t.startsWith("<!--", i)) {
                val end = t.indexOf("-->", i).let { if (it < 0) t.length else it + 3 }
                flush(); line.append(t, i, end); flush(); i = end; continue
            }
            if (t[i] == '<' && i + 1 < t.length && (t[i + 1].isLetter() || t[i + 1] == '/' || t[i + 1] == '!')) {
                val end = tagEnd(t, i)
                val tag = t.substring(i, end)
                val closing = tag.startsWith("</")
                val name = tag.drop(if (closing) 2 else 1).takeWhile { it.isLetterOrDigit() || it == '-' }.lowercase()
                val selfClosing = tag.endsWith("/>") || name in voidTags || tag.startsWith("<!")
                i = end
                if (tag.startsWith("<!")) { flush(); line.append(tag); flush(); continue }
                if (name !in blockTags) { line.append(tag); continue }
                if (closing) { flush(); level = maxOf(0, level - 1); line.append(tag); flush(); continue }
                flush(); line.append(tag)
                if (selfClosing) { flush(); continue }
                // Raw content: formatted as its own language, or kept as it is.
                if (name == "script" || name == "style" || name == "pre" || name == "textarea") {
                    val close = t.indexOf("</$name", i, ignoreCase = true).let { if (it < 0) t.length else it }
                    val inner = t.substring(i, close)
                    i = close
                    if (name == "pre" || name == "textarea") {
                        // Kept exactly as it is, with its closing tag on the same line.
                        val end = if (close < t.length) tagEnd(t, close) else close
                        line.append(inner).append(t, close, end)
                        i = end
                        flush()
                        continue
                    }
                    flush()
                    // The closing tag steps back out like any other block.
                    level++
                    val formatted = if (name == "script") format(inner, CodeLanguage.Js) else format(inner, CodeLanguage.Css)
                    for (l in formatted.lines()) if (l.isNotBlank()) out.append(CodeLanguage.Html.indent.repeat(level)).append(l).append('\n')
                    continue
                }
                flush(); level++
                continue
            }
            // Text: runs of white space become one space.
            val c = t[i]
            if (c.isWhitespace()) { if (line.isNotEmpty() && !line.last().isWhitespace()) line.append(' ') } else line.append(c)
            i++
        }
        flush()
        return out.toString().trimEnd()
    }

    /** End of the tag starting at [start] (after ">"), quotes inside attributes respected. */
    private fun tagEnd(t: String, start: Int): Int {
        var i = start + 1
        var quote: Char? = null
        while (i < t.length) {
            val c = t[i]
            if (quote != null) { if (c == quote) quote = null } else if (c == '"' || c == '\'') quote = c else if (c == '>') return i + 1
            i++
        }
        return t.length
    }

    /** CSS: a line for every rule and declaration. */
    private fun breakCss(t: String): String = buildString {
        var i = 0
        var quote: Char? = null
        while (i < t.length) {
            val c = t[i]
            if (quote != null) { append(c); if (c == quote) quote = null; i++; continue }
            if (t.startsWith("/*", i)) { val e = t.indexOf("*/", i).let { if (it < 0) t.length else it + 2 }; append(t, i, e); i = e; continue }
            when (c) {
                '"', '\'' -> { quote = c; append(c) }
                '{' -> append(" {\n")
                ';' -> append(";\n")
                '}' -> append("\n}\n")
                else -> append(c)
            }
            i++
        }
    }

    /** JavaScript: a line after "{" and ";" (not inside brackets, strings or comments), and around "}". */
    private fun breakJs(t: String): String = buildString {
        var i = 0
        var depth = 0
        while (i < t.length) {
            val c = t[i]
            when {
                t.startsWith("//", i) -> { val e = t.indexOf('\n', i).let { if (it < 0) t.length else it }; append(t, i, e); i = e; continue }
                t.startsWith("/*", i) -> { val e = t.indexOf("*/", i).let { if (it < 0) t.length else it + 2 }; append(t, i, e); i = e; continue }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < t.length && t[j] != c) { if (t[j] == '\\') j++; j++ }
                    val e = minOf(j + 1, t.length)
                    append(t, i, e); i = e; continue
                }
                c == '(' || c == '[' -> { depth++; append(c) }
                c == ')' || c == ']' -> { depth = maxOf(0, depth - 1); append(c) }
                c == '{' -> append("{\n")
                c == '}' -> {
                    append(if (trimEnd { it == ' ' || it == '\t' }.endsWith('\n')) "}" else "\n}")
                    // "}" followed by ")", ",", ";" or "else" stays on its line.
                    var k = i + 1
                    while (k < t.length && t[k] == ' ') k++
                    val next = t.getOrNull(k)
                    if (next != null && next !in "),;." && !t.startsWith("else", k) && !t.startsWith("catch", k) &&
                        !t.startsWith("finally", k) && next != '\n') append('\n')
                }
                c == ';' && depth == 0 -> append(";\n")
                else -> append(c)
            }
            i++
        }
    }

    /** Lines trimmed and indented by the braces before them; at most one empty line in a row. */
    private fun reindentBraces(t: String, unit: String): String {
        val out = StringBuilder()
        var level = 0
        var blank = false
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { if (!blank && out.isNotEmpty()) { out.append('\n'); blank = true }; continue }
            blank = false
            val opens = braceBalance(line)
            val leadingClose = line.takeWhile { it == '}' || it == ')' || it == ']' }.count { it == '}' }
            val here = maxOf(0, level - leadingClose)
            out.append(unit.repeat(here)).append(line).append('\n')
            level = maxOf(0, level + opens)
        }
        return out.toString().trimEnd()
    }

    /** "{" minus "}" in a line, without the ones in strings and comments. */
    private fun braceBalance(line: String): Int {
        var n = 0
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (quote != null) { if (c == '\\') i++ else if (c == quote) quote = null }
            else when {
                line.startsWith("//", i) -> return n
                c == '"' || c == '\'' || c == '`' -> quote = c
                c == '{' -> n++
                c == '}' -> n--
            }
            i++
        }
        return n
    }

    private val scriptOpeners = setOf(
        "if", "pokud", "while", "dokud", "for", "pro", "script", "skript", "try", "zkus", "parallel", "soubezne",
        "lock", "postupne", "time_limit", "limit", "html_window",
    )
    private val scriptMiddles = setOf("else", "jinak", "catch", "kdyz_selze", "branch", "vetev")
    private val scriptEnds = setOf("end", "konec")

    /** The script language: indented by its blocks (pokud … jinak … konec). */
    private fun reindentScript(t: String, unit: String): String {
        val out = StringBuilder()
        var level = 0
        for (raw in t.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) { out.append('\n'); continue }
            // "x = try(…)" opens a group as well.
            val statement = Regex("^[\\p{L}_][\\p{L}\\p{N}_]*\\s*=\\s*(?!=)").find(line)?.let { line.substring(it.range.last + 1) } ?: line
            val word = statement.takeWhile { isWordChar(it) }.lowercase()
            when {
                word in scriptEnds -> { level = maxOf(0, level - 1); out.append(unit.repeat(level)).append(line) }
                word in scriptMiddles -> out.append(unit.repeat(maxOf(0, level - 1))).append(line)
                word in scriptOpeners -> { out.append(unit.repeat(level)).append(line); level++ }
                else -> out.append(unit.repeat(level)).append(line)
            }
            out.append('\n')
        }
        return out.toString().trimEnd()
    }

    /** Leading white space of the line the cursor is on, plus one step after an opening line (Enter keeps the indent). */
    fun indentAfter(text: String, cursor: Int, language: CodeLanguage): String {
        val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0 || cursor == 0) 0 else it + 1 }
        val line = text.substring(start, cursor)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val trimmed = line.trim()
        val opens = when (language) {
            CodeLanguage.Script -> {
                val word = trimmed.replace(Regex("^[\\p{L}_][\\p{L}\\p{N}_]*\\s*=\\s*(?!=)"), "").takeWhile { isWordChar(it) }.lowercase()
                word in scriptOpeners || word in scriptMiddles
            }
            CodeLanguage.Html -> trimmed.endsWith("{") ||
                (trimmed.endsWith(">") && trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.endsWith("/>") &&
                    trimmed.lastIndexOf("</") <= 0 &&
                    trimmed.drop(1).takeWhile { it.isLetterOrDigit() }.lowercase().let { it in blockTags && it !in voidTags })
            else -> trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")
        }
        return indent + if (opens) language.indent else ""
    }
}
