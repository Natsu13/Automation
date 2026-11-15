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
import cz.natsu.automation.engine.Script
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The Skript language, conditions written as an expression, "Jinak pokud" and the new control blocks. */
class ScriptTest {
    private class Vars(val values: MutableMap<String, Any?> = mutableMapOf(), val chips: List<String> = emptyList()) : Script.Host {
        override fun get(name: String): Any? = if (name in values) values[name] else Script.MISSING
        override fun set(name: String, value: Any?) {
            values[name] = value
        }

        override fun chip(index: Int): String = chips[index]
    }

    private fun eval(code: String, vararg vars: Pair<String, Any?>): String =
        Script.text(Script.run(code, Vars(mutableMapOf(*vars))))

    private fun fails(code: String): String = try {
        eval(code)
        error("\"$code\" should fail")
    } catch (e: BlockException) {
        e.message ?: ""
    }

    // ---------- language ----------

    @Test
    fun expressions() {
        assertEquals("7", eval("1 + 2 * 3"))
        assertEquals("9", eval("(1 + 2) * 3"))
        assertEquals("-8", eval("-2 ^ 3"))
        assertEquals("2.5", eval("5 / 2"))
        assertEquals("ahoj světe", eval("\"ahoj\" & ' ' & \"světe\""))
        assertEquals("ab", eval("\"a\" + \"b\""))
        // Texts holding numbers (outputs of other blocks) count as numbers.
        assertEquals("15", eval("a + 5", "a" to "10"))
        assertEquals("true", eval("a > 9", "a" to "10"))
        assertEquals("true", eval("1 < 2 zaroven (2 < 1 nebo not false)"))
        assertEquals("false", eval("1 == 2 || 3 != 3"))
        assertEquals("true", eval("\"1.0\" == 1"))
        assertEquals("b", eval("kdyz(1 > 2, \"a\", \"b\")"))
    }

    @Test
    fun statements() {
        assertEquals(
            "velké", eval(
                """
                # comment
                x = 10
                pokud x > 100
                    vrat "obří"
                jinak pokud x > 5
                    vrat "velké"
                jinak
                    vrat "malé"
                konec
                """.trimIndent()
            )
        )
        assertEquals("15", eval("s = 0\npro i v range(5)\n  s += i\nkonec\ns"))
        assertEquals("4", eval("n = 0\ndokud true\n n += 1\n pokud n > 3\n  ukonci\n konec\nkonec\nvrat n"))
        assertEquals("9", eval("s = 0; for i in [1, 2, 3, 4, 5]; if i % 2 == 0; continue; end; s += i; end; s"))
        assertEquals("1", eval("if true return 1 end"))
    }

    @Test
    fun listsObjectsAndJson() {
        assertEquals("3", eval("len([1, 2, 3])"))
        assertEquals("c", eval("x = [\"a\", \"b\", \"c\"]\nx[-1]"))
        assertEquals("[1,2,9]", eval("x = [1, 2]\nx[2] = 9\nx"))
        assertEquals("{\"jmeno\":\"Jan\",\"vek\":31}", eval("o = {jmeno: \"Jan\", vek: 30}\no.vek += 1\no"))
        // JSON text from another block is read like a list / an object.
        val response = "{\"body\":{\"items\":[{\"price\":120},{\"price\":80},{\"price\":300}]}}"
        assertEquals(
            "420", eval(
                "s = 0\npro p v odpoved.body.items\n pokud p.price > 100\n  s += p.price\n konec\nkonec\nvrat s",
                "odpoved" to response
            )
        )
        assertEquals("a-b-c", eval("join(split(\"a,b,c\", \",\"), \"-\")"))
        assertEquals("", eval("x.neni.tu", "x" to "{}"))
    }

    @Test
    fun functions() {
        assertEquals("AHOJ", eval("upper(\"ahoj\")"))
        assertEquals("3.14", eval("round(3.14159, 2)"))
        assertEquals("5", eval("max(1, 5, 3)"))
        assertEquals("6", eval("sum([1, 2, 3])"))
        assertEquals("42", eval("match(\"id=42;\", \"id=(\\\\d+)\")"))
        assertEquals("true", eval("contains([1, 2, 3], 2) zaroven contains(\"ahoj\", \"ho\")"))
        // The calculator of the "Vypočítat" block is available as a function.
        assertEquals("1210", eval("calc(\"1 000 * 1,21\")"))
        assertEquals("seznam", eval("type(\"[1]\")"))
    }

    @Test
    fun variablesAreSharedWithTheHost() {
        val vars = Vars(mutableMapOf("cena" to "100"))
        Script.run("celkem = cena * 1.21", vars)
        assertEquals(121.0, vars.values["celkem"] as Double, 1e-9)
    }

    @Test
    fun chipsAreValuesNotCode() {
        val chip = "${Script.CHIP_START}0${Script.CHIP_END}"
        // A chip whose value looks like code stays a plain text.
        val vars = Vars(chips = listOf("1 + 1"))
        assertEquals("1 + 1", Script.text(Script.run(chip, vars)))
        assertEquals("<1 + 1>", Script.text(Script.run("\"<$chip>\"", vars)))
        assertEquals("true", Script.text(Script.run("$chip > 5", Vars(chips = listOf("12")))))
    }

    @Test
    fun errorsNameTheLine() {
        assertTrue(fails("x = 1\ny = x +").startsWith("řádek 2"))
        assertTrue(fails("x = 1\n\nvrat nezname").startsWith("řádek 3"))
        assertTrue(fails("pokud true\n x = 1").contains("konec"))
        assertTrue(fails("1 / 0").contains("dělení nulou"))
        assertTrue(fails("\"abc\" * 2").contains("není číslo"))
        assertTrue(fails("dokud true\nkonec").contains("milion"))
        assertTrue(fails("nic_takoveho(1)").contains("neznámá funkce"))
    }

    // ---------- blocks ----------

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

    private fun run(blocks: List<Block>, input: String = "") =
        runBlocking { AutomationRunner.run(FakeContext(), blocks, input) }

    private fun ret(vararg parts: Any) = block("return", null, "text" to rich(*parts))
    private fun set(name: String, value: String) =
        block("set_variable", null, "name" to richText(name), "value" to richText(value))

    @Test
    fun scriptBlockUsesVariablesAndChips() {
        val script = block("script", null, "code" to rich("celkem = cena * 2 + ", VarRef.Input, "\nvrat \"= \" & celkem"))
        val result = run(listOf(set("cena", "10"), script, ret(VarRef.BlockOutput(script.id), " / ", VarRef.Named("celkem"))), "5")
        assertEquals("= 25 / 25", result.output)
    }

    @Test
    fun scriptErrorStopsTheRun() {
        val result = run(listOf(block("script", null, "code" to richText("x = \n1"))))
        assertTrue(result.error ?: "", result.error!!.contains("řádek 1"))
    }

    private fun expert(type: String, group: String, expression: String) =
        block(type, group, "mode" to ParamValue.Choice("expert"), "expression" to richText(expression))

    private fun classify(n: String): String? {
        val g = UUID.randomUUID().toString()
        return run(
            listOf(
                set("n", n),
                expert("if", g, "n < 0"),
                ret("záporné"),
                expert("else_if", g, "n == 0"),
                ret("nula"),
                expert("else_if", g, "n < 10 zaroven n % 2 == 0"),
                ret("malé sudé"),
                block("else", g),
                ret("ostatní"),
                block("end_if", g),
                ret("nemá nastat"),
            )
        ).output
    }

    @Test
    fun elseIfChain() {
        assertEquals("záporné", classify("-3"))
        assertEquals("nula", classify("0"))
        assertEquals("malé sudé", classify("4"))
        assertEquals("ostatní", classify("5"))
    }

    @Test
    fun elseIfBranchSkipsTheRest() {
        val g = UUID.randomUUID().toString()
        val blocks = listOf(
            set("log", ""),
            expert("if", g, "true"),
            set("log", "a"),
            expert("else_if", g, "true"),
            set("log", "b"),
            block("end_if", g),
            ret(VarRef.Named("log")),
        )
        assertEquals("a", run(blocks).output)
        // Without "otherwise", a chain where nothing holds just continues after the end.
        val none = listOf(
            expert("if", g, "false"),
            ret("x"),
            expert("else_if", g, "1 > 2"),
            ret("y"),
            block("end_if", g),
            ret("konec"),
        )
        assertEquals("konec", run(none).output)
    }

    @Test
    fun comparisonsWithEquality() {
        fun check(op: String, left: String, right: String): String? {
            val g = UUID.randomUUID().toString()
            return run(
                listOf(
                    block("if", g, "left" to richText(left), "op" to ParamValue.Choice(op), "right" to richText(right)),
                    ret("ano"),
                    block("end_if", g),
                    ret("ne"),
                )
            ).output
        }
        assertEquals("ano", check("greater_eq", "5", "5"))
        assertEquals("ne", check("greater_eq", "4", "5"))
        assertEquals("ano", check("less_eq", "5", "5,0"))
        assertEquals("ne", check("less_eq", "6", "5"))
    }

    @Test
    fun failBlockIsCaughtByTry() {
        val g = UUID.randomUUID().toString()
        val attempt = block("try", g)
        val blocks = listOf(
            attempt,
            block("fail", null, "message" to richText("moje chyba")),
            ret("nemá nastat"),
            block("catch", g),
            ret(VarRef.BlockOutput(attempt.id)),
            block("end_try", g),
        )
        assertTrue(run(blocks).output!!.contains("moje chyba"))
        assertEquals("Zastavit s chybou: x", run(listOf(block("fail", null, "message" to richText("x")))).error)
    }

    @Test
    fun waitUntilAndComment() {
        val wait = block(
            "wait_until", null,
            "left" to richText("5"), "op" to ParamValue.Choice("greater"), "right" to richText("3"),
        )
        val blocks = listOf(block("comment", null, "text" to richText("poznámka")), wait, ret(VarRef.BlockOutput(wait.id)))
        assertEquals("true", run(blocks).output)
    }
}
