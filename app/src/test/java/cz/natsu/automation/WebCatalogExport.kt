package cz.natsu.automation

import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.BlockJson
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.EventValue
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.engine.BlockCategory
import cz.natsu.automation.engine.BlockHelp
import cz.natsu.automation.engine.BlockKind
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.ControlBlocks
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.i18n.English
import cz.natsu.automation.i18n.I18n
import cz.natsu.automation.i18n.Language
import cz.natsu.automation.ui.common.IconCatalog
import cz.natsu.automation.ui.list.Examples
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import java.io.File

/**
 * Writes the block catalog for the web editor (`web/catalog.js` in the repository root):
 * every block with its parameters, events, icons and the examples, in Czech and English.
 *
 * Run after changing blocks: `./gradlew :app:testDebugUnitTest --tests "*WebCatalogExport*"`
 */
class WebCatalogExport {
    /** A text in both languages; English falls back to the Czech original. */
    private fun t(czech: String): JsonElement = buildJsonObject {
        put("cs", czech)
        put("en", English.texts[czech] ?: czech)
    }

    private fun iconName(vector: androidx.compose.ui.graphics.vector.ImageVector): String =
        vector.name.substringAfterLast('.')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Za-z])([0-9])"), "$1_$2")
            .lowercase()

    private fun valueJson(value: ParamValue): JsonElement = BlockJson.json.encodeToJsonElement(ParamValue.serializer(), value)

    /**
     * Which values of the block's choices / toggles hide [param]. The rule itself is a
     * Kotlin lambda, so it is tried out: the parameters whose change makes a difference
     * are its "controllers", and every combination of their values is recorded.
     */
    internal fun hiddenRule(
        params: List<ParamDef>,
        param: ParamDef,
        /** The rule tried out; the parameter's visibility, or e.g. its "left out of the summary" rule. */
        rule: ((Map<String, ParamValue>) -> Boolean)? = param.hiddenWhen,
    ): JsonElement {
        if (rule == null) return JsonNull
        val defaults = params.associate { it.key to it.defaultValue() }
        fun hidden(values: Map<String, ParamValue>) = try { rule(values) } catch (e: Exception) { false }
        /** Values worth trying: every choice, both toggle states, sample numbers, an empty and a filled-in text. */
        fun domain(p: ParamDef): List<ParamValue> = when (p) {
            is ParamDef.Choice -> p.options.map { ParamValue.Choice(it.value) }
            is ParamDef.Toggle -> listOf(ParamValue.Bool(false), ParamValue.Bool(true))
            is ParamDef.Number -> numberSamples(p).map { ParamValue.Number(it) }
            is ParamDef.Text -> listOf(richText(""), richText("x"))
            is ParamDef.Automation -> listOf(ParamValue.Choice(""), ParamValue.Choice("1"))
        }
        // Controllers: parameters whose value changes the result for some values of the controllers found so far.
        val controllers = mutableListOf<ParamDef>()
        fun combos(of: List<ParamDef>): List<Map<String, ParamValue>> {
            var out: List<Map<String, ParamValue>> = listOf(emptyMap())
            for (c in of) out = out.flatMap { combo -> domain(c).map { combo + (c.key to it) } }
            return out
        }
        var grew = true
        while (grew) {
            grew = false
            for (p in params) {
                if (p in controllers) continue  // a rule may depend on the parameter itself ("custom" is not summarised)
                val cs = combos(controllers)
                if (cs.size > 2000) break
                val matters = cs.any { combo ->
                    val values = domain(p).map { hidden(defaults + combo + (p.key to it)) }
                    values.distinct().size > 1
                }
                if (matters) { controllers += p; grew = true }
            }
            // "a or b": from the defaults neither alone changes anything, so pairs are tried too.
            if (!grew && controllers.isEmpty()) {
                val candidates = params.filter { it !is ParamDef.Text && it !is ParamDef.Automation }
                pairs@ for (p in candidates) for (q in candidates) {
                    if (p === q) continue
                    val seen = domain(p).flatMap { a -> domain(q).map { b -> hidden(defaults + (p.key to a) + (q.key to b)) } }
                    if (seen.distinct().size > 1) { controllers += p; controllers += q; grew = true; break@pairs }
                }
            }
        }
        if (controllers.isEmpty()) return JsonPrimitive(hidden(defaults))
        val all = combos(controllers)
        if (all.size > 2000) return JsonPrimitive(hidden(defaults))
        fun label(c: ParamDef, v: ParamValue?): JsonElement = when (v) {
            is ParamValue.Choice -> JsonPrimitive(v.value)
            is ParamValue.Bool -> JsonPrimitive(v.value.toString())
            is ParamValue.Number -> JsonPrimitive(numberSamples(c as ParamDef.Number).indexOf(v.value).toString())
            is ParamValue.Rich -> JsonPrimitive(if (v.segments.isEmpty()) "" else "*")
            null -> JsonNull
        }
        return buildJsonObject {
            putJsonArray("keys") { controllers.forEach { add(JsonPrimitive(it.key)) } }
            // How each controller's current value is turned into one of the tried values.
            putJsonArray("kinds") {
                controllers.forEach { c ->
                    add(when (c) {
                        is ParamDef.Number -> buildJsonObject { putJsonArray("samples") { numberSamples(c).forEach { add(JsonPrimitive(it)) } } }
                        is ParamDef.Text -> JsonPrimitive("text")
                        else -> JsonPrimitive("value")
                    })
                }
            }
            putJsonArray("hiddenFor") {
                for (combo in all) if (hidden(defaults + combo)) add(buildJsonArray { controllers.forEach { add(label(it, combo[it.key])) } })
            }
        }
    }

    /** Numbers a rule on a number parameter is tried with (its steps, or up to ~60 even samples). */
    private fun numberSamples(p: ParamDef.Number): List<Float> {
        val count = ((p.max - p.min) / p.step).toInt()
        val step = if (count <= 60) p.step else (p.max - p.min) / 60f
        return generateSequence(p.min) { it + step }.takeWhile { it <= p.max + 1e-6f }.toList().let { (it + p.default).distinct().sorted() }
    }

    private fun paramJson(all: List<ParamDef>, p: ParamDef): JsonObject = buildJsonObject {
        put("key", p.key)
        put("label", t(p.label))
        put("default", valueJson(p.defaultValue()))
        put("hidden", hiddenRule(all, p))
        // Left out of the one-line summary: a rule like "hidden", tried out the same way.
        p.hiddenInSummaryWhen?.let { put("noSummary", hiddenRule(all, p, it)) }
        when (p) {
            is ParamDef.Text -> {
                put("kind", "text")
                if (p.placeholder.isNotEmpty()) put("placeholder", t(p.placeholder))
                if (p.plain) put("plain", true)
                if (p.code) put("code", true)
                if (p.appPicker) put("appPicker", true)
                if (p.imagePicker) put("imagePicker", true)
                if (p.colorPicker) put("colorPicker", true)
                if (p.iconPicker) put("iconPicker", true)
                if (p.suggestions.isNotEmpty()) putJsonArray("suggestions") { p.suggestions.forEach { add(JsonPrimitive(it)) } }
            }
            is ParamDef.Choice -> {
                put("kind", "choice")
                putJsonArray("options") {
                    p.options.forEach { o ->
                        add(buildJsonObject {
                            put("value", o.value)
                            put("label", t(o.label))
                            put("short", t(o.short))
                        })
                    }
                }
            }
            is ParamDef.Number -> {
                put("kind", "number")
                put("min", p.min); put("max", p.max); put("step", p.step)
                if (p.unit.isNotEmpty()) put("unit", t(p.unit))
            }
            is ParamDef.Toggle -> {
                put("kind", "toggle")
                p.summaryWhenOn?.let { put("summaryWhenOn", t(it)) }
            }
            is ParamDef.Automation -> put("kind", "automation")
        }
    }

    private fun helpOf(type: String): JsonElement {
        I18n.force(Language.Czech)
        val cs = BlockHelp.of(type) ?: return JsonNull
        I18n.force(Language.English)
        val en = BlockHelp.of(type) ?: cs
        I18n.force(Language.Czech)
        return buildJsonObject { put("cs", cs.trimIndent()); put("en", en.trimIndent()) }
    }

    /** Blocks inserted together with a group start (its branches and end), as in the editor. */
    private fun companions(type: String, kind: BlockKind): List<String> {
        if (!kind.isGroupStart) return emptyList()
        val def = Blocks.get(type)!!
        return buildList {
            if (kind == BlockKind.TryStart) add(Blocks.CATCH)
            when (type) {
                ControlBlocks.SWITCH -> { add(ControlBlocks.CASE); add(Blocks.ELSE) }
                ControlBlocks.PARALLEL -> add(ControlBlocks.BRANCH)
            }
            add(ControlBlocks.endTypeFor(def))
        }
    }

    @Test
    fun export() {
        I18n.force(Language.Czech)
        val blocks = buildJsonArray {
            for (def in Blocks.all) add(buildJsonObject {
                put("type", def.type)
                put("title", t(def.title))
                put("description", t(def.description))
                put("category", def.category.name)
                put("icon", iconName(def.icon))
                put("kind", def.kind.name)
                put("summary", t(def.summary))
                def.outputName?.let { put("output", t(it)) }
                def.requirement?.let { put("requirement", t(it.title)) }
                if (!def.showInLibrary) put("hiddenInLibrary", true)
                if (def.needsWindow) put("needsWindow", true)
                putJsonArray("params") { def.params.forEach { add(paramJson(def.params, it)) } }
                putJsonArray("companions") { companions(def.type, def.kind).forEach { add(JsonPrimitive(it)) } }
                if (def.kind.isGroupStart) {
                    put("elseType", if (def.type == ControlBlocks.SWITCH) ControlBlocks.CASE else Blocks.elseTypeFor(def.kind))
                }
                put("help", helpOf(def.type))
            })
        }
        val categories = buildJsonArray {
            BlockCategory.entries.forEach { add(buildJsonObject { put("key", it.name); put("title", t(it.title)) }) }
        }
        val events = buildJsonArray {
            for (e in EventType.entries) add(buildJsonObject {
                put("key", e.key)
                put("title", t(e.title))
                put("description", t(e.description))
                put("group", e.group.name)
                put("groupTitle", t(e.group.title))
                e.input?.let { put("input", t(it)) }
                e.permission?.let { put("permission", it.name) }
                putJsonObject("defaults") { e.defaults.forEach { (k, v) -> put(k, v) } }
            })
        }
        val eventValues = buildJsonArray {
            for (v in EventValue.entries) add(buildJsonObject {
                put("key", v.key); put("label", t(v.label)); put("description", t(v.description))
            })
        }
        val icons = buildJsonArray {
            for (entry in IconCatalog.all) add(buildJsonObject {
                put("key", entry.key); put("icon", iconName(entry.icon)); put("words", entry.words)
            })
        }
        val blockList = ListSerializer(Block.serializer())
        val examples = buildJsonArray {
            for (example in Examples.all) {
                val built = example.build()
                val entity = built.triggers(AutomationEntity(name = example.name, icon = example.icon))
                add(buildJsonObject {
                    put("name", t(example.name))
                    put("description", t(example.description))
                    put("icon", example.icon)
                    put("triggerProcessText", entity.triggerProcessText)
                    put("triggerShare", entity.triggerShare)
                    put("triggerTile", entity.triggerTile)
                    put("triggerClipboard", entity.triggerClipboard)
                    put("conditionsAny", entity.conditionsAny)
                    put("conditions", BlockJson.json.parseToJsonElement(entity.conditionsJson))
                    put("events", BlockJson.json.parseToJsonElement(entity.eventsJson))
                    put("blocks", BlockJson.json.encodeToJsonElement(blockList, built.blocks))
                })
            }
        }
        // Words of the editor itself that the app already translates.
        val ui = buildJsonObject {
            for (word in listOf("Vstup", "Schránka", "Datum a čas", "Typ spuštění")) put(word, t(word))
        }
        val root = buildJsonObject {
            put("generated", java.time.LocalDate.now().toString())
            put("format", cz.natsu.automation.data.AutomationTransfer.FORMAT)
            put("categories", categories)
            put("blocks", blocks)
            put("events", events)
            put("eventValues", eventValues)
            put("icons", icons)
            put("examples", examples)
            put("ui", ui)
            putJsonArray("builtinRefs") {
                listOf(VarRef.Input to "input", VarRef.Clipboard to "clipboard", VarRef.CurrentDate to "date", VarRef.Trigger to "trigger")
                    .forEach { add(JsonPrimitive(it.second)) }
            }
        }
        val out = File("../web/catalog.js")
        out.parentFile.mkdirs()
        out.writeText(
            "// Generated by app/src/test/.../WebCatalogExport.kt – do not edit by hand.\n" +
                "window.CATALOG = " + BlockJson.json.encodeToString(JsonElement.serializer(), root) + ";\n"
        )
        println("Wrote ${out.canonicalPath}: ${Blocks.all.size} blocks, ${EventType.entries.size} events")
    }
}
