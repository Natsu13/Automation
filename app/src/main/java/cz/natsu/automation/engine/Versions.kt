package cz.natsu.automation.engine

/**
 * Comparing version names like "2.1", "v2.10.3" or "2.1-beta": the numbers are compared
 * one by one as numbers (2.10 is newer than 2.9), missing ones count as 0 (2.1 = 2.1.0).
 */
object Versions {
    private fun parts(version: String): List<Long> =
        Regex("\\d+").findAll(version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+'))
            .map { it.value.take(18).toLong() }.toList()

    /** Negative when [a] is older than [b], 0 when the same, positive when newer. */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val diff = (x.getOrNull(i) ?: 0L).compareTo(y.getOrNull(i) ?: 0L)
            if (diff != 0) return diff
        }
        // Same numbers: a release is newer than its pre-release ("2.1" > "2.1-beta").
        val preA = a.contains('-')
        val preB = b.contains('-')
        return when {
            preA == preB -> 0
            preA -> -1
            else -> 1
        }
    }

    fun isNewer(a: String, b: String): Boolean = parts(a).isNotEmpty() && compare(a, b) > 0
}
