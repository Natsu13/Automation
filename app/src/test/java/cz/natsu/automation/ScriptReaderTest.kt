package cz.natsu.automation

import cz.natsu.automation.data.AutomationTransfer
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.engine.ScriptReader
import cz.natsu.automation.engine.Structure
import cz.natsu.automation.i18n.I18n
import cz.natsu.automation.i18n.Language
import cz.natsu.automation.ui.editor.ScriptWriter
import cz.natsu.automation.ui.list.Examples
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script ("Show as script", or one an AI model wrote from web/ai-guide.md) read back into blocks. */
class ScriptReaderTest {
    @After
    fun czech() = I18n.force(Language.Czech)

    @Test
    fun everyExampleReadsBackToTheSameScript() {
        for (language in listOf(Language.Czech, Language.English)) {
            I18n.force(language)
            for (example in Examples.all) {
                val written = ScriptWriter(example.build().blocks).write()
                val read = ScriptReader.read(written)
                assertTrue(example.name, Structure.analyze(read.blocks).valid)
                assertEquals("${example.name} ($language)", written, ScriptWriter(read.blocks).write())
            }
        }
    }

    @Test
    fun scriptCopiedFromTheApp() {
        val text = """
            found = extract({source: input, what: "url", which: "first"})
            if found != ""
                web_result = web(found, "final_url")
                edited_text = block("replace", web_result, "(?<=[?&])(utm_[a-z_]+|fbclid|gclid|si|igsh|ref|ref_src)=[^&#]*&?", "", true)
                edited_text2 = block("replace", edited_text, "[?&]+(?=#|${'$'})", "", true)
                edited_text3 = block("replace", edited_text2, "https://x.com/", "https://vxtwitter.com/")
                edited_text4 = block("replace", edited_text3, "(www\\.)?facebook\\.com", "facebed.com", true)
                if contains(trigger, "sdílení")
                    share(edited_text4, "Sdílet vyčištěný odkaz")
                else
                    return edited_text4
                end
            end
        """.trimIndent()
        val read = ScriptReader.read(text)
        assertEquals(
            listOf("extract", "if", "web", "replace", "replace", "replace", "replace", "if", "share", "else", "return", "end_if", "end_if"),
            read.blocks.map { it.type },
        )
        assertEquals(text, ScriptWriter(read.blocks).write())
        val facebook = read.blocks[6].params["find"] as ParamValue.Rich
        assertEquals("(www\\.)?facebook\\.com", (facebook.segments.single() as Segment.Text).text)
        // The share step gets the output of the last replace step.
        val shared = read.blocks[8].params["text"] as ParamValue.Rich
        assertEquals(VarRef.BlockOutput(read.blocks[6].id), (shared.segments.single() as Segment.Var).ref)
    }

    @Test
    fun scriptWrittenFromTheGuide() {
        val text = """
            # @name "Ranní počasí"
            # @icon light
            # @event time_daily(time: "07:15", days: "12345")
            # @conditions any
            # @condition launch_condition({source: "wifi_name", op: "equals", right: "Domov"})
            # Try (only as blocks)
                weather = http_request({url: "https://api.example.com/w?key=" & secret("weather_key"), only_body: true})
            # On error (only as blocks)
                toast("Počasí se nepodařilo načíst")
            # End try (only as blocks)
            temp = json_get(weather, "current.temp")
            total = 0
            total = total + 1
            if temp < 0
                notification({title: "Mrzne", text: "Venku je " & temp & " °C"})
            end
            # toast("vypnutý krok")
            # Obyčejná poznámka
        """.trimIndent()
        val read = ScriptReader.read(text)
        assertEquals("Ranní počasí", read.name)
        assertEquals("light", read.icon)
        assertEquals("time_daily", read.events.single().type)
        assertEquals("07:15", read.events.single().params["time"])
        assertEquals("12345", read.events.single().params["days"])
        assertTrue(read.conditionsAny)
        assertEquals(ParamValue.Choice("wifi_name"), read.conditions.single().params["source"])
        assertEquals(
            listOf("try", "http_request", "catch", "toast", "end_try", "json_get", "set_variable", "script", "if", "notification", "end_if", "toast", "comment"),
            read.blocks.map { it.type },
        )
        assertTrue(Structure.analyze(read.blocks).valid)
        assertTrue(read.blocks[11].disabled)
        assertFalse(read.blocks[12].disabled)
        val url = read.blocks[1].params["url"] as ParamValue.Rich
        assertEquals(Segment.Var(VarRef.Secret("weather_key")), url.segments.last())
    }

    @Test
    fun importAcceptsAScriptFile() {
        val imported = AutomationTransfer.decode("# @name \"Ahoj\"\n# @triggers share\ntoast(\"Ahoj \" & input)")
        assertEquals("Ahoj", imported.single().name)
        assertTrue(imported.single().triggerShare)
        assertEquals("toast", imported.single().blocks.single().type)
    }
}
