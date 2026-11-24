package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Numbers
import androidx.core.content.IntentCompat
import cz.natsu.automation.data.HomeCards
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Versions of apps, downloading a file with progress and installing an APK. */
object InstallBlocks {
    val all: List<BlockDef> = listOf(
        BlockDef(
            type = "app_version",
            summary = "Verze aplikace {package}",
            title = "Verze aplikace",
            description = "Vrátí verzi nainstalované aplikace – této, nebo jiné podle balíčku. " +
                    "Porovnat ji jde podmínkou „je novější verze než“.",
            category = BlockCategory.Apps,
            icon = Icons.Rounded.Numbers,
            params = listOf(
                ParamDef.Text("package", "Balíček aplikace", placeholder = "tato aplikace", appPicker = true).apply {
                    hiddenInSummaryWhen = { (it["package"] as? cz.natsu.automation.data.ParamValue.Rich)?.segments.isNullOrEmpty() }
                },
                ParamDef.Choice(
                    "what", "Co vrátit", listOf(
                        ChoiceOption("name", "Název verze (např. 2.1)", "název"),
                        ChoiceOption("code", "Číslo sestavení (versionCode)", "číslo sestavení"),
                    )
                ),
            ),
            outputName = "Verze",
        ) { block ->
            val pkg = text(block, "package").trim().ifEmpty { context.packageName }
            val info = packageInfo(context, pkg) ?: throw BlockException(tr("aplikace %s není nainstalovaná", pkg))
            BlockResult.Output(
                if (choice(block, "what") == "code") versionCode(info).toString() else info.versionName.orEmpty()
            )
        },

        BlockDef(
            type = "download_file",
            summary = "Stáhnout {url}{show_card}",
            title = "Stáhnout soubor",
            description = "Stáhne soubor z adresy do aplikace a vrátí ho (pro Nainstalovat APK, Sdílet soubor, " +
                    "Uložit soubor…). Průběh může ukazovat na kartě na úvodu; Zastavit stahování přeruší.",
            category = BlockCategory.Share,
            icon = Icons.Rounded.Download,
            params = listOf(
                ParamDef.Text("url", "Adresa", placeholder = "https://…"),
                ParamDef.Text("name", "Název souboru", placeholder = "podle adresy").apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text("headers", "Hlavičky (klíč: hodnota na řádek)", placeholder = "Authorization: Bearer …").apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Toggle("show_card", "Ukázat průběh na kartě na úvodu", summaryWhenOn = " (průběh na kartě)"),
                ParamDef.Text("card", "Název karty (id)", default = "karta").apply {
                    hiddenWhen = { (it["show_card"] as? cz.natsu.automation.data.ParamValue.Bool)?.value != true }
                    hiddenInSummaryWhen = { true }
                },
            ),
            outputName = "Stažený soubor",
        ) { block ->
            val url = text(block, "url").trim()
            if (url.isEmpty()) throw BlockException(tr("adresa je prázdná"))
            val file = download(
                context, url, text(block, "name").trim(),
                JsonTools.pairs(text(block, "headers"), charArrayOf(':')),
                if (bool(block, "show_card")) text(block, "card").trim() else "",
            )
            BlockResult.Output(Uri.fromFile(file).toString())
        },

        BlockDef(
            type = "install_apk",
            summary = "Nainstalovat {file}",
            title = "Nainstalovat APK",
            description = "Nabídne instalaci aplikace ze souboru APK (třeba staženého). Android se vždy zeptá, " +
                    "jestli instalovat. Aktualizace této aplikace ji po instalaci zavře a otevře novou verzi.",
            category = BlockCategory.Apps,
            icon = Icons.Rounded.InstallMobile,
            requirement = Requirement.InstallPackages,
            params = listOf(
                ParamDef.Text("file", "Soubor APK", placeholder = "výstup bloku Stáhnout soubor"),
            ),
            outputName = "Nainstalovaný balíček",
        ) { block ->
            if (!context.packageManager.canRequestPackageInstalls()) askToAllowInstalls(context)
            BlockResult.Output(install(context, text(block, "file")))
        },
    )

    /**
     * Opens the system switch "Install unknown apps" for this app and waits (up to two minutes)
     * until the user turns it on. Only works while the app is on screen.
     */
    private suspend fun askToAllowInstalls(context: Context) {
        val denied = BlockException(tr("aplikace nesmí instalovat – povol Instalaci neznámých aplikací"), Requirement.InstallPackages)
        if (!cz.natsu.automation.AutomationApplication.visible) throw denied
        withContext(Dispatchers.Main) {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        repeat(240) {
            kotlinx.coroutines.delay(500)
            if (context.packageManager.canRequestPackageInstalls()) return
        }
        throw denied
    }

    // ---------- versions ----------

    private fun packageInfo(context: Context, pkg: String, flags: Int = 0): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(pkg, flags)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    // ---------- download ----------

    private fun megabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes / 1_048_576.0)

    /** Downloads [address] into the cache; [card] = a card on the home screen that shows the progress. */
    suspend fun download(
        context: Context,
        address: String,
        name: String,
        headers: List<Pair<String, String>>,
        card: String,
    ): File = withContext(Dispatchers.IO) {
        val url = try {
            URL(if (address.contains("://")) address else "https://$address")
        } catch (e: Exception) {
            throw BlockException(tr("neplatná adresa"))
        }
        if (url.protocol != "https" && url.protocol != "http") throw BlockException(tr("neplatná adresa"))
        if (HttpBlocks.isLocalHost(url.host) && !LocalNetwork.granted(context)) {
            throw BlockException(tr("Android nepustí aplikaci do místní sítě – povol přístup k místní síti"), Requirement.LocalNetwork)
        }
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
        val fileName = name.ifEmpty { Uri.decode(url.path.substringAfterLast('/')) }
            .replace(Regex("[^\\w.\\-]+"), "_").take(80).ifBlank { "soubor" }
        val target = File(File(dir, UUID.randomUUID().toString().take(8)).apply { mkdirs() }, fileName)
        val showProgress = card.isNotEmpty() && HomeCards.get(card) != null

        val conn = url.openConnection() as HttpURLConnection
        // Stopping the run (a card button, a time limit) closes the connection, so the read below ends at once.
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val watcher = job?.invokeOnCompletion { conn.disconnect() }
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Automation (Android)")
            conn.setRequestProperty("Accept", "application/octet-stream, */*")
            for ((key, value) in headers) conn.setRequestProperty(key, value)
            val code = conn.responseCode
            if (code >= 400) throw BlockException(tr("soubor nejde stáhnout (chyba %s)", code))
            val total = conn.contentLengthLong
            var done = 0L
            var shownAt = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (showProgress && now - shownAt > 250) {
                            shownAt = now
                            HomeCards.progress(
                                card,
                                if (total > 0) done.toFloat() / total else -1f,
                                if (total > 0) "${megabytes(done)} / ${megabytes(total)} MB" else "${megabytes(done)} MB",
                            )
                        }
                    }
                }
            }
            if (total > 0 && done < total) throw BlockException(tr("stahování se přerušilo"))
            if (showProgress) HomeCards.progress(card, 1f, "${megabytes(done)} MB")
            target
        } catch (e: Exception) {
            target.delete()
            ensureActive()
            throw when (e) {
                is BlockException -> e
                is java.net.UnknownHostException -> BlockException(tr("bez připojení nebo neznámý server"))
                is java.net.SocketTimeoutException -> BlockException(tr("server neodpovídá"))
                else -> BlockException(tr("stahování selhalo (%s)", e.message ?: e.javaClass.simpleName))
            }
        } finally {
            watcher?.dispose()
            conn.disconnect()
        }
    }

    // ---------- install ----------

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String?>>>()

    /** Installs the APK [source]; returns the package name once installed. */
    private suspend fun install(context: Context, source: String): String {
        val apk = FileTools.copyToCache(context, source, "apk").file
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val archive = (if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(apk.path, flags)
        }) ?: run {
            apk.delete()
            throw BlockException(tr("soubor není platné APK"))
        }
        val pkg = archive.packageName
        val installed = packageInfo(context, pkg, flags)
        if (installed != null) {
            if (versionCode(archive) < versionCode(installed)) {
                apk.delete()
                throw BlockException(tr("APK je starší verze (%s) než nainstalovaná (%s)", archive.versionName, installed.versionName))
            }
            val ours = signatures(installed)
            val theirs = signatures(archive)
            if (ours.isNotEmpty() && theirs.isNotEmpty() && ours.intersect(theirs).isEmpty()) {
                apk.delete()
                throw BlockException(
                    tr("APK je podepsané jiným klíčem než nainstalovaná aplikace – Android ho přes ni nenainstaluje")
                )
            }
        }

        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(pkg)
            // Updating itself (or an app it installed before) needs no extra question on Android 12+.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = withContext(Dispatchers.IO) {
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    session.openWrite("app.apk", 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
            } catch (e: Exception) {
                installer.abandonSession(id)
                throw BlockException(tr("APK nejde připravit k instalaci (%s)", e.message))
            } finally {
                apk.delete()
            }
            id
        }
        val result = CompletableDeferred<Pair<Int, String?>>()
        waiting[sessionId] = result
        try {
            val intent = Intent(context, InstallResultReceiver::class.java).setAction("install.$sessionId")
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            )
            installer.openSession(sessionId).use { it.commit(pending.intentSender) }
            val (status, message) = result.await()
            return when (status) {
                PackageInstaller.STATUS_SUCCESS -> pkg
                PackageInstaller.STATUS_FAILURE_ABORTED -> throw RunCancelledException()
                PackageInstaller.STATUS_FAILURE_CONFLICT ->
                    throw BlockException(tr("instalace se nepovedla: aplikace je podepsaná jinak nebo jde o starší verzi"))
                PackageInstaller.STATUS_FAILURE_STORAGE -> throw BlockException(tr("instalace se nepovedla: málo místa"))
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> throw BlockException(tr("instalace se nepovedla: APK není pro tento telefon"))
                else -> throw BlockException(tr("instalace se nepovedla (%s)", message ?: status))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        } finally {
            waiting.remove(sessionId)
        }
    }

    private fun signatures(info: PackageInfo): Set<String> {
        if (Build.VERSION.SDK_INT < 28) return emptySet()
        val signing = info.signingInfo ?: return emptySet()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signatures.orEmpty().map { it.toCharsString() }.toSet()
    }

    /** The answer of the system installer for a session. */
    internal fun onResult(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android asks the user first: open its question.
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android lets only an app on screen open a window; otherwise the question waits in a notification.
                if (cz.natsu.automation.AutomationApplication.visible) {
                    try {
                        context.startActivity(confirm)
                        return
                    } catch (e: Exception) {
                        // Falls back to the notification below.
                    }
                }
                askByNotification(context, sessionId, confirm)
                return
            }
        }
        waiting[sessionId]?.complete(status to intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

private fun askByNotification(context: Context, sessionId: Int, confirm: Intent) {
    NotificationBlock.ensureChannels(context)
    val open = PendingIntent.getActivity(
        context, sessionId, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = androidx.core.app.NotificationCompat.Builder(context, NotificationBlock.CHANNEL_LOUD)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(tr("Aktualizace je připravená"))
        .setContentText(tr("Klepni a nainstaluj ji."))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    try {
        androidx.core.app.NotificationManagerCompat.from(context).notify("install", sessionId, notification)
    } catch (e: SecurityException) {
        // Notifications are not allowed; the run still waits and can be stopped.
    }
}

/** Receives the progress of an installation started by the "Nainstalovat APK" block. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InstallBlocks.onResult(context, intent)
    }
}
