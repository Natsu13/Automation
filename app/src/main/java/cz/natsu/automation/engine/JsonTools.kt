package cz.natsu.automation.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Values in a run are plain text. JSON objects and arrays travel as JSON text,
 * lists are either a JSON array or one item per line.
 */
object JsonTools {
    private val pretty = Json { prettyPrint = true }

    fun parse(text: String): JsonElement = try {
        Json.parseToJsonElement(text.trim())
    } catch (e: Exception) {
        throw BlockException("text není platný JSON")
    }

    fun parseOrNull(text: String): JsonElement? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            null
        }
    }

    /** Text form of a value: strings without quotes, null as "", objects / arrays as JSON. */
    fun asText(element: JsonElement?): String = when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    fun pretty(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    /**
     * Turns text into a JSON value. Objects / arrays written as JSON stay JSON;
     * with [typed] also numbers, true / false and null keep their type.
     */
    fun value(text: String, typed: Boolean = true): JsonElement {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            parseOrNull(trimmed)?.let { return it }
        }
        if (typed) {
            when (trimmed) {
                "true" -> return JsonPrimitive(true)
                "false" -> return JsonPrimitive(false)
                "null" -> return JsonNull
            }
            if (Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?").matches(trimmed)) {
                trimmed.toLongOrNull()?.let { return JsonPrimitive(it) }
                trimmed.toDoubleOrNull()?.let { return JsonPrimitive(it) }
            }
        }
        return JsonPrimitive(text)
    }

    // ---------- lists ----------

    /** A JSON array gives its items, anything else is split into non-empty lines. */
    fun toList(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            (parseOrNull(trimmed) as? JsonArray)?.let { array -> return array.map { asText(it) } }
        }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** A list as a JSON array; items that are JSON objects / arrays are kept as such. */
    fun fromList(items: List<String>): String = JsonArray(items.map { value(it, typed = false) }).toString()

    // ---------- paths ----------

    sealed interface Step {
        data class Key(val name: String) : Step
        data class Index(val index: Int) : Step
        data object All : Step
    }

    /**
     * `data.items[0].url`, `items[*].name`, `["key with.dot"]`, `items[-1]`
     * (from the end). A leading `$` is allowed. Empty path = the whole value.
     */
    fun parsePath(path: String): List<Step> {
        val steps = mutableListOf<Step>()
        var text = path.trim()
        if (text.startsWith("$")) text = text.substring(1)
        var i = 0
        val key = StringBuilder()
        fun flushKey() {
            if (key.isNotEmpty()) steps += Step.Key(key.toString())
            key.clear()
        }
        while (i < text.length) {
            when (val c = text[i]) {
                '.' -> flushKey()
                '[' -> {
                    flushKey()
                    val end = findClosing(text, i)
                    val inner = text.substring(i + 1, end).trim()
                    steps += when {
                        inner == "*" -> Step.All
                        inner.length >= 2 && (inner[0] == '"' || inner[0] == '\'') && inner.last() == inner[0] ->
                            Step.Key(inner.substring(1, inner.length - 1))
                        else -> Step.Index(inner.toIntOrNull() ?: throw BlockException("neplatný index [$inner] v cestě"))
                    }
                    i = end
                }
                else -> key.append(c)
            }
            i++
        }
        flushKey()
        return steps
    }

    private fun findClosing(text: String, open: Int): Int {
        var quote: Char? = null
        for (j in open + 1 until text.length) {
            val c = text[j]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == ']') {
                return j
            }
        }
        throw BlockException("v cestě chybí ]")
    }

    /** Value at [steps], or null when it does not exist. `[*]` collects the results into an array. */
    fun select(element: JsonElement?, steps: List<Step>, from: Int = 0): JsonElement? {
        if (element == null) return null
        if (from == steps.size) return element
        return when (val step = steps[from]) {
            is Step.Key -> (element as? JsonObject)?.get(step.name)?.let { select(it, steps, from + 1) }
                // A number on an array works as an index too: items.0.url
                ?: (element as? JsonArray)?.let { array ->
                    step.name.toIntOrNull()?.let { index -> array.getOrNull(index.fromEnd(array.size)) }
                }?.let { select(it, steps, from + 1) }
            is Step.Index -> (element as? JsonArray)?.getOrNull(step.index.fromEnd(element.size))
                ?.let { select(it, steps, from + 1) }
            Step.All -> {
                val children = when (element) {
                    is JsonArray -> element
                    is JsonObject -> element.values
                    else -> return null
                }
                JsonArray(children.mapNotNull { select(it, steps, from + 1) })
            }
        }
    }

    private fun Int.fromEnd(size: Int) = if (this < 0) size + this else this

    /** Copy of [element] with the value at [steps] replaced by [value] (null = removed). Missing parts are created. */
    fun set(element: JsonElement?, steps: List<Step>, value: JsonElement?, from: Int = 0): JsonElement? {
        if (from == steps.size) return value
        return when (val step = steps[from]) {
            is Step.Key -> {
                if (element is JsonArray && step.name.toIntOrNull() != null) {
                    return set(element, steps.toMutableList().also { it[from] = Step.Index(step.name.toInt()) }, value, from)
                }
                val map = (element as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                val child = set(map[step.name], steps, value, from + 1)
                if (child == null) map.remove(step.name) else map[step.name] = child
                JsonObject(map)
            }
            is Step.Index -> {
                val list = (element as? JsonArray)?.toMutableList() ?: mutableListOf()
                val index = step.index.fromEnd(list.size)
                when {
                    index == list.size || (step.index < 0 && list.isEmpty()) -> {
                        set(null, steps, value, from + 1)?.let { list.add(it) }
                    }
                    index in list.indices -> {
                        val child = set(list[index], steps, value, from + 1)
                        if (child == null) list.removeAt(index) else list[index] = child
                    }
                    else -> throw BlockException("index ${step.index} je mimo seznam (má ${list.size} položek)")
                }
                JsonArray(list)
            }
            Step.All -> when (element) {
                is JsonArray -> JsonArray(element.mapNotNull { set(it, steps, value, from + 1) })
                is JsonObject -> JsonObject(element.mapNotNull { (k, v) -> set(v, steps, value, from + 1)?.let { k to it } }.toMap())
                else -> element
            }
        }
    }

    /** "key: value" / "key=value" lines, e.g. for headers and form fields. */
    fun pairs(text: String, separators: CharArray = charArrayOf(':', '=')): List<Pair<String, String>> =
        text.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val at = line.indexOfAny(separators)
            if (at <= 0) throw BlockException("řádek \"${line.trim().take(40)}\" nemá tvar klíč: hodnota")
            line.substring(0, at).trim() to line.substring(at + 1).trim()
        }
}
