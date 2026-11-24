package cz.natsu.automation.ui.list

import android.content.Context
import cz.natsu.automation.engine.Versions

/**
 * News at the top of the home screen: what is new in the app. Each one can be closed
 * for good. After an update, a news item says which version the app has now.
 */
object News {
    /** [example] = name of the example the button adds; [button] = its label. */
    class Item(
        val id: String,
        val title: String,
        val text: String,
        val icon: String,
        val button: String? = null,
        val example: String? = null,
        /** Already translated (the version text is built at run time). */
        val translated: Boolean = false,
    )

    private const val PREFS = "news"
    private const val DISMISSED = "dismissed"
    private const val LAST_VERSION = "last_version"
    private const val UPDATED_TO = "updated_to"

    const val UPDATE_EXAMPLE = "Aktualizace z GitHubu"

    private val all = listOf(
        Item(
            id = "auto_update",
            title = "Nově: automatická aktualizace",
            text = "Ukázková automatizace jednou denně po otevření aplikace zkontroluje GitHub, stáhne novou verzi " +
                    "s průběhem na kartě tady nahoře a nabídne ji k instalaci.",
            icon = "download",
            button = "Přidat automatizaci",
            example = UPDATE_EXAMPLE,
        ),
        Item(
            id = "home_cards",
            title = "Karty a připnuté automatizace",
            text = "Automatizace teď můžou na úvod přidat kartu s průběhem a tlačítky (blok Karta na úvodu). " +
                    "Automatizaci připneš nahoru v jejím menu ⋮.",
            icon = "pin",
        ),
    )

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Called when the app opens: remembers the version, and notes an update. */
    fun noteVersion(context: Context) {
        val current = currentVersion(context) ?: return
        val prefs = prefs(context)
        val last = prefs.getString(LAST_VERSION, null)
        val edit = prefs.edit().putString(LAST_VERSION, current)
        if (last != null && Versions.isNewer(current, last)) edit.putString(UPDATED_TO, current)
        edit.apply()
    }

    private fun currentVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }

    /** The news to show, newest first. */
    fun visible(context: Context): List<Item> {
        val prefs = prefs(context)
        val dismissed = prefs.getStringSet(DISMISSED, emptySet()).orEmpty()
        val updated = prefs.getString(UPDATED_TO, null)?.let { version ->
            Item(
                id = "updated_$version",
                title = cz.natsu.automation.i18n.tr("Aktualizováno na verzi %s", version),
                text = cz.natsu.automation.i18n.tr("Aplikace se aktualizovala. Přehled změn najdeš u vydání na GitHubu."),
                icon = "check",
                translated = true,
            )
        }
        return (listOfNotNull(updated) + all).filter { it.id !in dismissed }
    }

    fun dismiss(context: Context, id: String) {
        val prefs = prefs(context)
        val dismissed = prefs.getStringSet(DISMISSED, emptySet()).orEmpty() + id
        prefs.edit().putStringSet(DISMISSED, dismissed).apply()
    }
}
