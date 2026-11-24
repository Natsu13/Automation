package cz.natsu.automation.data

import android.content.Context

/**
 * When each automation last started, for the launch condition "Hodin od posledního
 * spuštění" (e.g. "at most once a day"). Kept apart from [RunHistory], which can be
 * cleared and only keeps the last hundred runs.
 */
object LastRun {
    /** The source of a launch condition that reads it. */
    const val HOURS_SINCE_RUN = "hours_since_run"

    private const val PREFS = "last_run"

    fun mark(context: Context, automationId: Long, time: Long = System.currentTimeMillis()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(automationId.toString(), time).apply()
    }

    fun get(context: Context, automationId: Long): Long? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(automationId.toString(), -1L).takeIf { it > 0 }

    /** Hours since the last start (with one decimal place); a big number when it never ran. */
    fun hoursSince(context: Context, automationId: Long?): String {
        val last = automationId?.let { get(context, it) } ?: return "100000"
        val hours = (System.currentTimeMillis() - last).coerceAtLeast(0) / 360_000L / 10.0
        return cz.natsu.automation.engine.formatNumber(hours)
    }

    fun forget(context: Context, automationId: Long) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(automationId.toString()).apply()
    }
}
