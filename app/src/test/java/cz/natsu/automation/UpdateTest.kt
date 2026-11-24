package cz.natsu.automation

import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.LastRun
import cz.natsu.automation.engine.CardBlocks
import cz.natsu.automation.engine.Structure
import cz.natsu.automation.engine.Versions
import cz.natsu.automation.ui.list.Examples
import cz.natsu.automation.ui.list.News
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test
    fun versions() {
        assertTrue(Versions.isNewer("v2.1", "2.0"))
        assertTrue(Versions.isNewer("2.10", "2.9"))
        assertFalse(Versions.isNewer("2.1", "2.1.0"))
        assertFalse(Versions.isNewer("v2.0", "2.1"))
        assertTrue(Versions.isNewer("2.1", "2.1-beta"))
        assertFalse(Versions.isNewer("", "2.0"))
        assertEquals(0, Versions.compare("v3", "3.0.0"))
    }

    @Test
    fun countdowns() {
        val c = cz.natsu.automation.data.Countdowns
        assertEquals(90_000L, c.parseDuration("1:30"))
        assertEquals(3_600_000L, c.parseDuration("1:00:00"))
        assertEquals(45_000L, c.parseDuration("45"))
        assertEquals(null, c.parseDuration("abc"))
        assertEquals("19:59", c.clock(1_199_000))
        assertEquals("1:05:03", c.clock(3_903_000))
        assertEquals("20 min", c.words(1_200_000))
        assertEquals("1 h 5 min", c.words(3_903_000))
        val now = 1_000_000_000_000L
        val at = c.parseTime("18:30", now)!!
        assertTrue(at > now && at - now <= 24 * 3600_000L)
        assertEquals(null, c.parseTime("25:00", now))
        val card = cz.natsu.automation.data.HomeCard(
            id = "x", title = "Pračka {zbyva}", text = "Bacha, zbývá jen {zbyva}!", countdownStart = now, countdownEnd = now + 3_600_000,
        )
        assertEquals("Pračka 1 h", card.titleAt(now))
        assertEquals("Bacha, zbývá jen 20 min!", card.textAt(now + 2_400_000))
        assertEquals(now + 2_700_000, card.countdownAt(0.75))
    }

    @Test
    fun updateExampleReadsIntoBlocks() {
        val built = Examples.all.first { it.name == News.UPDATE_EXAMPLE }.build()
        val types = built.blocks.map { it.type }
        assertTrue(Structure.analyze(built.blocks).valid)
        for (type in listOf("try", "http_request", "json_get", "app_version", "for_each", "download_file", CardBlocks.SHOW, CardBlocks.WAIT, "install_apk")) {
            assertTrue("missing $type in $types", type in types)
        }
        // Everything is a block: no line of the script fell back to a Skript step.
        assertFalse("script fallback in $types", "script" in types)
        val entity = built.triggers(cz.natsu.automation.data.AutomationEntity(name = "x"))
        assertEquals(listOf(EventType.ThisAppOpened.key), entity.events.map { it.type })
        val condition = entity.conditions.single()
        assertEquals(LastRun.HOURS_SINCE_RUN, (condition.params["source"] as cz.natsu.automation.data.ParamValue.Choice).value)
        // The version check is a simple condition, not an expression.
        val check = built.blocks.first { it.type == "if" }
        assertEquals("newer_version", (check.params["op"] as cz.natsu.automation.data.ParamValue.Choice).value)
    }
}
