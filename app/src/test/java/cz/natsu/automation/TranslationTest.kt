package cz.natsu.automation

import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.ParamDef
import cz.natsu.automation.i18n.English
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text a block shows in the editor has an English version. */
class TranslationTest {
    /** Texts that read the same in both languages: symbols, code, examples, HTTP words, bare {placeholders}. */
    private val neutral = setOf(
        "+", ",", ";", "×", "÷", "−", "-90°", "90°", "180°", "h", "s", "min", "mod", "tab", "regex",
        "maximum", "minimum", "Base64", "JSON", "JPG", "PNG", "WEBP", "Text", "text", "Text {text}", "Video", "video",
        "e-mail", "GET", "POST", "PUT", "PATCH", "DELETE", "Content-Type", "Authorization: Bearer …",
        "https://…", "https://api.example.com/items", "com.example.app", "50.08,14.42", "name=Jan\nphoto=@content://…", "if (location.pathname == '/hotovo') done(document.title)",
    )

    /** Only {placeholders} and punctuation: nothing to translate. */
    private fun onlyPlaceholders(text: String) = text.replace(Regex("\\{\\w+\\}"), "").none { it.isLetter() }

    @Test
    fun everyBlockTextIsTranslated() {
        val missing = sortedSetOf<String>()
        fun check(text: String) {
            if (text.isBlank() || text in neutral || onlyPlaceholders(text)) return
            if (text !in English.texts) missing += text
        }
        for (def in Blocks.all) {
            check(def.title)
            check(def.description)
            check(def.summary)
            def.outputName?.let(::check)
            for (param in def.params) {
                check(param.label)
                when (param) {
                    is ParamDef.Text -> check(param.placeholder)
                    is ParamDef.Choice -> param.options.forEach { check(it.label); check(it.short) }
                    else -> Unit
                }
            }
        }
        assertTrue("Missing English texts (${missing.size}):\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
