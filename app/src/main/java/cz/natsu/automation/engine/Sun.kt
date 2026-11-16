package cz.natsu.automation.engine

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * When the sun rises and sets at a place – computed in the phone (the "sunrise
 * equation"), no internet needed. Good to about a minute or two.
 */
object Sun {
    private const val DAY_MS = 86_400_000.0
    /** Julian date of 1. 1. 1970 0:00 UTC and of 1. 1. 2000 12:00. */
    private const val UNIX_EPOCH = 2440587.5
    private const val J2000 = 2451545.0

    private fun rad(degrees: Double) = Math.toRadians(degrees)

    /**
     * Sunrise ([rise]) or sunset of the solar day around [time] (ms since 1970) at the
     * place; null where the sun does not rise or set that day (polar day / night).
     */
    fun time(lat: Double, lon: Double, time: Long, rise: Boolean): Long? {
        val day = Math.round(time / DAY_MS + UNIX_EPOCH - J2000 + lon / 360.0)
        val noon = day - lon / 360.0
        val anomaly = (357.5291 + 0.98560028 * noon).mod(360.0)
        val center = 1.9148 * sin(rad(anomaly)) + 0.0200 * sin(rad(2 * anomaly)) + 0.0003 * sin(rad(3 * anomaly))
        val longitude = (anomaly + center + 180.0 + 102.9372).mod(360.0)
        val transit = J2000 + noon + 0.0053 * sin(rad(anomaly)) - 0.0069 * sin(rad(2 * longitude))
        val declination = asin(sin(rad(longitude)) * sin(rad(23.4397)))
        // -0.833°: the upper edge of the sun on the horizon, with the bending of light in the air.
        val cosHour = (sin(rad(-0.833)) - sin(rad(lat)) * sin(declination)) / (cos(rad(lat)) * cos(declination))
        if (cosHour < -1.0 || cosHour > 1.0) return null
        val hour = Math.toDegrees(acos(cosHour)) / 360.0
        val julian = if (rise) transit - hour else transit + hour
        return Math.round((julian - UNIX_EPOCH) * DAY_MS)
    }

    /** The first sunrise ([rise]) or sunset after [now]; null when there is none in the next days. */
    fun next(lat: Double, lon: Double, rise: Boolean, now: Long = System.currentTimeMillis()): Long? =
        (-1..3).mapNotNull { time(lat, lon, now + (it * DAY_MS).toLong(), rise) }.filter { it > now }.minOrNull()
}
