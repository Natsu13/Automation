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
import cz.natsu.automation.engine.Crypto
import cz.natsu.automation.engine.HtmlBlocks
import cz.natsu.automation.engine.HtmlWindow
import cz.natsu.automation.engine.RunUi
import cz.natsu.automation.engine.ScriptReader
import cz.natsu.automation.engine.Structure
import cz.natsu.automation.i18n.I18n
import cz.natsu.automation.i18n.Language
import cz.natsu.automation.ui.editor.ScriptWriter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class CryptoAndHtmlTest {
    init {
        I18n.force(Language.Czech)
    }

    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun block(type: String, group: String? = null, vararg params: Pair<String, ParamValue>): Block {
        val def = Blocks.get(type) ?: error("unknown block $type")
        return def.newBlock(group).let { it.copy(params = it.params + params) }
    }

    private fun output(id: String) = ParamValue.Rich(listOf(Segment.Var(VarRef.BlockOutput(id))))

    // ---------- encryption ----------

    private val fast = 10_000

    @Test
    fun encryptsAndDecryptsWithAKey() {
        val key = Crypto.derive("tajne heslo", Crypto.newSalt(), fast)
        val text = "Heslo k bance: 1234 ✔ – ěščřžýáíé"
        val locked = Crypto.encrypt(text, key)
        assertTrue(locked.startsWith(Crypto.DATA_PREFIX))
        assertFalse(locked.contains("1234"))
        // A new IV every time: the same text never looks the same.
        assertNotEquals(locked, Crypto.encrypt(text, key))
        assertEquals(text, Crypto.decrypt(locked, key))
        // The key as text (the output of "Klíč z hesla") works the same.
        assertEquals(text, Crypto.decryptWith(locked, key.text))
    }

    @Test
    fun theSamePasswordMakesTheSameKeyFromTheEncryptedText() {
        val key = Crypto.derive("heslo", Crypto.newSalt(), fast)
        val check = Crypto.encrypt("ok", key)
        val (salt, rounds) = Crypto.saltOf(check)!!
        assertEquals(fast, rounds)
        val again = Crypto.derive("heslo", salt, rounds)
        assertEquals(key.text, again.text)
        assertEquals("ok", Crypto.decrypt(check, again))
        // Straight with the password: the salt comes from the text.
        assertEquals("ok", Crypto.decryptWith(check, "heslo"))
    }

    @Test
    fun aWrongPasswordIsAnError() {
        val key = Crypto.derive("spravne", Crypto.newSalt(), fast)
        val check = Crypto.encrypt("ok", key)
        val (salt, rounds) = Crypto.saltOf(check)!!
        try {
            Crypto.decrypt(check, Crypto.derive("spatne", salt, rounds))
            fail("a wrong password decrypted")
        } catch (e: BlockException) {
            assertEquals("špatné heslo nebo poškozená data", e.message)
        }
    }

    @Test
    fun aChangedTextIsAnError() {
        val key = Crypto.derive("heslo", Crypto.newSalt(), fast)
        val locked = Crypto.encrypt("text", key)
        val data = locked.substringAfterLast(':')
        val tampered = locked.substringBeforeLast(':') + ":" + data.replaceRange(20, 21, if (data[20] == 'A') "B" else "A")
        // The rounds in the header are checked as well (another key would be made from them).
        val otherRounds = locked.replace(":$fast:", ":${fast + 1}:")
        for (bad in listOf(tampered, otherRounds)) {
            try {
                Crypto.decrypt(bad, key)
                fail("changed data decrypted")
            } catch (e: BlockException) {
                // expected
            }
        }
    }

    @Test
    fun passwordsHaveEveryKind() {
        repeat(50) {
            val password = Crypto.password(12, listOf(Crypto.LOWER, Crypto.UPPER, Crypto.DIGITS, Crypto.SYMBOLS))
            assertEquals(12, password.length)
            assertTrue(password.any { it in Crypto.LOWER })
            assertTrue(password.any { it in Crypto.UPPER })
            assertTrue(password.any { it in Crypto.DIGITS })
            assertTrue(password.any { it in Crypto.SYMBOLS })
        }
        assertTrue(Crypto.password(30, listOf(Crypto.DIGITS)).all { it.isDigit() })
    }

    @Test
    fun blocksEncryptAndDecrypt() {
        val key = block("derive_key", null, "password" to richText("heslo"), "rounds" to ParamValue.Number(fast.toFloat()))
        val enc = block("encrypt", null, "text" to richText("tajemství"), "key" to output(key.id))
        val dec = block("decrypt", null, "text" to output(enc.id), "key" to output(key.id))
        val result = runBlocking { AutomationRunner.run(FakeContext(), listOf(key, enc, dec), "") }
        assertTrue(result.error, result.success)
        val log = result.log.associateBy { it.blockId }
        assertEquals("tajemství", log[dec.id]?.output)
        // The key and the password stay out of the log.
        assertFalse(log[key.id]?.output.orEmpty().startsWith(Crypto.KEY_PREFIX))
    }

    // ---------- the HTML window ----------

    /** A "page" that makes [calls] one after another and closes the window with "zavřeno". */
    private class FakePage(val calls: List<Pair<String, String>>) : RunUi {
        val replies = mutableListOf<String>()
        var sheet = false
        override suspend fun askText(title: String, initial: String, multiline: Boolean, hidden: Boolean) = initial
        override suspend fun choose(title: String, options: List<String>) = options.firstOrNull()
        override suspend fun showText(title: String, text: String) = true
        override suspend fun pickFile(mime: String): String? = null
        override suspend fun showImages(title: String, images: List<String>, pick: Boolean): String? = null

        override suspend fun showHtml(window: HtmlWindow): String {
            sheet = window.sheet
            val scripts = Channel<String>(Channel.UNLIMITED)
            window.attach { scripts.trySend(it) }
            for ((index, call) in calls.withIndex()) {
                window.call((index + 1).toString(), call.first, call.second)
                replies += scripts.receive()
            }
            return "zavřeno"
        }
    }

    @Test
    fun stepsInsideAnswerThePage() {
        val group = UUID.randomUUID().toString()
        val window = block(HtmlBlocks.WINDOW, group, "html" to richText("<p>ahoj</p>"))
        val action = block("json_get", null, "source" to output(window.id), "path" to richText("action"))
        val failIf = block("if", UUID.randomUUID().toString(),
            "left" to output(action.id), "op" to ParamValue.Choice("equals"), "right" to richText("spatne"))
        val boom = block("fail", null, "message" to richText("tohle nejde"))
        val endIf = block("end_if", failIf.groupId)
        val answer = block("json_get", null, "source" to output(window.id), "path" to richText("data.jmeno"))
        val end = block(HtmlBlocks.END_WINDOW, group)
        val after = block("text", null, "text" to output(window.id))
        val blocks = listOf(window, action, failIf, boom, endIf, answer, end, after)
        assertTrue(Structure.analyze(blocks).valid)

        val page = FakePage(listOf("pozdrav" to """{"jmeno":"Jan"}""", "spatne" to "", "pozdrav" to """{"jmeno":"Eva"}"""))
        val result = runBlocking { AutomationRunner.run(FakeContext(), blocks, "", ui = page) }
        assertTrue(result.error, result.success)
        assertEquals(3, page.replies.size)
        assertTrue(page.replies[0], page.replies[0].contains("""_reply("1", true, "Jan")"""))
        // An error inside rejects only that call; the window and the run go on.
        assertTrue(page.replies[1], page.replies[1].contains("""_reply("2", false, "Zastavit s chybou: tohle nejde")"""))
        assertTrue(page.replies[2], page.replies[2].contains("""_reply("3", true, "Eva")"""))
        // After the window the block's value is what the page closed it with.
        assertEquals("zavřeno", result.log.last { it.blockId == after.id }.output)
    }

    @Test
    fun returnInsideTheWindowAnswersThePage() {
        val group = UUID.randomUUID().toString()
        val window = block(HtmlBlocks.WINDOW, group, "html" to richText("<p>ahoj</p>"))
        val ret = block("return", null, "text" to richText("odpověď"))
        val end = block(HtmlBlocks.END_WINDOW, group)
        val after = block("text", null, "text" to richText("dál"))
        val page = FakePage(listOf("cokoli" to ""))
        val result = runBlocking { AutomationRunner.run(FakeContext(), listOf(window, ret, end, after), "", ui = page) }
        assertTrue(result.error, result.success)
        assertTrue(page.replies[0], page.replies[0].contains("""_reply("1", true, "odpověď")"""))
        // The run went on after the window.
        assertEquals("dál", result.log.last().output)
    }

    @Test
    fun aScriptOpensTheWindowToo() {
        val code = """
            volani = html_window("<p>ahoj</p>", "Nadpis", false, false, "sheet")
                if volani.action == "spatne"
                    fail("tohle nejde")
                end
                return "Ahoj " & volani.data.jmeno
            end
            return "konec: " & volani
        """.trimIndent()
        val script = block("script", null, "code" to richText(code))
        val page = FakePage(listOf("pozdrav" to """{"jmeno":"Jan"}""", "spatne" to "", "pozdrav" to """{"jmeno":"Eva"}"""))
        val result = runBlocking { AutomationRunner.run(FakeContext(), listOf(script), "", ui = page) }
        assertTrue(result.error, result.success)
        assertTrue(page.replies[0], page.replies[0].contains("""_reply("1", true, "Ahoj Jan")"""))
        assertTrue(page.replies[1], page.replies[1].contains("""_reply("2", false,""") && page.replies[1].contains("tohle nejde"))
        assertTrue(page.replies[2], page.replies[2].contains("""_reply("3", true, "Ahoj Eva")"""))
        assertEquals("konec: zavřeno", result.log.single().output)
        assertTrue(page.sheet)
    }

    @Test
    fun theShimGoesIntoTheHead() {
        val page = HtmlWindow.withShim("<!doctype html><html><head><title>x</title></head><body></body></html>")
        assertTrue(page.indexOf("window.automation") in page.indexOf("<head>")..page.indexOf("<title>"))
        assertTrue(HtmlWindow.withShim("<p>a</p>").startsWith("<script>"))
        // Lines that end a JavaScript string are escaped.
        assertEquals("\"a\\u2028b\\\"c\"", HtmlWindow.js("a b\"c"))
    }

    @Test
    fun theWindowReadsBackFromAScript() {
        val text = """
            pozadavek = html_window("<b>ahoj</b>\n<i>x</i>", "Nadpis", false, true)
                akce = json_get(pozadavek, "action", "value")
                return akce
            end
        """.trimIndent()
        val blocks = ScriptReader.read(text).blocks
        assertEquals(listOf(HtmlBlocks.WINDOW, "json_get", "return", HtmlBlocks.END_WINDOW), blocks.map { it.type })
        assertEquals(ParamValue.Bool(true), blocks[0].params["secure"])
        // Written again it reads back the same (the writer names the steps by their outputs).
        val written = ScriptWriter(blocks).write()
        assertTrue(written, written.startsWith("page_call = html_window("))
        assertEquals(written, ScriptWriter(ScriptReader.read(written).blocks).write())
    }

    @Test
    fun theNotepadIsValid() {
        val blocks = ScriptReader.read(cz.natsu.automation.ui.list.NotepadExample.script()).blocks
        assertTrue(Structure.analyze(blocks).valid)
        assertTrue(blocks.any { it.type == HtmlBlocks.WINDOW })
        assertTrue(blocks.any { it.type == "derive_key" })
        assertTrue(blocks.any { it.type == "bio_unlock" })
    }
}
