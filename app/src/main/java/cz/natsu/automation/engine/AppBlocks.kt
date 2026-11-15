package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Apps
import cz.natsu.automation.data.ParamValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Other apps: opening one, and sending an intent to its activity, service or receiver. */
object AppBlocks {
    private fun choiceIs(key: String, vararg values: String): (Map<String, ParamValue>) -> Boolean = { map ->
        (map[key] as? ParamValue.Choice)?.value in values
    }

    /** Package of the app written as a package name or as the name under its icon. */
    fun findPackage(context: Context, app: String): String? {
        val wanted = app.trim()
        if (wanted.isEmpty()) return null
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(wanted) != null) return wanted
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        return (apps.firstOrNull { it.first.equals(wanted, ignoreCase = true) }
            ?: apps.firstOrNull { it.first.contains(wanted, ignoreCase = true) })?.second
    }

    /**
     * Extras written one per line as `name=value`. The type is guessed (true / false,
     * whole number, decimal number, text) or given: `name:int=5`, `name:long=5`,
     * `name:float=1.5`, `name:double=1.5`, `name:bool=true`, `name:string=5`.
     */
    fun putExtras(intent: Intent, text: String) {
        for ((rawKey, value) in JsonTools.pairs(text, charArrayOf('='))) {
            val key = rawKey.substringBeforeLast(':').trim()
            val type = if (':' in rawKey) rawKey.substringAfterLast(':').trim().lowercase() else ""
            fun bad(): Nothing = throw BlockException(tr("extra \"%s\": \"%s\" není %s", key, value, type))
            when (type) {
                "int" -> intent.putExtra(key, value.toIntOrNull() ?: bad())
                "long" -> intent.putExtra(key, value.toLongOrNull() ?: bad())
                "float" -> intent.putExtra(key, value.replace(',', '.').toFloatOrNull() ?: bad())
                "double" -> intent.putExtra(key, value.replace(',', '.').toDoubleOrNull() ?: bad())
                "bool", "boolean" -> intent.putExtra(key, value.lowercase().toBooleanStrictOrNull() ?: bad())
                "string", "text" -> intent.putExtra(key, value)
                "" -> when {
                    // No type: a name with a colon of its own (rare) is kept whole.
                    value == "true" || value == "false" -> intent.putExtra(rawKey, value == "true")
                    value.toIntOrNull() != null -> intent.putExtra(rawKey, value.toInt())
                    value.toLongOrNull() != null -> intent.putExtra(rawKey, value.toLong())
                    Regex("-?\\d+\\.\\d+").matches(value) -> intent.putExtra(rawKey, value.toDouble())
                    else -> intent.putExtra(rawKey, value)
                }
                // Not a known type, so the colon belongs to the name (e.g. "android.intent.extra:x").
                else -> intent.putExtra(rawKey, value)
            }
        }
    }

    val all: List<BlockDef> = listOf(
        BlockDef(
            type = "open_app",
            needsWindow = true,
            summary = "Otevřít aplikaci {app}",
            title = "Otevřít aplikaci",
            description = "Spustí nainstalovanou aplikaci – stejně jako klepnutí na její ikonu. " +
                    "Stačí název (Spotify) nebo balíček (com.spotify.music).",
            category = BlockCategory.Apps,
            icon = Icons.Rounded.Apps,
            params = listOf(
                ParamDef.Text("app", "Aplikace", placeholder = "název nebo balíček", appPicker = true),
            ),
        ) { block ->
            val app = text(block, "app")
            if (app.isBlank()) throw BlockException(tr("aplikace není vybraná"))
            val packageName = findPackage(context, app) ?: throw BlockException(tr("aplikace \"%s\" není nainstalovaná", app.trim()))
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw BlockException(tr("aplikace \"%s\" nejde spustit", app.trim()))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                withContext(Dispatchers.Main) { context.startActivity(intent) }
            } catch (e: Exception) {
                throw BlockException(tr("aplikaci nejde otevřít (%s)", e.message))
            }
            BlockResult.None
        },

        BlockDef(
            type = "send_intent",
            needsWindowWhen = { block -> (block.params["target"] as? ParamValue.Choice)?.value.let { it == null || it == "activity" } },
            summary = "{target} {package} {action}",
            title = "Poslat intent / spustit službu",
            description = "Pro pokročilé: otevře konkrétní obrazovku jiné aplikace, spustí její službu nebo jí pošle " +
                    "zprávu (broadcast) – s akcí, daty a parametry (extras). Tak se ovládají aplikace, které to " +
                    "podporují (Tasker pluginy, přehrávače, chytrá domácnost…). Nápověda pod ?.",
            category = BlockCategory.Apps,
            icon = Icons.AutoMirrored.Rounded.Send,
            params = listOf(
                ParamDef.Choice(
                    "target", "Co udělat", listOf(
                        ChoiceOption("activity", "Otevřít obrazovku (activity)", "Otevřít obrazovku"),
                        ChoiceOption("service", "Spustit službu (service)", "Spustit službu"),
                        ChoiceOption("foreground_service", "Spustit službu na popředí", "Spustit službu na popředí"),
                        ChoiceOption("broadcast", "Poslat zprávu (broadcast)", "Poslat broadcast"),
                    )
                ),
                ParamDef.Text("package", "Aplikace (balíček)", placeholder = "com.example.app", appPicker = true),
                ParamDef.Text(
                    "class", "Třída (nepovinné)", placeholder = "com.example.app.SyncService nebo .SyncService",
                    componentPicker = true,
                ),
                ParamDef.Text(
                    "action", "Akce", placeholder = "např. android.intent.action.VIEW",
                    suggestions = listOf(
                        "android.intent.action.VIEW",
                        "android.intent.action.SEND",
                        "android.intent.action.MAIN",
                        "android.intent.action.SEARCH",
                        "android.intent.action.WEB_SEARCH",
                        "android.intent.action.DIAL",
                        "android.intent.action.SENDTO",
                        "android.intent.action.EDIT",
                        "android.media.action.MEDIA_PLAY_FROM_SEARCH",
                    ),
                ),
                ParamDef.Text("data", "Data (adresa)", placeholder = "např. https://… nebo spotify:track:…"),
                ParamDef.Text("mime", "Typ dat (MIME)", placeholder = "např. text/plain").apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Text("extras", "Parametry – extras (název=hodnota na řádek)", placeholder = "query=ahoj\ncount:int=5"),
            ),
        ) { block ->
            val target = choice(block, "target")
            val packageName = text(block, "package").trim().let { written ->
                // A name like "Spotify" works too; an unknown text is taken as a package name.
                if (written.isEmpty() || '.' in written) written else findPackage(context, written) ?: written
            }
            val className = text(block, "class").trim().let { if (it.startsWith(".")) packageName + it else it }
            val action = text(block, "action").trim()
            val data = text(block, "data").trim()
            val mime = text(block, "mime").trim()

            val intent = Intent()
            if (action.isNotEmpty()) intent.action = action
            when {
                data.isNotEmpty() && mime.isNotEmpty() -> intent.setDataAndType(Uri.parse(data), mime)
                data.isNotEmpty() -> intent.data = Uri.parse(data)
                mime.isNotEmpty() -> intent.type = mime
            }
            when {
                className.isNotEmpty() -> {
                    if (packageName.isEmpty()) throw BlockException(tr("ke třídě je potřeba i balíček aplikace"))
                    intent.component = ComponentName(packageName, className)
                }
                packageName.isNotEmpty() -> intent.setPackage(packageName)
            }
            putExtras(intent, text(block, "extras"))
            if (action.isEmpty() && className.isEmpty()) throw BlockException(tr("vyplň akci nebo třídu"))

            try {
                when (target) {
                    "service", "foreground_service" -> {
                        if (packageName.isEmpty()) throw BlockException(tr("u služby je potřeba balíček aplikace"))
                        val started = if (target == "foreground_service" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        if (started == null) {
                            throw BlockException(tr("služba neexistuje, nebo ji aplikace nedovoluje spouštět zvenku (exported)"))
                        }
                    }
                    "broadcast" -> context.sendBroadcast(intent)
                    else -> {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        withContext(Dispatchers.Main) { context.startActivity(intent) }
                    }
                }
            } catch (e: BlockException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                throw BlockException(tr("žádná aplikace tenhle intent neumí otevřít"))
            } catch (e: SecurityException) {
                throw BlockException(tr("aplikace to nedovoluje (není exported nebo chce oprávnění)"))
            } catch (e: IllegalStateException) {
                // Android refuses to start a background service of an app that is not running.
                throw BlockException(tr("Android službu nepustil – zkus \"Spustit službu na popředí\" (%s)", e.message))
            }
            BlockResult.None
        },
    )
}
