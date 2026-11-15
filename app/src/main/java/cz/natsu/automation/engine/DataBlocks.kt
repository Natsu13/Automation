package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Loops, JSON and lists. */
object DataBlocks {
    const val FOR_EACH = "for_each"
    const val REPEAT = "repeat"
    const val LOOP_CONTROL = "loop_control"

    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    private fun choiceNot(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value !in values
    }

    private class ListLoop(val items: List<String>, var index: Int = 0)

    val all: List<BlockDef> = listOf(
        // ---------- Loops ----------
        BlockDef(
            type = FOR_EACH,
            summary = "Pro každou položku z {list}",
            title = "Pro každou položku",
            description = "Zopakuje kroky pro každou položku seznamu (JSON pole nebo jedna položka na řádek). " +
                    "Aktuální položka je výstup tohoto kroku.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.Loop,
            kind = BlockKind.WhileStart,
            params = listOf(ParamDef.Text("list", "Seznam", defaultRef = VarRef.Input)),
            outputName = "Položka",
        ) { block ->
            val state = if (loopAgain.remove(block.id)) {
                (loopState[block.id] as? ListLoop)?.also { it.index++ }
            } else null
            val loop = state ?: ListLoop(JsonTools.toList(text(block, "list"))).also { loopState[block.id] = it }
            if (loop.index < loop.items.size) {
                BlockResult.Condition(true, loop.items[loop.index])
            } else {
                loopState.remove(block.id)
                BlockResult.Condition(false, "hotovo (${loop.items.size} položek)")
            }
        },

        BlockDef(
            type = REPEAT,
            summary = "Opakovat {count}×",
            title = "Opakovat několikrát",
            description = "Zopakuje kroky zadaný početkrát. Výstup je číslo aktuálního kola (od 1).",
            category = BlockCategory.Control,
            icon = Icons.Rounded.RepeatOne,
            kind = BlockKind.WhileStart,
            params = listOf(ParamDef.Text("count", "Kolikrát", default = "3")),
            outputName = "Kolo",
        ) { block ->
            val round = if (loopAgain.remove(block.id)) (loopState[block.id] as? Int ?: 0) + 1 else 1
            val count = parseNumber(text(block, "count"))?.toInt()
                ?: throw BlockException(tr("\"Kolikrát\" není číslo"))
            if (round <= count) {
                loopState[block.id] = round
                BlockResult.Condition(true, round.toString())
            } else {
                loopState.remove(block.id)
                BlockResult.Condition(false, "hotovo ($count×)")
            }
        },

        BlockDef(
            type = LOOP_CONTROL,
            summary = "{action}",
            title = "Ukončit / přeskočit kolo",
            description = "Uvnitř opakování: ukončí celé opakování, nebo přeskočí zbytek kroků a pokračuje dalším kolem.",
            category = BlockCategory.Control,
            icon = Icons.Rounded.SkipNext,
            params = listOf(
                ParamDef.Choice(
                    "action", "Co udělat", listOf(
                        ChoiceOption("break", "Ukončit opakování", "Ukončit opakování"),
                        ChoiceOption("continue", "Pokračovat dalším kolem", "Další kolo"),
                    )
                ),
            ),
        ) { block -> BlockResult.LoopJump(exit = choice(block, "action") != "continue") },

        // ---------- JSON ----------
        BlockDef(
            type = "json_get",
            summary = "Z JSON {what} {path}",
            title = "Získat z JSON",
            description = "Vytáhne hodnotu z JSON podle cesty, např. data.items[0].url nebo items[*].url (všechny). " +
                    "Objekty a pole vrací jako JSON, takže jdou dál zpracovat nebo projít opakováním.",
            category = BlockCategory.Data,
            icon = Icons.Rounded.DataObject,
            params = listOf(
                ParamDef.Text("source", "JSON", defaultRef = VarRef.Input),
                ParamDef.Text("path", "Cesta", placeholder = "např. data.items[*].url (prázdná = celý JSON)"),
                ParamDef.Choice(
                    "what", "Co vrátit", listOf(
                        ChoiceOption("value", "Hodnotu", "hodnotu"),
                        ChoiceOption("count", "Počet položek", "počet"),
                        ChoiceOption("keys", "Názvy klíčů (seznam)", "klíče"),
                        ChoiceOption("exists", "Jestli existuje (true / false)", "existuje"),
                        ChoiceOption("pretty", "Hezky formátovaný JSON", "formátovaně"),
                    )
                ),
                ParamDef.Text("fallback", "Když chybí, vrátit", placeholder = "prázdný text").apply {
                    hiddenWhen = choiceNot("what", "value", "pretty")
                },
            ),
            outputName = "Hodnota z JSON",
        ) { block ->
            val root = JsonTools.parse(text(block, "source"))
            val found = JsonTools.select(root, JsonTools.parsePath(text(block, "path")))
            val result = when (choice(block, "what")) {
                "exists" -> (found != null).toString()
                "count" -> when (found) {
                    is JsonArray -> found.size
                    is JsonObject -> found.size
                    null -> 0
                    else -> 1
                }.toString()
                "keys" -> JsonTools.fromList((found as? JsonObject)?.keys?.toList()
                    ?: throw BlockException(tr("na cestě není objekt")))
                "pretty" -> found?.let { JsonTools.pretty(it) } ?: text(block, "fallback")
                else -> if (found == null) text(block, "fallback") else JsonTools.asText(found)
            }
            BlockResult.Output(result)
        },

        BlockDef(
            type = "json_build",
            summary = "Vytvořit JSON {kind}",
            title = "Vytvořit JSON",
            description = "Sestaví JSON z řádků \"klíč: hodnota\" (tečka v klíči vytvoří vnořený objekt: user.name), " +
                    "nebo pole z řádků. Hodí se jako tělo POST požadavku.",
            category = BlockCategory.Data,
            icon = Icons.Rounded.EditNote,
            params = listOf(
                ParamDef.Choice(
                    "kind", "Vytvořit", listOf(
                        ChoiceOption("object", "Objekt z řádků klíč: hodnota", "objekt"),
                        ChoiceOption("array", "Pole – každý řádek jedna položka", "pole"),
                    )
                ),
                ParamDef.Text("items", "Položky", placeholder = "title: Ahoj\nuser.id: 42"),
                ParamDef.Toggle("typed", "Čísla a true / false jako hodnoty (ne text)", default = true),
            ),
            outputName = "JSON",
        ) { block ->
            val typed = bool(block, "typed")
            val source = text(block, "items")
            val result = if (choice(block, "kind") == "array") {
                JsonArray(source.lines().filter { it.isNotBlank() }.map { JsonTools.value(it.trim(), typed) })
            } else {
                var root: kotlinx.serialization.json.JsonElement = JsonObject(emptyMap())
                for ((key, value) in JsonTools.pairs(source, charArrayOf(':'))) {
                    root = JsonTools.set(root, JsonTools.parsePath(key), JsonTools.value(value, typed))
                        ?: JsonObject(emptyMap())
                }
                root
            }
            BlockResult.Output(result.toString())
        },

        BlockDef(
            type = "json_set",
            summary = "V JSON {mode} {path}",
            title = "Upravit JSON",
            description = "Nastaví, přidá do pole nebo smaže hodnotu na cestě. Chybějící části cesty vytvoří.",
            category = BlockCategory.Data,
            icon = Icons.Rounded.Tune,
            params = listOf(
                ParamDef.Text("source", "JSON", placeholder = "prázdné = nový objekt"),
                ParamDef.Choice(
                    "mode", "Co udělat", listOf(
                        ChoiceOption("set", "Nastavit hodnotu", "nastavit"),
                        ChoiceOption("append", "Přidat na konec pole", "přidat do"),
                        ChoiceOption("remove", "Smazat", "smazat"),
                    )
                ),
                ParamDef.Text("path", "Cesta", placeholder = "např. user.name nebo tags"),
                ParamDef.Text("value", "Hodnota").apply { hiddenWhen = choiceIs("mode", "remove") },
                ParamDef.Toggle("typed", "Čísla a true / false jako hodnoty (ne text)", default = true).apply {
                    hiddenWhen = choiceIs("mode", "remove")
                },
            ),
            outputName = "Upravený JSON",
        ) { block ->
            val sourceText = text(block, "source")
            val root = if (sourceText.isBlank()) JsonObject(emptyMap()) else JsonTools.parse(sourceText)
            val steps = JsonTools.parsePath(text(block, "path"))
            val value = JsonTools.value(text(block, "value"), bool(block, "typed"))
            val result = when (choice(block, "mode")) {
                "remove" -> if (steps.isEmpty()) null else JsonTools.set(root, steps, null)
                "append" -> {
                    val target = JsonTools.select(root, steps)
                    if (target != null && target !is JsonArray) throw BlockException(tr("na cestě není pole"))
                    val list = (target as? JsonArray).orEmpty() + value
                    JsonTools.set(root, steps, JsonArray(list))
                }
                else -> JsonTools.set(root, steps, value)
            }
            BlockResult.Output(result?.toString() ?: "")
        },

        BlockDef(
            type = "type_of",
            summary = "Zjistit typ {value}",
            title = "Zjistit typ",
            description = "Řekne, jestli je hodnota číslo, text, objekt, seznam, ano/ne nebo prázdná.",
            category = BlockCategory.Data,
            icon = Icons.Rounded.Category,
            params = listOf(
                ParamDef.Text("value", "Hodnota", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "names", "Pojmenování", listOf(
                        ChoiceOption("cs", "Česky (číslo, text, objekt…)", ""),
                        ChoiceOption("en", "Anglicky (number, string, object…)", ""),
                    )
                ).apply { hiddenInSummaryWhen = { true } },
            ),
            outputName = "Typ",
        ) { block ->
            val type = typeOf(text(block, "value"))
            BlockResult.Output(if (choice(block, "names") == "en") type.en else type.cs)
        },

        // ---------- Lists ----------
        BlockDef(
            type = "list",
            summary = "Seznam: {op} {source}",
            title = "Práce se seznamem",
            description = "Počet, položka podle pořadí, spojení do textu, rozdělení textu na seznam, přidání, " +
                    "řazení… Seznam je JSON pole nebo jedna položka na řádek; výsledný seznam je JSON pole.",
            category = BlockCategory.Data,
            icon = Icons.AutoMirrored.Rounded.FormatListBulleted,
            params = listOf(
                ParamDef.Text("source", "Seznam / text", defaultRef = VarRef.Input),
                ParamDef.Choice(
                    "op", "Operace", listOf(
                        ChoiceOption("count", "Počet položek", "počet"),
                        ChoiceOption("sum", "Součet čísel", "součet"),
                        ChoiceOption("avg", "Průměr čísel", "průměr"),
                        ChoiceOption("min", "Nejmenší číslo", "minimum"),
                        ChoiceOption("max", "Největší číslo", "maximum"),
                        ChoiceOption("item", "Položka na pozici", "položka"),
                        ChoiceOption("first", "První položka", "první"),
                        ChoiceOption("last", "Poslední položka", "poslední"),
                        ChoiceOption("join", "Spojit do textu", "spojit"),
                        ChoiceOption("split", "Rozdělit text na seznam", "rozdělit"),
                        ChoiceOption("add", "Přidat položku na konec", "přidat"),
                        ChoiceOption("remove", "Odebrat položku na pozici", "odebrat"),
                        ChoiceOption("contains", "Obsahuje položku (true / false)", "obsahuje"),
                        ChoiceOption("unique", "Bez duplicit", "bez duplicit"),
                        ChoiceOption("sort", "Seřadit", "seřadit"),
                        ChoiceOption("reverse", "Obrátit pořadí", "obrátit"),
                        ChoiceOption("shuffle", "Zamíchat", "zamíchat"),
                        ChoiceOption("random", "Náhodná položka", "náhodná"),
                        ChoiceOption("lines", "Převést na řádky", "na řádky"),
                    )
                ),
                ParamDef.Text("index", "Pozice (od 1, -1 = poslední)", default = "1").apply {
                    hiddenWhen = choiceNot("op", "item", "remove")
                },
                ParamDef.Text("separator", "Oddělovač", default = ", ", placeholder = "prázdný = nový řádek").apply {
                    hiddenWhen = choiceNot("op", "join", "split")
                },
                ParamDef.Text("value", "Položka").apply { hiddenWhen = choiceNot("op", "add", "contains") },
            ),
            outputName = "Výsledek seznamu",
        ) { block ->
            val source = text(block, "source")
            val separator = text(block, "separator").ifEmpty { "\n" }
            if (choice(block, "op") == "split") {
                return@BlockDef BlockResult.Output(
                    JsonTools.fromList(source.split(separator).map { it.trim() }.filter { it.isNotEmpty() })
                )
            }
            val items = JsonTools.toList(source)
            val indexText = text(block, "index")
            fun position(): Int {
                val n = parseNumber(indexText)?.toInt() ?: throw BlockException(tr("pozice není číslo"))
                val index = if (n < 0) items.size + n else n - 1
                if (index !in items.indices) throw BlockException(tr("pozice %s je mimo seznam (má %s položek)", n, items.size))
                return index
            }
            fun numbers(): List<Double> = items.map {
                parseNumber(it) ?: throw BlockException(tr("\"%s\" není číslo", it.take(30)))
            }.ifEmpty { throw BlockException(tr("seznam je prázdný")) }
            val result = when (choice(block, "op")) {
                "count" -> items.size.toString()
                "sum" -> formatNumber(numbers().sum())
                "avg" -> formatNumber(numbers().average())
                "min" -> formatNumber(numbers().min())
                "max" -> formatNumber(numbers().max())
                "item" -> items[position()]
                "first" -> items.firstOrNull() ?: ""
                "last" -> items.lastOrNull() ?: ""
                "join" -> items.joinToString(separator)
                "add" -> JsonTools.fromList(items + text(block, "value"))
                "remove" -> JsonTools.fromList(items.toMutableList().also { it.removeAt(position()) })
                "contains" -> (text(block, "value") in items).toString()
                "unique" -> JsonTools.fromList(items.distinct())
                "sort" -> JsonTools.fromList(sortNatural(items))
                "reverse" -> JsonTools.fromList(items.reversed())
                "shuffle" -> JsonTools.fromList(items.shuffled())
                "random" -> items.randomOrNull() ?: ""
                else -> items.joinToString("\n")
            }
            BlockResult.Output(result)
        },
    )

    enum class ValueType(val cs: String, val en: String) {
        Empty("prázdné", "empty"),
        Number("číslo", "number"),
        Bool("ano/ne", "boolean"),
        Object("objekt", "object"),
        List("seznam", "array"),
        Text("text", "string"),
    }

    fun typeOf(value: String): ValueType {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == "null") return ValueType.Empty
        if (trimmed == "true" || trimmed == "false") return ValueType.Bool
        if (parseNumber(trimmed) != null && Regex("[-+]?[\\d\\s]*[.,]?\\d+([eE][-+]?\\d+)?").matches(trimmed)) return ValueType.Number
        return when (JsonTools.parseOrNull(trimmed)) {
            is kotlinx.serialization.json.JsonObject -> ValueType.Object
            is JsonArray -> ValueType.List
            else -> ValueType.Text
        }
    }

    /** Numbers by value, text alphabetically (Czech). */
    private fun sortNatural(items: List<String>): List<String> {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("cs"))
        return items.sortedWith { a, b ->
            val x = parseNumber(a)
            val y = parseNumber(b)
            if (x != null && y != null) x.compareTo(y) else collator.compare(a, b)
        }
    }
}
