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
    init {
        // The texts checked here are the Czech originals.
        cz.natsu.automation.i18n.I18n.force(cz.natsu.automation.i18n.Language.Czech)
    }

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
    fun groupsOfSteps() {
        // A script inside a script: its return ends only that step.
        assertEquals("6|done", eval("""
            x = script
                a = 2
                return a * 3
            end
            return x & "|done"
        """.trimIndent()))
        // try … catch: the value is the error text, the catch branch runs.
        val failed = eval("""
            err = try
                y = 1 / nic_takove
            catch
                handled = true
            end
            return handled & "|" & (err != "")
        """.trimIndent(), "nic_takove" to null)
        assertEquals("true|true", failed)
        // Passed: the value is empty and catch does not run.
        assertEquals("|ok", eval("""
            handled = "ok"
            err = try
                y = 1
            catch
                handled = "ne"
            end
            return err & "|" & handled
        """.trimIndent()))
        // parallel branches, lock and a time limit run their steps.
        assertEquals("3", eval("""
            n = 0
            parallel
                n += 1
            branch
                n += 1
            end
            lock("a")
                n += 1
            end
            time_limit(5)
                x = n
            end
            return x
        """.trimIndent()))
        assertTrue(fails("time_limit(0.001)\n    i = 0\n    while true\n        i += 1\n    end\nend").contains("časový limit"))
        // Named like a block, but with something after the bracket it is still a call.
        assertTrue(fails("x = try(1) + 1").isNotEmpty())
    }

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

    private fun choose(value: String): String? {
        val g = UUID.randomUUID().toString()
        return run(
            listOf(
                block("switch", g, "value" to richText(value)),
                block("case", g, "value" to richText("a")),
                ret("A"),
                block("case", g, "value" to richText("b\nc\n10")),
                ret("BC"),
                block("else", g),
                ret("jiné"),
                block("end_switch", g),
            )
        ).output
    }

    @Test
    fun switchPicksTheMatchingBranch() {
        assertEquals("A", choose("A"))
        assertEquals("BC", choose("c"))
        assertEquals("BC", choose("10,0"))
        assertEquals("jiné", choose("x"))
    }

    @Test
    fun parallelBranchesAllRun() {
        val g = UUID.randomUUID().toString()
        val wait = block("wait", null, "seconds" to ParamValue.Number(0.5f))
        val blocks = listOf(
            block("parallel", g),
            wait,
            set("a", "1"),
            block("parallel_branch", g),
            set("b", "2"),
            block("parallel_branch", g),
            set("c", "3"),
            block("end_parallel", g),
            ret(VarRef.Named("a"), VarRef.Named("b"), VarRef.Named("c")),
        )
        val started = System.currentTimeMillis()
        assertEquals("123", run(blocks).output)
        // The branches ran at the same time: the whole thing takes about as long as the slowest one.
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun parallelLoopKeepsOrderAndPosition() {
        val g = UUID.randomUUID().toString()
        val each = block(
            "for_each", g, "list" to richText("a\nb\nc\nd"),
            "parallel" to ParamValue.Bool(true), "collect" to richText("vysledky"),
        )
        val position = block("loop_index")
        val line = block("text", null, "text" to rich(VarRef.BlockOutput(position.id), ":", VarRef.BlockOutput(each.id)))
        val blocks = listOf(
            each,
            block("wait", null, "seconds" to ParamValue.Number(1f)),
            position,
            line,
            block("end_while", g),
            ret(VarRef.Named("vysledky"), " ", VarRef.BlockOutput(line.id)),
        )
        val started = System.currentTimeMillis()
        // Every round had its own item and position; after the loop the steps hold the last round.
        assertEquals("[\"1:a\",\"2:b\",\"3:c\",\"4:d\"] 4:d", run(blocks).output)
        // One after another it would take 4 s.
        assertTrue(System.currentTimeMillis() - started < 3000)
    }

    @Test
    fun parallelLoopSkipErrorAndLimit() {
        val g = UUID.randomUUID().toString()
        fun repeat(vararg body: Block) = listOf(
            block(
                "repeat", g, "count" to richText("3"), "parallel" to ParamValue.Bool(true),
                "limit" to ParamValue.Number(1f), "collect" to richText("v"),
            ),
            *body,
            block("end_while", g),
            ret(VarRef.Named("v")),
        )
        // "Next round" ends just that round; the steps after it do not run.
        val skipped = repeat(
            block("text", null, "text" to richText("x")),
            block("loop_control", null, "action" to ParamValue.Choice("continue")),
            block("fail", null, "message" to richText("nemá nastat")),
        )
        assertEquals("[\"x\",\"x\",\"x\"]", run(skipped).output)

        // "End the loop" in one round ends it for all: the rounds that did not finish have no result.
        val ended = repeat(
            block("text", null, "text" to richText("x")),
            block("loop_control", null, "action" to ParamValue.Choice("break")),
        )
        assertEquals("[\"x\",\"\",\"\"]", run(ended).output)

        val failing = run(repeat(block("fail", null, "message" to richText("kolo spadlo"))))
        assertTrue(failing.error!!.contains("kolo spadlo"))

        // Without the switch the same loop runs one round after another, as before.
        val h = UUID.randomUUID().toString()
        val plain = block("repeat", h, "count" to richText("3"))
        assertEquals(
            "3", run(listOf(plain, set("n", "x"), block("end_while", h), ret(VarRef.BlockOutput(plain.id)))).output
        )
    }

    @Test
    fun parallelLoopFirstRoundWins() {
        val g = UUID.randomUUID().toString()
        val h = UUID.randomUUID().toString()
        val each = block("for_each", g, "list" to richText("pomalý\nrychlý\npomalý"), "parallel" to ParamValue.Bool(true))
        val found = block("text", null, "text" to rich("vyhrál ", VarRef.BlockOutput(each.id)))
        val blocks = listOf(
            each,
            block("if", h, "left" to rich(VarRef.BlockOutput(each.id)), "right" to richText("pomalý")),
            block("wait", null, "seconds" to ParamValue.Number(5f)),
            block("end_if", h),
            found,
            block("loop_control", null, "action" to ParamValue.Choice("break")),
            block("end_while", g),
            ret(VarRef.BlockOutput(found.id)),
        )
        val started = System.currentTimeMillis()
        // After the loop its steps hold the round that ended it, and the slow rounds were not waited for.
        assertEquals("vyhrál rychlý", run(blocks).output)
        assertTrue(System.currentTimeMillis() - started < 3000)

        // An error in one round stops the others as well.
        val failing = listOf(
            each,
            block("if", h, "left" to rich(VarRef.BlockOutput(each.id)), "right" to richText("pomalý")),
            block("wait", null, "seconds" to ParamValue.Number(5f)),
            block("end_if", h),
            block("fail", null, "message" to richText("kolo spadlo")),
            block("end_while", g),
        )
        val again = System.currentTimeMillis()
        assertTrue(run(failing).error!!.contains("kolo spadlo"))
        assertTrue(System.currentTimeMillis() - again < 3000)
    }

    @Test
    fun parallelRoundsTakeTurnsInLock() {
        val g = UUID.randomUUID().toString()
        val l = UUID.randomUUID().toString()
        val read = block("text", null, "text" to rich(VarRef.Named("soucet")))
        val sum = block("math", null, "a" to rich(VarRef.BlockOutput(read.id)), "b" to richText("1"))
        // Read, wait, write: without the lock every round would read 0 and the sum would be 1.
        fun counting(locked: Boolean) = listOf(
            set("soucet", "0"),
            block("repeat", g, "count" to richText("20"), "parallel" to ParamValue.Bool(true), "limit" to ParamValue.Number(20f)),
        ) + (if (locked) listOf(block("lock", l, "name" to richText("soucet"))) else emptyList()) + listOf(
            read,
            block("wait", null, "seconds" to ParamValue.Number(0.02f)),
            sum,
            block("set_variable", null, "name" to richText("soucet"), "value" to rich(VarRef.BlockOutput(sum.id))),
        ) + (if (locked) listOf(block("end_lock", l)) else emptyList()) + listOf(
            block("end_while", g),
            ret(VarRef.Named("soucet")),
        )
        assertEquals("20", run(counting(locked = true)).output)
        assertTrue(run(counting(locked = false)).output != "20")

        // "Změnit proměnnou" does the same in one step, without a lock.
        val atomic = listOf(
            block("repeat", g, "count" to richText("200"), "parallel" to ParamValue.Bool(true), "limit" to ParamValue.Number(50f)),
            block("change_variable", null, "name" to richText("soucet"), "value" to richText("2")),
            block("end_while", g),
            ret(VarRef.Named("soucet")),
        )
        assertEquals("400", run(atomic).output)
    }

    @Test
    fun lockPassesJumpsAndNests() {
        val g = UUID.randomUUID().toString()
        val l = UUID.randomUUID().toString()
        val inner = UUID.randomUUID().toString()
        val loop = block("repeat", g, "count" to richText("5"))
        // "End the loop" inside a lock ends the loop around it; a lock inside the same lock does not wait for itself.
        val blocks = listOf(
            loop,
            block("lock", l),
            block("lock", inner),
            block("change_variable", null, "name" to richText("n"), "value" to richText("1")),
            block("if", "i", "left" to rich(VarRef.Named("n")), "right" to richText("3")),
            block("loop_control", null, "action" to ParamValue.Choice("break")),
            block("end_if", "i"),
            block("end_lock", inner),
            block("end_lock", l),
            block("end_while", g),
            ret(VarRef.Named("n")),
        )
        assertEquals("3", run(blocks).output)

        val list = listOf(
            block("change_variable", null, "name" to richText("s"), "op" to ParamValue.Choice("push"), "value" to richText("a")),
            block("change_variable", null, "name" to richText("s"), "op" to ParamValue.Choice("push"), "value" to richText("b")),
            block("change_variable", null, "name" to richText("t"), "op" to ParamValue.Choice("append"), "value" to richText("x")),
            block("change_variable", null, "name" to richText("t"), "op" to ParamValue.Choice("append"), "value" to richText("y")),
            ret(VarRef.Named("s"), VarRef.Named("t")),
        )
        assertEquals("[\"a\",\"b\"]xy", run(list).output)
    }

    @Test
    fun timeLimitFailsSlowSteps() {
        val g = UUID.randomUUID().toString()
        val t = UUID.randomUUID().toString()
        val attempt = block("try", t)
        fun limited(seconds: Float) = listOf(
            attempt,
            block("time_limit", g, "seconds" to ParamValue.Number(1f)),
            block("wait", null, "seconds" to ParamValue.Number(seconds)),
            block("end_time_limit", g),
            ret("stihlo se"),
            block("catch", t),
            ret(VarRef.BlockOutput(attempt.id)),
            block("end_try", t),
        )
        assertEquals("stihlo se", run(limited(0.1f)).output)
        val started = System.currentTimeMillis()
        assertTrue(run(limited(10f)).output!!.contains("nestihly do 1 s"))
        assertTrue(System.currentTimeMillis() - started < 4000)
    }

    @Test
    fun parallelErrorAndReturn() {
        val g = UUID.randomUUID().toString()
        val t = UUID.randomUUID().toString()
        val attempt = block("try", t)
        val failing = listOf(
            attempt,
            block("parallel", g),
            set("a", "1"),
            block("parallel_branch", g),
            block("fail", null, "message" to richText("větev spadla")),
            block("end_parallel", g),
            ret("nemá nastat"),
            block("catch", t),
            ret(VarRef.BlockOutput(attempt.id)),
            block("end_try", t),
        )
        assertTrue(run(failing).output!!.contains("větev spadla"))

        val returning = listOf(
            block("parallel", g),
            ret("z větve"),
            block("parallel_branch", g),
            set("a", "1"),
            block("end_parallel", g),
            ret("za blokem"),
        )
        assertEquals("z větve", run(returning).output)
    }

    @Test
    fun scriptCallsBlocks() {
        val code = """
            velke = change_case("ahoj", "upper")
            slova = count({source: "a b c", what: "words"})
            nahrazeno = blok("replace", {source: "a-b", find: "-", replacement: "+"})
            vrat velke & " " & slova & " " & nahrazeno
        """.trimIndent()
        assertEquals("AHOJ 3 a+b", run(listOf(block("script", null, "code" to richText(code)), ret("x"))).let {
            // The script's value is the block's output; read it from the log of the first step.
            it.log.first().output
        })
        val bad = run(listOf(block("script", null, "code" to richText("count({nic: 1})"))))
        assertTrue(bad.error ?: "", bad.error!!.contains("neznámý parametr"))
        val control = run(listOf(block("script", null, "code" to richText("blok(\"if\")"))))
        assertTrue(control.error ?: "", control.error!!.contains("volat nejde"))
    }

    @Test
    fun automationWrittenAsScriptRunsTheSame() {
        val g = UUID.randomUUID().toString()
        val calc = block("math", null, "mode" to ParamValue.Choice("expert"), "expression" to rich(VarRef.Named("i"), "+1"))
        val blocks = listOf(
            set("i", "0"),
            calc,
            block("if", g, "left" to rich(VarRef.BlockOutput(calc.id)), "op" to ParamValue.Choice("greater_eq"), "right" to richText("1")),
            ret("velké ", VarRef.BlockOutput(calc.id)),
            block("else", g),
            ret("malé"),
            block("end_if", g),
        )
        val script = cz.natsu.automation.ui.editor.ScriptWriter(blocks).write()
        assertEquals(
            """
            i = 0
            result = calc(i & "+1")
            if result >= 1
                return "velké " & result
            else
                return "malé"
            end
            """.trimIndent(), script
        )
        // An "if" with an empty branch is turned round, and a block shows what it is set to.
        val found = block("extract")
        val empty = listOf(
            found,
            block("if", g, "left" to rich(VarRef.BlockOutput(found.id)), "op" to ParamValue.Choice("empty")),
            block("else", g),
            ret(VarRef.BlockOutput(found.id)),
            block("end_if", g),
        )
        assertEquals(
            """
            found = extract({source: input, what: "url", which: "first"})
            if found != ""
                return found
            end
            """.trimIndent(), cz.natsu.automation.ui.editor.ScriptWriter(empty).write()
        )
        assertEquals("true", eval("\"Sdílení\" == \"sdílení\" zaroven contains(\"Sdílení\", \"SDÍL\") zaroven not exact(\"A\", \"a\")"))
        // The written script gives the same result as the steps.
        val asScript = block("script", null, "code" to richText(script))
        assertEquals(run(blocks).output, run(listOf(asScript, ret(VarRef.BlockOutput(asScript.id)))).output)
    }

    @Test
    fun examplesAreValidAndTheScriptOneRuns() {
        for (example in cz.natsu.automation.ui.list.Examples.all) {
            val built = example.build().blocks
            assertTrue(example.name, cz.natsu.automation.engine.Structure.analyze(built).valid)
            // Every example can also be written as a script without failing.
            cz.natsu.automation.ui.editor.ScriptWriter(built).write()
        }
        val cart = cz.natsu.automation.ui.list.Examples.all.first { it.name == "Skript: součet košíku" }.build().blocks
        // Without the dialog at the end: 42 + 89.9 + 219 = 350.9, over 300 so 10 % off.
        val result = run(cart.dropLast(1))
        assertEquals("Položek: 3, celkem 315.81", result.log.last().output)
    }

    @Test
    fun triggerTypeComparesInBothLanguages() {
        fun started(op: String, word: String): String? {
            val g = UUID.randomUUID().toString()
            return run(
                listOf(
                    block("if", g, "left" to rich(VarRef.Trigger), "op" to ParamValue.Choice(op), "right" to richText(word)),
                    ret("ano"),
                    block("end_if", g),
                    ret(VarRef.Trigger),
                )
            ).output
        }
        try {
            for (language in listOf(cz.natsu.automation.i18n.Language.Czech, cz.natsu.automation.i18n.Language.English)) {
                cz.natsu.automation.i18n.I18n.force(language)
                // A test run is started by hand: "Ručně" / "Manual".
                assertEquals("ano", started("equals", "ručně"))
                assertEquals("ano", started("equals", "Manual"))
                assertEquals("ano", started("contains", "ruč"))
                assertEquals("ano", started("not_equals", "Sdílení"))
                assertEquals("true", eval("\"Sdílení\" == \"sharing\" zaroven contains(\"Sharing\", \"sdíl\")"))
            }
            assertEquals("Manual", started("equals", "Sharing"))
        } finally {
            cz.natsu.automation.i18n.I18n.force(cz.natsu.automation.i18n.Language.Czech)
        }
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
