package cz.natsu.automation

import android.content.Context
import android.content.ContextWrapper
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.engine.AutomationRunner
import cz.natsu.automation.engine.BlockException
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.Calc
import cz.natsu.automation.engine.HttpBlocks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Try / on error, loop position, calculations, list statistics, choosing objects. */
class ControlBlocksTest {
    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun block(type: String, group: String? = null, vararg params: Pair<String, ParamValue>): Block {
        val def = Blocks.get(type) ?: error("unknown block $type")
        return def.newBlock(group).let { it.copy(params = it.params + params) }
    }

    private fun rich(vararg parts: Any) = ParamValue.Rich(parts.map {
        when (it) {
            is String -> Segment.Text(it)
            is VarRef -> Segment.Var(it)
            else -> error("bad part")
        }
    })

    private fun run(blocks: List<Block>) = runBlocking { AutomationRunner.run(FakeContext(), blocks, "") }
    private fun group() = UUID.randomUUID().toString()
    private fun ret(vararg parts: Any) = block("return", null, "text" to rich(*parts))
    private fun append(name: String, vararg parts: Any) =
        block("set_variable", null, "name" to richText(name), "value" to rich(VarRef.Named(name), *parts))

    /** A step that always fails: invalid JSON. */
    private fun failing() = block("json_get", null, "source" to richText("{nope"))

    // ---------- calculator ----------

    @Test
    fun calculator() {
        assertEquals(7.0, Calc.evaluate("1 + 2 * 3"), 0.0)
        assertEquals(9.0, Calc.evaluate("(1 + 2) * 3"), 0.0)
        assertEquals(2.5, Calc.evaluate("5 / 2"), 0.0)
        assertEquals(1210.0, Calc.evaluate("1 000 * 1,21"), 1e-9)
        assertEquals(1.0, Calc.evaluate("10 % 3"), 0.0)
        assertEquals(1024.0, Calc.evaluate("2 ^ 10"), 0.0)
        assertEquals(-4.0, Calc.evaluate("-(2 + 2)"), 0.0)
        assertEquals(3.14, Calc.evaluate("round(3,14159; 2)"), 1e-9)
        assertEquals(5.0, Calc.evaluate("max(1; 5; 3)"), 0.0)
        assertEquals(3.0, Calc.evaluate("sqrt(9)"), 0.0)
        for (bad in listOf("1 +", "2 * (3", "abc(1)", "1 / 0", "1 x 2")) {
            try {
                Calc.evaluate(bad)
                error("\"$bad\" should fail")
            } catch (e: BlockException) {
                // expected
            }
        }
    }

    @Test
    fun calculateBlockRounds() {
        val calc = block("calculate", null, "expression" to richText("10 / 3"), "places" to richText("2"))
        assertEquals("3.33", run(listOf(calc, ret(VarRef.BlockOutput(calc.id)))).output)
    }

    // ---------- try / on error ----------

    @Test
    fun tryCatchesErrorAndContinues() {
        val g = group()
        val attempt = block("try", g)
        val result = run(
            listOf(
                attempt,
                append("log", "a"),
                failing(),
                append("log", "NEVER"),
                block("catch", g),
                append("log", "c"),
                block("end_try", g),
                append("log", "d"),
                ret(VarRef.Named("log"), " | ", VarRef.BlockOutput(attempt.id)),
            )
        )
        assertNull(result.error)
        assertTrue(result.output!!, result.output!!.startsWith("acd | Získat z JSON: text není platný JSON"))
    }

    @Test
    fun trySuccessSkipsCatch() {
        val g = group()
        val attempt = block("try", g)
        val result = run(
            listOf(
                attempt, append("log", "a"), block("catch", g), append("log", "c"), block("end_try", g),
                ret(VarRef.Named("log"), "[", VarRef.BlockOutput(attempt.id), "]"),
            )
        )
        assertEquals("a[]", result.output)
    }

    @Test
    fun tryRetriesThenGivesUp() {
        val g = group()
        val result = run(
            listOf(
                block("try", g, "attempts" to ParamValue.Number(3f), "delay" to ParamValue.Number(0f)),
                append("n", "x"),
                failing(),
                block("end_try", g),
                ret(VarRef.Named("n")),
            )
        )
        assertNull(result.error)
        assertEquals("xxx", result.output)
    }

    @Test
    fun errorOutsideTryStopsTheRun() {
        val g = group()
        val result = run(listOf(block("try", g), block("end_try", g), failing(), ret("never")))
        assertTrue(result.error!!.contains("není platný JSON"))
    }

    @Test
    fun nestedTryCatchesInnermost() {
        val outer = group()
        val inner = group()
        val result = run(
            listOf(
                block("try", outer),
                block("try", inner),
                failing(),
                block("catch", inner),
                append("log", "inner"),
                block("end_try", inner),
                failing(),
                block("catch", outer),
                append("log", "+outer"),
                block("end_try", outer),
                ret(VarRef.Named("log")),
            )
        )
        assertEquals("inner+outer", result.output)
    }

    @Test
    fun breakInsideTryInsideLoop() {
        val loop = group()
        val t = group()
        val repeat = block("repeat", loop, "count" to richText("5"))
        val stop = group()
        val result = run(
            listOf(
                repeat,
                block("try", t),
                append("log", VarRef.BlockOutput(repeat.id)),
                block("if", stop, "left" to rich(VarRef.BlockOutput(repeat.id)), "op" to ParamValue.Choice("equals"), "right" to richText("2")),
                block("loop_control", null, "action" to ParamValue.Choice("break")),
                block("end_if", stop),
                block("end_try", t),
                block("end_while", loop),
                // A failure after the loop must not be caught by the "try" the loop was left from.
                failing(),
            )
        )
        assertTrue(result.error!!.contains("není platný JSON"))
        // Two rounds ran before the break.
        assertEquals(2, result.log.count { it.title == "Nastavit proměnnou" })
    }

    // ---------- loop position ----------

    @Test
    fun loopIndexInNestedLoops() {
        val outer = block("for_each", group(), "list" to richText("[\"a\",\"b\"]"))
        val inner = block("repeat", group(), "count" to richText("2"))
        val outerIndex = block("loop_index", null)
        val innerIndex = block("loop_index", null, "zero" to ParamValue.Bool(true))
        val result = run(
            listOf(
                outer, outerIndex,
                inner, innerIndex,
                append("log", VarRef.BlockOutput(outerIndex.id), ".", VarRef.BlockOutput(innerIndex.id), " "),
                block("end_while", inner.groupId),
                block("end_while", outer.groupId),
                ret(VarRef.Named("log")),
            )
        )
        assertEquals("1.0 1.1 2.0 2.1 ", result.output)
    }

    // ---------- lists and choosing ----------

    @Test
    fun listStatistics() {
        fun op(name: String): String? {
            val list = block("list", null, "source" to richText("[1, 2.5, \"3,5\"]"), "op" to ParamValue.Choice(name))
            return run(listOf(list, ret(VarRef.BlockOutput(list.id)))).output
        }
        assertEquals("7", op("sum"))
        assertEquals("1", op("min"))
        assertEquals("3.5", op("max"))
        assertEquals(7.0 / 3, op("avg")!!.toDouble(), 1e-9)
    }

    @Test
    fun chooseReturnsWholeObject() {
        // Without a window the first option is taken.
        val choose = block(
            "choose", null,
            "options" to richText("""[{"title":"A","id":1},{"title":"B","id":2}]"""),
            "label" to richText("title"),
        )
        assertEquals("""{"title":"A","id":1}""", run(listOf(choose, ret(VarRef.BlockOutput(choose.id)))).output)
    }

    @Test
    fun localHosts() {
        for (h in listOf("192.168.1.10", "10.0.0.2", "172.20.1.1", "homeassistant.local", "router.lan")) {
            assertTrue(h, HttpBlocks.isLocalHost(h))
        }
        for (h in listOf("httpbin.org", "8.8.8.8", "172.32.0.1", "example.com")) {
            assertTrue(h, !HttpBlocks.isLocalHost(h))
        }
    }
}
