package cz.natsu.automation.engine

import cz.natsu.automation.data.EventType
import cz.natsu.automation.i18n.English

/**
 * Values the app itself produces as words – how an automation was started ("Sdílení" /
 * "Sharing"), the name of an event, "zapnuto" / "on" – exist in both languages of the app.
 *
 * A comparison accepts either, so an automation written with the Czech word keeps
 * working when the app is switched to English, and the other way round.
 */
object Equivalents {
    private val pairs: List<Pair<String, String>> by lazy {
        (RunTrigger.entries.map { it.label } + EventType.entries.map { it.title } + listOf("zapnuto", "vypnuto"))
            .mapNotNull { czech -> English.texts[czech]?.let { czech to it } }
    }
    private val toEnglish by lazy { pairs.associate { it.first.lowercase() to it.second } }
    private val toCzech by lazy { pairs.associate { it.second.lowercase() to it.first } }

    /** [text] and, when it is one of those words, the same word in the other language. */
    fun variants(text: String): List<String> {
        val key = text.trim().lowercase()
        val other = toEnglish[key] ?: toCzech[key] ?: return listOf(text)
        return listOf(text, other)
    }
}
