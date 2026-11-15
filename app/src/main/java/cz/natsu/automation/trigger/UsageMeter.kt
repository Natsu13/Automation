package cz.natsu.automation.trigger

import cz.natsu.automation.i18n.tr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cheap measurement of how much the app costs while it runs in the background.
 *
 * Android keeps CPU time per app (all its processes, so the logcat reader is
 * included). The app takes a snapshot only at moments it is awake anyway – when it
 * is opened or closed, when the screen turns on / off and after an event – and
 * adds the difference to today's "background" or "in the app" counter. Nothing is
 * polled, so the measuring itself costs practically nothing.
 */
object UsageMeter {
    private const val TAG = "UsageMeter"
    private const val PREFS = "usage"
    private const val KEY_LAST_CPU = "last_cpu_ms"
    private const val KEY_UI_VISIBLE = "ui_visible"
    private const val KEY_PRECISE = "precise"

    /** Background CPU per day above which the app shows a warning in the list. */
    val WARN_CPU_MS = TimeUnit.MINUTES.toMillis(3)

    data class Day(val backgroundCpuMs: Long, val foregroundCpuMs: Long, val events: Int, val runs: Int)

    data class Summary(val today: Day, val yesterday: Day) {
        /** The busier of today / yesterday is over the limit. */
        val high: Boolean get() = maxOf(today.backgroundCpuMs, yesterday.backgroundCpuMs) > WARN_CPU_MS
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayKey(offsetDays: Int = 0): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(offsetDays.toLong())))

    /** CPU time of all processes of this app since Android started counting (ms). */
    private fun cpuMs(context: Context): Long? {
        try {
            val stats = context.getSystemService(SystemHealthManager::class.java)?.takeMyUidSnapshot()
            if (stats != null) {
                val user = if (stats.hasMeasurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS))
                    stats.getMeasurement(UidHealthStats.MEASUREMENT_USER_CPU_TIME_MS) else 0L
                val system = if (stats.hasMeasurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS))
                    stats.getMeasurement(UidHealthStats.MEASUREMENT_SYSTEM_CPU_TIME_MS) else 0L
                if (user + system > 0) return user + system
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health stats unavailable", e)
        }
        // Fallback: this process only.
        return try {
            val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
            val ticks = fields[11].toLong() + fields[12].toLong()
            ticks * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Adds the CPU time used since the last sample to today's counter. The time is
     * counted as "in the app" when the app was on screen, else as background.
     * [uiVisible] (when given) is the state from now on.
     */
    @Synchronized
    fun sample(context: Context, uiVisible: Boolean? = null) {
        val now = cpuMs(context) ?: return
        val prefs = prefs(context)
        val last = prefs.getLong(KEY_LAST_CPU, -1L)
        // Counters restart after a reboot / full charge; then the whole value is new.
        val delta = when {
            last < 0 -> 0L
            now >= last -> now - last
            else -> now
        }
        val wasVisible = prefs.getBoolean(KEY_UI_VISIBLE, false)
        val bucket = (if (wasVisible) "fg_" else "bg_") + dayKey()
        prefs.edit().apply {
            putLong(KEY_LAST_CPU, now)
            putLong(bucket, prefs.getLong(bucket, 0L) + delta)
            if (uiVisible != null) putBoolean(KEY_UI_VISIBLE, uiVisible)
            cleanup(prefs.all.keys)
        }.apply()
    }

    private fun android.content.SharedPreferences.Editor.cleanup(keys: Set<String>) {
        val keep = (0..2).map { dayKey(it) }.toSet()
        for (key in keys) {
            val day = key.substringAfter('_', "")
            if (day.length == 8 && day.all(Char::isDigit) && day !in keep) remove(key)
        }
    }

    @Synchronized
    fun countEvent(context: Context) = increment(context, "events_")

    @Synchronized
    fun countRun(context: Context) = increment(context, "runs_")

    private fun increment(context: Context, prefix: String) {
        val prefs = prefs(context)
        val key = prefix + dayKey()
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    fun summary(context: Context): Summary {
        val prefs = prefs(context)
        fun day(offset: Int): Day {
            val key = dayKey(offset)
            return Day(
                backgroundCpuMs = prefs.getLong("bg_$key", 0L),
                foregroundCpuMs = prefs.getLong("fg_$key", 0L),
                events = prefs.getInt("events_$key", 0),
                runs = prefs.getInt("runs_$key", 0),
            )
        }
        return Summary(day(0), day(1))
    }

    fun formatCpu(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 1 -> tr("méně než 1 s")
            seconds < 60 -> "$seconds s"
            seconds % 60 == 0L -> "${seconds / 60} min"
            else -> "${seconds / 60} min ${seconds % 60} s"
        }
    }

    // ---------- precise measurement (Android's own battery statistics) ----------

    fun isPreciseEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PRECISE, false)

    fun setPreciseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRECISE, enabled).apply()
    }

    /** DUMP has to be granted over ADB; without it Android won't give out its battery statistics. */
    fun hasDump(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    /** Battery statistics are also guarded by "usage access" (an app op, also settable over ADB). */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED ||
                (mode == android.app.AppOpsManager.MODE_DEFAULT &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED)
    }

    fun hasPreciseAccess(context: Context): Boolean = hasDump(context) && hasUsageAccess(context)

    /**
     * Battery use of this app since the last full charge, as computed by Android
     * (mAh). [backgroundMah] = what the app used while not on screen.
     */
    data class Precise(
        val totalMah: Double,
        val backgroundMah: Double,
        val screenMah: Double,
        val capacityMah: Double?,
    ) {
        val backgroundPercent: Double? get() = capacityMah?.takeIf { it > 0 }?.let { backgroundMah / it * 100 }
    }

    sealed interface PreciseResult {
        data class Ok(val value: Precise) : PreciseResult
        data class Failed(val message: String) : PreciseResult
    }

    /** Runs `dumpsys batterystats` for this app. Takes a moment; call off the main thread. */
    suspend fun readPrecise(context: Context): PreciseResult = withContext(Dispatchers.IO) {
        if (!hasPreciseAccess(context)) return@withContext PreciseResult.Failed(tr("Chybí oprávnění z ADB."))
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("dumpsys", "batterystats", "--charged", context.packageName)
            )
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            parse(output, Process.myUid())
                ?.let { PreciseResult.Ok(it) }
                ?: PreciseResult.Failed(
                    output.lineSequence().firstOrNull { it.contains("Permission Denial") }
                        ?: tr("Android statistiku nevrátil.")
                )
        } catch (e: Exception) {
            PreciseResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Parses the "UID u0a123: 0.17 fg: … fgs: … ( screen=… cpu=… )" line of batterystats. */
    internal fun parse(output: String, uid: Int): Precise? {
        val appId = uid % 100_000 - Process.FIRST_APPLICATION_UID
        val user = uid / 100_000
        val name = "u${user}a$appId"
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("UID $name:") } ?: return null
        val total = Regex("""^UID \S+: ([\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val head = line.substringBefore('(')
        fun state(name: String) = Regex("""\b$name: ([\d.]+)""").find(head)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val background = state("fgs") + state("bg") + state("cached")
        val screen = Regex("""screen=([\d.]+)""").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val capacity = Regex("""Capacity: ([\d.]+)""").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
        return Precise(total, background, screen, capacity)
    }
}
