package cz.natsu.automation

import cz.natsu.automation.engine.Locator
import cz.natsu.automation.engine.Sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/** Sunrise / sunset times and the coordinates the place events are set up with. */
class SunTest {
    private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }.timeInMillis

    private fun assertNear(expected: Long, actual: Long?, minutes: Int = 6) {
        assertTrue("expected about $expected but was $actual", actual != null && abs(actual - expected) <= minutes * 60_000L)
    }

    @Test
    fun pragueOnTheLongestDay() {
        val noon = utc(2024, 6, 21, 10, 0)
        // 4:53 and 21:16 of Central European Summer Time.
        assertNear(utc(2024, 6, 21, 2, 53), Sun.time(50.0755, 14.4378, noon, rise = true))
        assertNear(utc(2024, 6, 21, 19, 16), Sun.time(50.0755, 14.4378, noon, rise = false))
    }

    @Test
    fun pragueInWinterAndFarEast() {
        // 7:58 and 16:02 of Central European Time.
        val noon = utc(2024, 12, 21, 11, 0)
        assertNear(utc(2024, 12, 21, 6, 58), Sun.time(50.0755, 14.4378, noon, rise = true))
        assertNear(utc(2024, 12, 21, 15, 2), Sun.time(50.0755, 14.4378, noon, rise = false))
        // Tokyo, 20 March 2024: 5:45 and 17:53 of Japan time (UTC+9) – the sunrise is on the previous UTC day.
        val tokyoNoon = utc(2024, 3, 20, 3, 0)
        assertNear(utc(2024, 3, 19, 20, 45), Sun.time(35.6762, 139.6503, tokyoNoon, rise = true))
        assertNear(utc(2024, 3, 20, 8, 53), Sun.time(35.6762, 139.6503, tokyoNoon, rise = false))
    }

    @Test
    fun nextIsAlwaysAhead() {
        val now = utc(2024, 6, 21, 12, 0)
        val sunset = Sun.next(50.0755, 14.4378, rise = false, now = now)!!
        val sunrise = Sun.next(50.0755, 14.4378, rise = true, now = now)!!
        assertTrue(sunset > now && sunset - now < 12 * 3600_000L)
        // Today's sunrise is over: the next one is tomorrow morning.
        assertTrue(sunrise > sunset && sunrise - now < 24 * 3600_000L)
        // Midnight sun far in the north: nothing to plan.
        assertNull(Sun.next(78.22, 15.65, rise = true, now = now))
    }

    @Test
    fun coordinates() {
        assertEquals(50.08 to 14.42, Locator.parse("50.08,14.42"))
        assertEquals(50.08 to 14.42, Locator.parse(" 50.08, 14.42 "))
        assertEquals(-33.9 to 151.2, Locator.parse("{\"lat\":-33.9,\"lon\":151.2,\"accuracy\":12}"))
        assertNull(Locator.parse("Praha"))
        assertNull(Locator.parse("95,14"))
        assertEquals("50.075500,14.437800", Locator.coords(50.0755, 14.4378))
    }
}
