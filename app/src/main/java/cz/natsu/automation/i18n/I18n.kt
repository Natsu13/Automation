package cz.natsu.automation.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class Language(val code: String, val title: String) {
    System("system", "By the phone's language / Podle telefonu"),
    English("en", "English"),
    Czech("cs", "Čeština"),
}

/**
 * Language of the app. The texts in the code are Czech; [tr] looks up their English
 * version in [English] when English is on. A text without a translation stays Czech,
 * so nothing ever disappears.
 */
object I18n {
    private const val PREFS = "settings"
    private const val KEY = "language"

    /** Compose state: screens redraw when the language changes. */
    var language by mutableStateOf(Language.System)
        private set

    val english: Boolean
        get() = when (language) {
            Language.English -> true
            Language.Czech -> false
            Language.System -> Locale.getDefault().language !in setOf("cs", "sk")
        }

    fun load(context: Context) {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        // Unset = the phone's language; English when the app does not have that language.
        language = Language.entries.firstOrNull { it.code == code } ?: Language.System
    }

    fun set(context: Context, value: Language) {
        language = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.code).apply()
    }

    /** For tests and previews. */
    fun force(value: Language) {
        language = value
    }
}

/** [czech] in the language of the app. */
fun tr(czech: String): String = if (!I18n.english) czech else English.texts[czech] ?: czech

/** [czech] with `%s` placeholders filled by [args], in the language of the app. */
fun tr(czech: String, vararg args: Any?): String = String.format(tr(czech), *args)
