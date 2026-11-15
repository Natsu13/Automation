package cz.natsu.automation

import cz.natsu.automation.data.Block
import cz.natsu.automation.data.BlockJson
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading stored blocks must never silently turn an automation into an empty one. */
class BlockJsonTest {
    private val blocks = listOf(
        Block(
            id = "a", type = "text",
            params = mapOf("text" to ParamValue.Rich(listOf(Segment.Text("ahoj "), Segment.Var(VarRef.Input)))),
        ),
        Block(id = "b", type = "change_case", params = mapOf("mode" to ParamValue.Choice("upper")), disabled = true),
    )

    @Test
    fun roundTrip() {
        val decoded = BlockJson.decodeChecked(BlockJson.encode(blocks))
        assertFalse(decoded.damaged)
        assertEquals(blocks, decoded.blocks)
    }

    @Test
    fun unknownPartsAreLeftOutButTheRestStays() {
        // As if saved by a newer version: an unknown variable and an unknown kind of value.
        val text = BlockJson.encode(blocks)
            .replace("\"type\":\"input\"", "\"type\":\"future_variable\"")
            .replace("\"type\":\"choice\"", "\"type\":\"future_value\"")
        val decoded = BlockJson.decodeChecked(text)

        assertFalse(decoded.unreadable)
        assertEquals(2, decoded.lost)
        assertEquals(listOf("a", "b"), decoded.blocks.map { it.id })
        assertEquals(ParamValue.Rich(listOf(Segment.Text("ahoj "))), decoded.blocks[0].params["text"])
        assertTrue(decoded.blocks[1].params.isEmpty())
        assertTrue(decoded.blocks[1].disabled)
    }

    @Test
    fun garbageIsReportedInsteadOfAnEmptyList() {
        for (text in listOf("", "not json", "{}", "[1, 2")) {
            val decoded = BlockJson.decodeChecked(text)
            assertTrue("\"$text\" should be unreadable", decoded.unreadable)
            assertTrue(decoded.blocks.isEmpty())
        }
        assertFalse(BlockJson.decodeChecked("[]").damaged)
    }
}
