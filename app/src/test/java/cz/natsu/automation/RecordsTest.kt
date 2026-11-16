package cz.natsu.automation

import cz.natsu.automation.engine.Records
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordsTest {
    @Test
    fun pairSplitsOnlyAtTypedEquals() {
        // "text=" typed, then a variable whose value contains "=" and a new line
        assertEquals("text" to "a=b\nc", Records.pair(listOf("text=" to true, "a=b\nc" to false), 0))
        assertEquals("cas" to "12:00", Records.pair(listOf(" cas = 12:00 " to true), 0))
    }

    @Test
    fun pairWithoutNameGetsOne() {
        assertEquals("hodnota2" to "jen text", Records.pair(listOf("jen text" to true), 1))
        assertEquals("hodnota1" to "x=y", Records.pair(listOf("x=y" to false), 0))
        assertNull(Records.pair(listOf("  " to true), 0))
        assertNull(Records.pair(emptyList(), 0))
    }

    @Test
    fun csvRowQuotesWhatNeedsIt() {
        assertEquals("a;\"b;c\";\"say \"\"hi\"\"\";\"two\nlines\"\r\n", Records.csvRow(listOf("a", "b;c", "say \"hi\"", "two\nlines"), ';'))
        assertEquals("a;b,\"c,d\"\r\n", Records.csvRow(listOf("a;b", "c,d"), ','))
    }

    @Test
    fun jsonListGrows() {
        val first = Records.addToJson("", listOf("app" to "Mail", "text" to "Ahoj"))
        val second = Records.addToJson("﻿" + first, listOf("app" to "Chat", "text" to "x"))
        val list = kotlinx.serialization.json.Json.parseToJsonElement(second) as kotlinx.serialization.json.JsonArray
        assertEquals(2, list.size)
        assertEquals("\"Chat\"", (list[1] as kotlinx.serialization.json.JsonObject)["app"].toString())
        assertEquals(listOf("app", "text"), (list[0] as kotlinx.serialization.json.JsonObject).keys.toList())
    }

    @Test(expected = cz.natsu.automation.engine.BlockException::class)
    fun jsonThatIsNotAListIsRefused() {
        Records.addToJson("{\"a\": 1}", listOf("a" to "b"))
    }
}
