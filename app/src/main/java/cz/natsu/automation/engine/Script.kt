package cz.natsu.automation.engine

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The small language of the "Skript" block and of conditions written as an expression.
 *
 * The text is parsed once into a tree ([compile], cached) and then evaluated ([run]).
 * Values are numbers, texts, true / false, lists, objects and null; texts that hold a
 * number or JSON (outputs of other blocks) behave like numbers / lists / objects.
 *
 * ```
 * celkem = cena * 1.21
 * pokud celkem > 500 nebo stav == "vip"
 *     vrat "drahé: " & round(celkem, 2)
 * konec
 * pro polozka v odpoved.body.items
 *     soucet = soucet + polozka.price
 * konec
 * ```
 *
 * It is separate from [Calc] (the calculator of the "Vypočítat" block), but can use it:
 * `calc("1 000 * 1,21")`.
 */
object Script {
    /** Variable chips are passed to the lexer as CHIP_START + index + CHIP_END. */
    const val CHIP_START = ''
    const val CHIP_END = ''

    /** Returned by [Host.get] for a variable that does not exist. */
    val MISSING = Any()

    private const val MAX_STEPS = 5_000_000
    private const val MAX_RANGE = 100_000

    /** What the script sees of the automation it runs in. */
    interface Host {
        /** A variable defined outside the script, or [MISSING]. */
        fun get(name: String): Any?

        /** The script assigned a variable; later steps of the automation can use it. */
        fun set(name: String, value: Any?)

        /** Value of the variable chip number [index] inserted into the code. */
        fun chip(index: Int): String

        /** false = the run was stopped. */
        fun active(): Boolean = true
    }

    class Program internal constructor(internal val body: List<Stmt>)

    // ---------- public API ----------

    private val cache = object : LinkedHashMap<String, Program>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Program>?) = size > 32
    }

    /** Parses [code]; throws [BlockException] with the line of the mistake. */
    fun compile(code: String): Program = synchronized(cache) {
        cache[code] ?: Parser(Lexer(code).tokens()).program().also { cache[code] = it }
    }

    /** Runs the program: the value of `vrat` / `return`, or of the last statement. */
    fun run(program: Program, host: Host): Any? = Interpreter(host).run(program)

    fun run(code: String, host: Host): Any? = run(compile(code), host)

    /** Text form of a value, as other blocks get it. */
    fun text(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> value.toString()
        is Double -> formatNumber(value)
        is String -> value
        else -> toJson(value).toString()
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0
        is String -> value.isNotBlank() && value.trim().let { it != "false" && it != "0" }
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    // ---------- values ----------

    private fun number(value: Any?): Double? = when (value) {
        is Double -> value
        is Boolean -> if (value) 1.0 else 0.0
        is String -> parseNumber(value)?.takeIf { it.isFinite() && value.trim().first().let { c -> c.isDigit() || c in "+-." } }
        else -> null
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Double ->
            if (value == floor(value) && abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::toJson))
        is Map<*, *> -> JsonObject(value.entries.associate { text(it.key) to toJson(it.value) })
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive ->
            if (element.isString) element.content
            else element.booleanOrNull ?: element.doubleOrNull ?: element.content
        is JsonArray -> element.mapTo(mutableListOf(), ::fromJson)
        is JsonObject -> element.entries.associateTo(LinkedHashMap()) { it.key to fromJson(it.value) }
    }

    /** A text holding a JSON list / object (an output of another block) as that list / object. */
    private fun structured(value: Any?): Any? {
        if (value !is String) return value
        val trimmed = value.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return value
        val parsed = JsonTools.parseOrNull(value) ?: return value
        return if (parsed is JsonArray || parsed is JsonObject) fromJson(parsed) else value
    }

    private fun same(left: Any?, right: Any?): Boolean {
        val a = number(left)
        val b = number(right)
        if (a != null && b != null) return a == b
        if (left is List<*> || left is Map<*, *> || right is List<*> || right is Map<*, *>) {
            return toJson(structured(left)) == toJson(structured(right))
        }
        return text(left) == text(right)
    }

    private fun compare(left: Any?, right: Any?): Int {
        val a = number(left)
        val b = number(right)
        return if (a != null && b != null) a.compareTo(b) else text(left).compareTo(text(right))
    }

    private fun fail(message: String): Nothing = throw BlockException(message)

    // ---------- lexer ----------

    internal enum class T { Num, Str, Ident, Chip, Op, NewLine, End }

    /** [parts] of a text: String pieces and Int chip indexes. */
    internal class Token(
        val type: T,
        val text: String,
        val line: Int,
        val number: Double = 0.0,
        val parts: List<Any> = emptyList(),
    )

    private class Lexer(private val code: String) {
        private var pos = 0
        private var line = 1
        private var depth = 0
        private val out = mutableListOf<Token>()

        private fun error(message: String): Nothing = fail("řádek $line: $message")

        private fun chipIndex(): Int {
            // pos is at CHIP_START
            val end = code.indexOf(CHIP_END, pos)
            if (end < 0) error("poškozená proměnná")
            val index = code.substring(pos + 1, end).toIntOrNull() ?: error("poškozená proměnná")
            pos = end + 1
            return index
        }

        fun tokens(): List<Token> {
            while (pos < code.length) {
                val c = code[pos]
                when {
                    c == '\n' -> {
                        if (depth == 0) out += Token(T.NewLine, "\n", line)
                        line++
                        pos++
                    }
                    c.isWhitespace() -> pos++
                    c == ';' -> {
                        out += Token(T.NewLine, ";", line)
                        pos++
                    }
                    c == '#' || (c == '/' && code.getOrNull(pos + 1) == '/') -> {
                        while (pos < code.length && code[pos] != '\n') pos++
                    }
                    c.isDigit() -> number()
                    c == '"' || c == '\'' -> string(c)
                    c == CHIP_START -> {
                        val index = chipIndex()
                        out += Token(T.Chip, "proměnná", line, number = index.toDouble())
                    }
                    c.isLetter() || c == '_' -> {
                        val start = pos
                        while (pos < code.length && (code[pos].isLetterOrDigit() || code[pos] == '_')) pos++
                        out += Token(T.Ident, code.substring(start, pos), line)
                    }
                    else -> operator()
                }
            }
            out += Token(T.End, "konec skriptu", line)
            return out
        }

        private fun number() {
            val start = pos
            while (pos < code.length && code[pos].isDigit()) pos++
            if (code.getOrNull(pos) == '.' && code.getOrNull(pos + 1)?.isDigit() == true) {
                pos++
                while (pos < code.length && code[pos].isDigit()) pos++
            }
            if (code.getOrNull(pos)?.lowercaseChar() == 'e') {
                var p = pos + 1
                if (code.getOrNull(p) == '+' || code.getOrNull(p) == '-') p++
                if (code.getOrNull(p)?.isDigit() == true) {
                    while (p < code.length && code[p].isDigit()) p++
                    pos = p
                }
            }
            val text = code.substring(start, pos)
            out += Token(T.Num, text, line, number = text.toDoubleOrNull() ?: error("\"$text\" není číslo"))
        }

        private fun string(quote: Char) {
            val startLine = line
            pos++
            val parts = mutableListOf<Any>()
            val current = StringBuilder()
            while (true) {
                if (pos >= code.length) fail("řádek $startLine: text není ukončený uvozovkou $quote")
                val c = code[pos]
                when {
                    c == quote -> {
                        pos++
                        break
                    }
                    c == '\\' && pos + 1 < code.length -> {
                        current.append(
                            when (val next = code[pos + 1]) {
                                'n' -> '\n'
                                't' -> '\t'
                                else -> next
                            }
                        )
                        pos += 2
                    }
                    c == CHIP_START -> {
                        if (current.isNotEmpty()) parts += current.toString()
                        current.clear()
                        parts += chipIndex()
                    }
                    else -> {
                        if (c == '\n') line++
                        current.append(c)
                        pos++
                    }
                }
            }
            if (current.isNotEmpty() || parts.isEmpty()) parts += current.toString()
            out += Token(T.Str, "text", startLine, parts = parts)
        }

        private fun operator() {
            val two = if (pos + 1 < code.length) code.substring(pos, pos + 2) else ""
            val op = when {
                two in TWO_CHAR -> two
                code[pos] in ONE_CHAR -> code[pos].toString()
                else -> error("nečekaný znak \"${code[pos]}\"")
            }
            when (op) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> if (depth > 0) depth--
            }
            pos += op.length
            out += Token(T.Op, op, line)
        }

        companion object {
            val TWO_CHAR = setOf("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=")
            const val ONE_CHAR = "+-*/%^<>=!()[]{},.:&"
        }
    }

    // ---------- tree ----------

    internal sealed class Expr(val line: Int)
    private class Lit(line: Int, val value: Any?) : Expr(line)
    private class Interpolated(line: Int, val parts: List<Any>) : Expr(line)
    private class Chip(line: Int, val index: Int) : Expr(line)
    private class Variable(line: Int, val name: String) : Expr(line)
    private class Unary(line: Int, val op: String, val operand: Expr) : Expr(line)
    private class Binary(line: Int, val op: String, val left: Expr, val right: Expr) : Expr(line)
    private class Logic(line: Int, val and: Boolean, val left: Expr, val right: Expr) : Expr(line)
    private class Index(line: Int, val base: Expr, val index: Expr) : Expr(line)
    private class Member(line: Int, val base: Expr, val name: String) : Expr(line)
    private class Call(line: Int, val name: String, val args: List<Expr>) : Expr(line)
    private class ListLit(line: Int, val items: List<Expr>) : Expr(line)
    private class MapLit(line: Int, val entries: List<Pair<Expr, Expr>>) : Expr(line)

    internal sealed class Stmt(val line: Int)
    private class ExprStmt(line: Int, val expr: Expr) : Stmt(line)
    private class Assign(line: Int, val target: Expr, val op: String, val value: Expr) : Stmt(line)
    private class If(line: Int, val branches: List<Pair<Expr, List<Stmt>>>, val otherwise: List<Stmt>?) : Stmt(line)
    private class While(line: Int, val condition: Expr, val body: List<Stmt>) : Stmt(line)
    private class For(line: Int, val name: String, val list: Expr, val body: List<Stmt>) : Stmt(line)
    private class Return(line: Int, val value: Expr?) : Stmt(line)
    private class Jump(line: Int, val exit: Boolean) : Stmt(line)

    // ---------- parser ----------

    private val IF = setOf("if", "pokud")
    private val ELSE = setOf("else", "jinak")
    private val END = setOf("end", "konec")
    private val WHILE = setOf("while", "dokud")
    private val FOR = setOf("for", "pro")
    private val IN = setOf("in", "v")
    private val RETURN = setOf("return", "vrat", "vrať")
    private val BREAK = setOf("break", "ukonci", "ukonči")
    private val CONTINUE = setOf("continue", "pokracuj", "pokračuj")
    private val AND = setOf("and", "zaroven", "zároveň")
    private val OR = setOf("or", "nebo")
    private val NOT = setOf("not")
    private val ASSIGN_OPS = setOf("=", "+=", "-=", "*=", "/=")

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        private fun peek() = tokens[pos]
        private fun next() = tokens[pos++]
        private fun error(token: Token, message: String): Nothing = fail("řádek ${token.line}: $message")

        private fun isWord(words: Set<String>, token: Token = peek()) =
            token.type == T.Ident && token.text.lowercase() in words

        private fun isOp(op: String) = peek().type == T.Op && peek().text == op

        private fun takeOp(op: String): Boolean {
            if (!isOp(op)) return false
            pos++
            return true
        }

        private fun expectOp(op: String) {
            if (!takeOp(op)) error(peek(), "čekal jsem \"$op\", ale je tu \"${peek().text}\"")
        }

        private fun skipNewLines() {
            while (peek().type == T.NewLine) pos++
        }

        private fun endOfStatement() {
            val token = peek()
            // "pokud x vrat 1 konec" on one line is fine too.
            if (token.type != T.NewLine && token.type != T.End && !isWord(END) && !isWord(ELSE)) {
                error(token, "nečekané \"${token.text}\" (chybí operátor nebo nový řádek?)")
            }
        }

        fun program(): Program {
            val body = block()
            val token = peek()
            if (token.type != T.End) error(token, "\"${token.text}\" tu nemá co uzavírat")
            return Program(body)
        }

        /** Statements up to `konec` / `jinak` / the end of the script. */
        private fun block(): List<Stmt> {
            val statements = mutableListOf<Stmt>()
            skipNewLines()
            while (peek().type != T.End && !isWord(END) && !isWord(ELSE)) {
                statements += statement()
                skipNewLines()
            }
            return statements
        }

        private fun expectEnd(opened: Token) {
            if (!isWord(END)) error(peek(), "chybí \"konec\" k \"${opened.text}\" z řádku ${opened.line}")
            pos++
        }

        private fun statement(): Stmt {
            val token = peek()
            val statement: Stmt = when {
                isWord(IF) -> ifStatement()
                isWord(WHILE) -> {
                    next()
                    val condition = expression()
                    takeOp(":")
                    val body = block()
                    expectEnd(token)
                    While(token.line, condition, body)
                }
                isWord(FOR) -> {
                    next()
                    val name = next().takeIf { it.type == T.Ident }
                        ?: error(token, "za \"${token.text}\" má být název proměnné")
                    if (!isWord(IN)) error(peek(), "čekal jsem \"v\" (pro položka v seznam)")
                    next()
                    val list = expression()
                    takeOp(":")
                    val body = block()
                    expectEnd(token)
                    For(token.line, name.text, list, body)
                }
                isWord(RETURN) -> {
                    next()
                    val value = if (peek().type == T.NewLine || peek().type == T.End) null else expression()
                    Return(token.line, value)
                }
                isWord(BREAK) -> {
                    next()
                    Jump(token.line, exit = true)
                }
                isWord(CONTINUE) -> {
                    next()
                    Jump(token.line, exit = false)
                }
                else -> {
                    val expr = expression()
                    val op = peek()
                    if (op.type == T.Op && op.text in ASSIGN_OPS) {
                        if (expr !is Variable && expr !is Index && expr !is Member) {
                            error(op, "do tohohle nejde přiřadit (vlevo od = má být proměnná)")
                        }
                        next()
                        Assign(token.line, expr, op.text, expression())
                    } else {
                        ExprStmt(token.line, expr)
                    }
                }
            }
            endOfStatement()
            return statement
        }

        private fun ifStatement(): Stmt {
            val start = next()
            val branches = mutableListOf<Pair<Expr, List<Stmt>>>()
            var otherwise: List<Stmt>? = null
            var condition = expression()
            while (true) {
                takeOp(":")
                branches += condition to block()
                if (!isWord(ELSE)) break
                next()
                if (isWord(IF)) {
                    next()
                    condition = expression()
                } else {
                    takeOp(":")
                    otherwise = block()
                    break
                }
            }
            expectEnd(start)
            return If(start.line, branches, otherwise)
        }

        fun expression(): Expr = or()

        private fun or(): Expr {
            var left = and()
            while (isWord(OR) || isOp("||")) {
                val token = next()
                left = Logic(token.line, false, left, and())
            }
            return left
        }

        private fun and(): Expr {
            var left = not()
            while (isWord(AND) || isOp("&&")) {
                val token = next()
                left = Logic(token.line, true, left, not())
            }
            return left
        }

        private fun not(): Expr {
            if (isWord(NOT) || isOp("!")) {
                val token = next()
                return Unary(token.line, "!", not())
            }
            return comparison()
        }

        private fun comparison(): Expr {
            var left = concat()
            while (peek().type == T.Op && peek().text in COMPARE) {
                val token = next()
                left = Binary(token.line, token.text, left, concat())
            }
            return left
        }

        private fun concat(): Expr {
            var left = additive()
            while (isOp("&")) {
                val token = next()
                left = Binary(token.line, "&", left, additive())
            }
            return left
        }

        private fun additive(): Expr {
            var left = multiplicative()
            while (isOp("+") || isOp("-")) {
                val token = next()
                left = Binary(token.line, token.text, left, multiplicative())
            }
            return left
        }

        private fun multiplicative(): Expr {
            var left = unary()
            while (isOp("*") || isOp("/") || isOp("%")) {
                val token = next()
                left = Binary(token.line, token.text, left, unary())
            }
            return left
        }

        private fun unary(): Expr {
            if (isOp("-")) {
                val token = next()
                return Unary(token.line, "-", unary())
            }
            if (isOp("+")) {
                next()
                return unary()
            }
            val base = postfix()
            if (isOp("^")) {
                val token = next()
                return Binary(token.line, "^", base, unary())
            }
            return base
        }

        private fun postfix(): Expr {
            var expr = primary()
            while (true) {
                val token = peek()
                expr = when {
                    takeOp("[") -> {
                        val index = expression()
                        expectOp("]")
                        Index(token.line, expr, index)
                    }
                    takeOp(".") -> {
                        val name = next()
                        if (name.type != T.Ident && name.type != T.Num) error(name, "za tečkou má být název")
                        Member(token.line, expr, name.text)
                    }
                    else -> return expr
                }
            }
        }

        private fun arguments(close: String): List<Expr> {
            val items = mutableListOf<Expr>()
            if (takeOp(close)) return items
            while (true) {
                items += expression()
                if (takeOp(close)) return items
                if (!takeOp(",")) error(peek(), "čekal jsem \",\" nebo \"$close\", ale je tu \"${peek().text}\"")
            }
        }

        private fun primary(): Expr {
            val token = next()
            return when (token.type) {
                T.Num -> Lit(token.line, token.number)
                T.Str ->
                    if (token.parts.all { it is String }) Lit(token.line, token.parts.joinToString(""))
                    else Interpolated(token.line, token.parts)
                T.Chip -> Chip(token.line, token.number.toInt())
                T.Ident -> when (token.text.lowercase()) {
                    "true", "ano" -> Lit(token.line, true)
                    "false" -> Lit(token.line, false)
                    "null", "nic" -> Lit(token.line, null)
                    else ->
                        if (takeOp("(")) Call(token.line, token.text.lowercase(), arguments(")"))
                        else Variable(token.line, token.text)
                }
                T.Op -> when (token.text) {
                    "(" -> expression().also { expectOp(")") }
                    "[" -> ListLit(token.line, arguments("]"))
                    "{" -> {
                        val entries = mutableListOf<Pair<Expr, Expr>>()
                        if (!takeOp("}")) while (true) {
                            val key = next()
                            val keyExpr: Expr = when (key.type) {
                                T.Ident, T.Num -> Lit(key.line, key.text)
                                T.Str -> if (key.parts.all { it is String }) Lit(key.line, key.parts.joinToString(""))
                                else Interpolated(key.line, key.parts)
                                else -> error(key, "klíč objektu má být text nebo název")
                            }
                            expectOp(":")
                            entries += keyExpr to expression()
                            if (takeOp("}")) break
                            if (!takeOp(",")) error(peek(), "čekal jsem \",\" nebo \"}\"")
                        }
                        MapLit(token.line, entries)
                    }
                    else -> error(token, "nečekané \"${token.text}\"")
                }
                T.NewLine, T.End -> error(token, "výraz je neúplný")
            }
        }

        companion object {
            val COMPARE = setOf("==", "!=", "<", "<=", ">", ">=")
        }
    }

    // ---------- interpreter ----------

    private class ReturnSignal(val value: Any?) : RuntimeException(null, null, false, false)
    private class JumpSignal(val exit: Boolean) : RuntimeException(null, null, false, false)

    private class Interpreter(private val host: Host) {
        private val locals = HashMap<String, Any?>()
        private var steps = 0
        private var last: Any? = null

        fun run(program: Program): Any? = try {
            exec(program.body)
            last
        } catch (signal: ReturnSignal) {
            signal.value
        } catch (signal: JumpSignal) {
            fail("ukonci / pokracuj jde použít jen uvnitř opakování")
        }

        private fun tick() {
            if (++steps > MAX_STEPS) fail("skript udělal přes 5 milionů kroků (nekonečné opakování?)")
            if (steps % 1000 == 0 && !host.active()) throw CancellationException("Zastaveno")
        }

        private fun exec(statements: List<Stmt>) {
            for (statement in statements) {
                tick()
                try {
                    exec(statement)
                } catch (e: ReturnSignal) {
                    throw e
                } catch (e: JumpSignal) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BlockException) {
                    if (e.message?.startsWith("řádek ") == true) throw e
                    throw BlockException("řádek ${statement.line}: ${e.message}", e.requirement)
                } catch (e: StackOverflowError) {
                    throw BlockException("řádek ${statement.line}: výraz je příliš složitý")
                } catch (e: RuntimeException) {
                    throw BlockException("řádek ${statement.line}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }

        private fun exec(statement: Stmt) {
            when (statement) {
                is ExprStmt -> last = eval(statement.expr)
                is Assign -> {
                    val value = eval(statement.value)
                    val result = if (statement.op == "=") value
                    else binary(statement.op.substring(0, 1), eval(statement.target), value)
                    assign(statement.target, result)
                    last = result
                }
                is If -> {
                    for ((condition, body) in statement.branches) {
                        if (truthy(eval(condition))) {
                            exec(body)
                            return
                        }
                    }
                    statement.otherwise?.let { exec(it) }
                }
                is While -> while (truthy(eval(statement.condition))) {
                    tick()
                    try {
                        exec(statement.body)
                    } catch (signal: JumpSignal) {
                        if (signal.exit) break
                    }
                }
                is For -> {
                    for (item in items(eval(statement.list))) {
                        tick()
                        setVariable(statement.name, item)
                        try {
                            exec(statement.body)
                        } catch (signal: JumpSignal) {
                            if (signal.exit) break
                        }
                    }
                }
                is Return -> throw ReturnSignal(statement.value?.let { eval(it) })
                is Jump -> throw JumpSignal(statement.exit)
            }
        }

        private fun items(value: Any?): List<Any?> = when (val list = structured(value)) {
            null -> emptyList()
            is List<*> -> list.toList()
            is Map<*, *> -> list.keys.toList()
            is Double -> range(1.0, list)
            is String -> list.lines().filter { it.isNotBlank() }
            else -> listOf(list)
        }

        private fun getVariable(name: String): Any? {
            if (locals.containsKey(name)) return locals[name]
            val value = host.get(name)
            if (value === MISSING) fail("proměnná \"$name\" neexistuje")
            return value
        }

        private fun setVariable(name: String, value: Any?) {
            locals[name] = value
            host.set(name, value)
        }

        private fun assign(target: Expr, value: Any?) {
            when (target) {
                is Variable -> setVariable(target.name, value)
                is Index -> {
                    val index = eval(target.index)
                    val updated: Any = when (val container = structured(eval(target.base))) {
                        null -> if (number(index) != null) mutableListOf(value) else linkedMapOf(text(index) to value)
                        is List<*> -> container.toMutableList().also { list ->
                            var n = number(index)?.toInt() ?: fail("pozice v seznamu má být číslo")
                            if (n < 0) n += list.size
                            when (n) {
                                list.size -> list.add(value)
                                in list.indices -> list[n] = value
                                else -> fail("pozice $n je mimo seznam (má ${list.size} položek)")
                            }
                        }
                        is Map<*, *> -> LinkedHashMap<String, Any?>().also { map ->
                            container.forEach { (k, v) -> map[text(k)] = v }
                            map[text(index)] = value
                        }
                        else -> fail("do téhle hodnoty nejde zapisovat přes [ ]")
                    }
                    assign(target.base, updated)
                }
                is Member -> {
                    val updated = when (val container = structured(eval(target.base))) {
                        null -> linkedMapOf<String, Any?>(target.name to value)
                        is Map<*, *> -> LinkedHashMap<String, Any?>().also { map ->
                            container.forEach { (k, v) -> map[text(k)] = v }
                            map[target.name] = value
                        }
                        else -> fail("\"${target.name}\": hodnota není objekt")
                    }
                    assign(target.base, updated)
                }
                else -> fail("sem nejde přiřadit")
            }
        }

        private fun eval(expr: Expr): Any? = when (expr) {
            is Lit -> expr.value
            is Interpolated -> expr.parts.joinToString("") { if (it is Int) host.chip(it) else it as String }
            is Chip -> host.chip(expr.index)
            is Variable -> getVariable(expr.name)
            is Unary -> {
                val value = eval(expr.operand)
                if (expr.op == "!") !truthy(value)
                else -(number(value) ?: fail("\"${text(value).take(30)}\" není číslo"))
            }
            is Logic -> {
                val left = truthy(eval(expr.left))
                if (expr.and) left && truthy(eval(expr.right)) else left || truthy(eval(expr.right))
            }
            is Binary -> binary(expr.op, eval(expr.left), eval(expr.right))
            is Index -> {
                val index = eval(expr.index)
                when (val base = structured(eval(expr.base))) {
                    null -> null
                    is List<*> -> {
                        var n = number(index)?.toInt() ?: fail("pozice v seznamu má být číslo")
                        if (n < 0) n += base.size
                        base.getOrNull(n)
                    }
                    is Map<*, *> -> base[text(index)]
                    is String -> {
                        var n = number(index)?.toInt() ?: fail("pozice v textu má být číslo")
                        if (n < 0) n += base.length
                        base.getOrNull(n)?.toString()
                    }
                    else -> fail("z téhle hodnoty nejde číst přes [ ]")
                }
            }
            is Member -> when (val base = structured(eval(expr.base))) {
                null -> null
                is Map<*, *> -> base[expr.name]
                is List<*> -> when (expr.name) {
                    "length", "delka", "délka" -> base.size.toDouble()
                    else -> expr.name.toIntOrNull()?.let { base.getOrNull(it) }
                        ?: fail("seznam nemá \"${expr.name}\" (položky se čtou přes [0])")
                }
                else -> fail("\"${expr.name}\": hodnota není objekt")
            }
            is ListLit -> expr.items.mapTo(mutableListOf()) { eval(it) }
            is MapLit -> expr.entries.associateTo(LinkedHashMap()) { (key, value) -> text(eval(key)) to eval(value) }
            is Call -> {
                tick()
                when (expr.name) {
                    "var", "promenna", "proměnná" -> {
                        if (expr.args.size != 1) fail("${expr.name} potřebuje název proměnné")
                        getVariable(text(eval(expr.args[0])))
                    }
                    else -> call(expr.name, expr.args.map { eval(it) })
                }
            }
        }

        private fun binary(op: String, left: Any?, right: Any?): Any? {
            fun num(value: Any?) = number(value) ?: fail("\"${text(value).take(30)}\" není číslo (operace $op)")
            return when (op) {
                "+" -> {
                    val a = number(left)
                    val b = number(right)
                    when {
                        a != null && b != null -> a + b
                        left is List<*> -> left + (if (right is List<*>) right else listOf(right))
                        else -> text(left) + text(right)
                    }
                }
                "&" -> text(left) + text(right)
                "-" -> num(left) - num(right)
                "*" -> num(left) * num(right)
                "/" -> num(right).let { if (it == 0.0) fail("dělení nulou") else num(left) / it }
                "%" -> num(right).let { if (it == 0.0) fail("dělení nulou") else num(left) % it }
                "^" -> num(left).pow(num(right))
                "==" -> same(left, right)
                "!=" -> !same(left, right)
                "<" -> compare(left, right) < 0
                "<=" -> compare(left, right) <= 0
                ">" -> compare(left, right) > 0
                ">=" -> compare(left, right) >= 0
                else -> fail("neznámá operace $op")
            }
        }

        private fun range(from: Double, to: Double): List<Any?> {
            val first = from.toLong()
            val last = to.toLong()
            if (last - first >= MAX_RANGE) fail("rozsah je moc velký (nejvýš $MAX_RANGE)")
            return (first..last).map { it.toDouble() }
        }

        private fun call(name: String, args: List<Any?>): Any? {
            fun need(range: IntRange) {
                if (args.size !in range) {
                    val count = if (range.first == range.last) "${range.first}" else "${range.first} až ${range.last}"
                    fail("$name: počet hodnot v závorce má být $count, je ${args.size}")
                }
            }
            fun str(i: Int) = text(args.getOrNull(i))
            fun num(i: Int) = number(args[i]) ?: fail("$name: \"${str(i).take(30)}\" není číslo")
            fun list(i: Int): List<Any?> = when (val value = structured(args[i])) {
                is List<*> -> value
                is Map<*, *> -> value.values.toList()
                else -> items(value)
            }
            /** Numbers given one by one or as a single list. */
            fun numbers(): List<Double> {
                val values = if (args.size == 1 && structured(args[0]) is List<*>) list(0) else args
                if (values.isEmpty()) fail("$name: chybí čísla")
                return values.map { number(it) ?: fail("$name: \"${text(it).take(30)}\" není číslo") }
            }
            fun regex(pattern: String) = try {
                Regex(pattern)
            } catch (e: Exception) {
                fail("$name: neplatný regulární výraz")
            }

            return when (name) {
                // --- text ---
                "len", "delka", "délka" -> {
                    need(1..1)
                    when (val value = structured(args[0])) {
                        is List<*> -> value.size.toDouble()
                        is Map<*, *> -> value.size.toDouble()
                        else -> text(value).length.toDouble()
                    }
                }
                "upper", "velka", "velká" -> need(1..1).let { str(0).uppercase() }
                "lower", "mala", "malá" -> need(1..1).let { str(0).lowercase() }
                "trim", "orizni", "ořízni" -> need(1..1).let { str(0).trim() }
                "replace", "nahrad", "nahraď" -> need(3..3).let { str(0).replace(str(1), str(2)) }
                "contains", "obsahuje" -> {
                    need(2..2)
                    when (val value = structured(args[0])) {
                        is List<*> -> value.any { same(it, args[1]) }
                        is Map<*, *> -> value.containsKey(str(1))
                        else -> text(value).contains(str(1))
                    }
                }
                "starts", "zacina", "začíná" -> need(2..2).let { str(0).startsWith(str(1)) }
                "ends", "konci", "končí" -> need(2..2).let { str(0).endsWith(str(1)) }
                "split", "rozdel", "rozděl" -> {
                    need(1..2)
                    val separator = if (args.size > 1) str(1) else "\n"
                    if (separator.isEmpty()) str(0).map { it.toString() }.toMutableList<Any?>()
                    else str(0).split(separator).toMutableList<Any?>()
                }
                "join", "spoj" -> need(1..2).let { list(0).joinToString(if (args.size > 1) str(1) else ", ") { text(it) } }
                "substr", "cast", "část" -> {
                    need(2..3)
                    val source = str(0)
                    var start = num(1).toInt()
                    if (start < 0) start += source.length
                    start = start.coerceIn(0, source.length)
                    val end = if (args.size > 2) (start + num(2).toInt()).coerceIn(start, source.length) else source.length
                    source.substring(start, end)
                }
                "find", "najdi" -> need(2..2).let { str(0).indexOf(str(1)).toDouble() }
                "match", "shoda" -> {
                    need(2..2)
                    regex(str(1)).find(str(0))?.let { it.groupValues.getOrNull(1) ?: it.value }
                }
                "matches", "odpovida", "odpovídá" -> need(2..2).let { regex(str(1)).containsMatchIn(str(0)) }
                "str", "text" -> need(1..1).let { str(0) }

                // --- numbers ---
                "num", "cislo", "číslo" -> need(1..1).let { num(0) }
                "int", "cele", "celé" -> need(1..1).let { num(0).toLong().toDouble() }
                "round", "zaokrouhli" -> {
                    need(1..2)
                    val factor = 10.0.pow(if (args.size > 1) num(1).toInt() else 0)
                    (num(0) * factor).roundToLong() / factor
                }
                "floor", "dolu", "dolů" -> need(1..1).let { floor(num(0)) }
                "ceil", "nahoru" -> need(1..1).let { ceil(num(0)) }
                "abs" -> need(1..1).let { abs(num(0)) }
                "sqrt", "odmocnina" -> need(1..1).let { sqrt(num(0)) }
                "min" -> numbers().min()
                "max" -> numbers().max()
                "sum", "soucet", "součet" -> if (args.isEmpty()) 0.0 else numbers().sum()
                "avg", "prumer", "průměr" -> numbers().average()
                "random", "nahodne", "náhodné" -> {
                    need(2..2)
                    val from = num(0).toLong()
                    val to = num(1).toLong()
                    if (to < from) fail("$name: druhé číslo je menší než první")
                    Random.nextLong(from, to + 1).toDouble()
                }
                "calc", "vypocet", "výpočet" -> need(1..1).let { Calc.evaluate(str(0)) }

                // --- lists and objects ---
                "range", "rozsah" -> {
                    need(1..2)
                    if (args.size == 1) range(1.0, num(0)) else range(num(0), num(1))
                }
                "keys", "klice", "klíče" -> {
                    need(1..1)
                    (structured(args[0]) as? Map<*, *>)?.keys?.toMutableList<Any?>() ?: fail("$name: hodnota není objekt")
                }
                "values", "hodnoty" -> {
                    need(1..1)
                    (structured(args[0]) as? Map<*, *>)?.values?.toMutableList() ?: fail("$name: hodnota není objekt")
                }
                "push", "pridej", "přidej" -> {
                    if (args.isEmpty()) fail("$name: chybí seznam")
                    (list(0) + args.drop(1)).toMutableList()
                }
                "json" -> need(1..1).let { fromJson(JsonTools.parse(str(0))) }
                "tojson", "dojson" -> need(1..1).let { toJson(structured(args[0])).toString() }
                "type", "typ" -> {
                    need(1..1)
                    when (val value = structured(args[0])) {
                        null -> "nic"
                        is Boolean -> "ano/ne"
                        is Double -> "číslo"
                        is List<*> -> "seznam"
                        is Map<*, *> -> "objekt"
                        else -> if (number(value) != null) "číslo" else "text"
                    }
                }

                // --- other ---
                "iif", "kdyz", "když" -> need(3..3).let { if (truthy(args[0])) args[1] else args[2] }
                "now", "ted", "teď" -> need(0..0).let { System.currentTimeMillis().toDouble() }
                "date", "datum" -> {
                    need(0..1)
                    try {
                        SimpleDateFormat(if (args.isNotEmpty()) str(0) else "d. M. yyyy HH:mm", Locale.getDefault()).format(Date())
                    } catch (e: IllegalArgumentException) {
                        fail("$name: neplatný formát data")
                    }
                }
                else -> fail("neznámá funkce $name")
            }
        }
    }
}
