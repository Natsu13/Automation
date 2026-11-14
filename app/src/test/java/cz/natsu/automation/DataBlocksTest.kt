package cz.natsu.automation

import android.content.Context
import android.content.ContextWrapper
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.JsonTools
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class DataBlocksTest {
    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun block(type: String, group: String? = null, vararg params: Pair<String, ParamValue>): Block {
        val def = Blocks.get(type) ?: error("unknown block $type")
        return def.newBlock(group).let { it.copy(params = it.params + params) }
    }

    private fun rich(vararg parts: Any): ParamValue.Rich = ParamValue.Rich(parts.map {
        when (it) {
            is String -> Segment.Text(it)
            is VarRef -> Segment.Var(it)
            else -> error("bad part")
        }
    })

    private fun run(blocks: List<Block>, input: String = "") = runBlocking {
        AutomationRunner.run(FakeContext(), blocks, input)
    }

    /** loop start + steps + matching end */
    private fun loop(start: Block, vararg steps: Block): List<Block> =
        listOf(start) + steps + Blocks.get("end_while")!!.newBlock(start.groupId)

    private fun group() = UUID.randomUUID().toString()

    // ---------- JsonTools ----------

    private val sample = """{"data":{"items":[{"url":"https://a/1.jpg","n":1},{"url":"https://a/2.jpg","n":2}]},"name":"x"}"""

    @Test
    fun paths() {
        val root = JsonTools.parse(sample)
        assertEquals("https://a/1.jpg", JsonTools.asText(JsonTools.select(root, JsonTools.parsePath("data.items[0].url"))))
        assertEquals("https://a/2.jpg", JsonTools.asText(JsonTools.select(root, JsonTools.parsePath("data.items[-1].url"))))
        assertEquals("https://a/2.jpg", JsonTools.asText(JsonTools.select(root, JsonTools.parsePath("\$.data.items.1.url"))))
        assertEquals("""["https://a/1.jpg","https://a/2.jpg"]""",
            JsonTools.asText(JsonTools.select(root, JsonTools.parsePath("data.items[*].url"))))
        assertNull(JsonTools.select(root, JsonTools.parsePath("data.missing.x")))
        assertEquals("x", JsonTools.asText(JsonTools.select(root, JsonTools.parsePath("[\"name\"]"))))
    }

    @Test
    fun setAndLists() {
        val built = JsonTools.set(null, JsonTools.parsePath("user.tags[0]"), JsonTools.value("a"))
        assertEquals("""{"user":{"tags":["a"]}}""", built.toString())
        assertEquals("""{"n":42,"ok":true,"s":"text"}""",
            listOf("n" to "42", "ok" to "true", "s" to "text").fold(null as kotlinx.serialization.json.JsonElement?) { acc, (k, v) ->
                JsonTools.set(acc, JsonTools.parsePath(k), JsonTools.value(v))
            }.toString())
        assertEquals(listOf("a", "b"), JsonTools.toList("a\n\n b "))
        assertEquals(listOf("a", "{\"x\":1}"), JsonTools.toList("""["a",{"x":1}]"""))
        assertEquals("""["a",{"x":1}]""", JsonTools.fromList(listOf("a", "{\"x\":1}")))
    }

    // ---------- blocks and loops ----------

    @Test
    fun forEachOverJsonUrls() {
        val urls = block("json_get", null, "source" to rich(VarRef.Input), "path" to richText("data.items[*].url"))
        val each = block("for_each", group(), "list" to rich(VarRef.BlockOutput(urls.id)))
        val append = block(
            "set_variable", null,
            "name" to richText("acc"),
            "value" to rich(VarRef.Named("acc"), VarRef.BlockOutput(each.id), ";"),
        )
        val result = run(
            listOf(urls) + loop(each, append) + block("return", null, "text" to rich(VarRef.Named("acc"))),
            input = sample,
        )
        assertEquals(null, result.error)
        assertEquals("https://a/1.jpg;https://a/2.jpg;", result.output)
    }

    @Test
    fun repeatWithBreakAndContinue() {
        val repeat = block("repeat", group(), "count" to richText("6"))
        val skipGroup = group()
        val stopGroup = group()
        val steps = listOf(
            // skip round 2
            block("if", skipGroup, "left" to rich(VarRef.BlockOutput(repeat.id)), "op" to ParamValue.Choice("equals"), "right" to richText("2")),
            block("loop_control", null, "action" to ParamValue.Choice("continue")),
            Blocks.get("end_if")!!.newBlock(skipGroup),
            // stop after round 4
            block("if", stopGroup, "left" to rich(VarRef.BlockOutput(repeat.id)), "op" to ParamValue.Choice("equals"), "right" to richText("5")),
            block("loop_control", null, "action" to ParamValue.Choice("break")),
            Blocks.get("end_if")!!.newBlock(stopGroup),
            block("set_variable", null, "name" to richText("acc"), "value" to rich(VarRef.Named("acc"), VarRef.BlockOutput(repeat.id))),
        )
        val result = run(loop(repeat, *steps.toTypedArray()) + block("return", null, "text" to rich(VarRef.Named("acc"))))
        assertEquals(null, result.error)
        assertEquals("134", result.output)
    }

    @Test
    fun nestedLoops() {
        val outer = block("for_each", group(), "list" to richText("[\"a\",\"b\"]"))
        val inner = block("repeat", group(), "count" to richText("2"))
        val append = block(
            "set_variable", null, "name" to richText("acc"),
            "value" to rich(VarRef.Named("acc"), VarRef.BlockOutput(outer.id), VarRef.BlockOutput(inner.id), " "),
        )
        val result = run(loop(outer, *loop(inner, append).toTypedArray()) + block("return", null, "text" to rich(VarRef.Named("acc"))))
        assertEquals(null, result.error)
        assertEquals("a1 a2 b1 b2 ", result.output)
    }

    @Test
    fun whileLoop() {
        val loopGroup = group()
        val start = block("while", loopGroup, "left" to rich(VarRef.Named("n")), "op" to ParamValue.Choice("not_equals"), "right" to richText("xxx"))
        val add = block("set_variable", null, "name" to richText("n"), "value" to rich(VarRef.Named("n"), "x"))
        val result = run(loop(start, add) + block("return", null, "text" to rich(VarRef.Named("n"))))
        assertEquals(null, result.error)
        assertEquals("xxx", result.output)
    }

    @Test
    fun jsonBuildSetAndList() {
        val built = block("json_build", null, "items" to richText("title: Ahoj\nuser.id: 42\ntags: [\"a\"]"))
        val edited = block(
            "json_set", null, "source" to rich(VarRef.BlockOutput(built.id)),
            "mode" to ParamValue.Choice("append"), "path" to richText("tags"), "value" to richText("b"),
        )
        val count = block(
            "list", null, "source" to rich(VarRef.BlockOutput(edited.id)),
            "op" to ParamValue.Choice("count"),
        )
        val tags = block("json_get", null, "source" to rich(VarRef.BlockOutput(edited.id)), "path" to richText("tags"))
        val joined = block("list", null, "source" to rich(VarRef.BlockOutput(tags.id)), "op" to ParamValue.Choice("join"), "separator" to richText("+"))
        val result = run(listOf(built, edited, count, tags, joined, block("return", null, "text" to rich(VarRef.BlockOutput(edited.id), " | ", VarRef.BlockOutput(joined.id)))))
        assertEquals(null, result.error)
        assertEquals("""{"title":"Ahoj","user":{"id":42},"tags":["a","b"]} | a+b""", result.output)
    }

    @Test
    fun httpResponseObject() {
        val ok = cz.natsu.automation.engine.HttpBlocks.responseObject(
            cz.natsu.automation.engine.HttpBlocks.Response(
                200, mapOf("Content-Type" to "application/json"), """{"items":[{"url":"u1"}]}""", null
            )
        )
        assertEquals("""{"status":200,"ok":true,"headers":{"content-type":"application/json"},"body":{"items":[{"url":"u1"}]}}""", ok.toString())
        assertEquals("u1", JsonTools.asText(JsonTools.select(ok, JsonTools.parsePath("body.items[0].url"))))

        val saved = cz.natsu.automation.engine.HttpBlocks.responseObject(
            cz.natsu.automation.engine.HttpBlocks.Response(404, emptyMap(), "nope", "file:///x.png")
        )
        assertEquals("""{"status":404,"ok":false,"headers":{},"body":"nope","file":"file:///x.png"}""", saved.toString())
    }

    @Test
    fun examplesBuildValidAutomations() {
        val all = cz.natsu.automation.ui.list.Examples.all
        assertEquals(all.size, all.map { it.name }.toSet().size)
        for (example in all) {
            val blocks = example.build().blocks
            assertEquals(example.name, true, cz.natsu.automation.engine.Structure.analyze(blocks).valid)
            assertEquals(example.name, true, example.description.isNotBlank())
            // every block type exists and outputs refer to blocks of the same automation
            val ids = blocks.map { it.id }.toSet()
            for (block in blocks) {
                assertEquals(block.type, true, Blocks.get(block.type) != null)
                for (value in block.params.values) {
                    if (value is ParamValue.Rich) for (segment in value.segments) {
                        val ref = (segment as? Segment.Var)?.ref as? VarRef.BlockOutput ?: continue
                        assertEquals("${example.name}: ${block.type}", true, ref.blockId in ids)
                    }
                }
            }
        }
        // fresh ids every time
        assertEquals(false, all.first().build().blocks.first().id == all.first().build().blocks.first().id)
    }

    @Test
    fun hugeOutputIsShortenedOnlyInLog() {
        val huge = "x".repeat(300_000)
        val set = block("set_variable", null, "name" to richText("big"), "value" to richText(huge))
        val text = block("text", null, "text" to rich(VarRef.Named("big")))
        val result = run(listOf(set, text, block("return", null, "text" to rich(VarRef.BlockOutput(text.id)))))
        assertEquals(huge, result.output)
        val logged = result.log.first { it.blockId == text.id }.output!!
        assertEquals(true, logged.length < 5000)
        assertEquals(true, logged.endsWith("celkem 300000 znaků)"))
    }

    @Test
    fun breakOutsideLoopIsError() {
        val result = run(listOf(block("loop_control", null)))
        assertEquals(true, result.error?.contains("není uvnitř opakování"))
    }
}
