package cz.natsu.automation.engine

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import cz.natsu.automation.data.EventValue

/** How an automation was started by a system event, and the values the event brought. */
data class EventInfo(
    /** Shown as "Typ spuštění", e.g. "Připojení k Wi-Fi". */
    val label: String,
    val values: Map<String, String> = emptyMap(),
)

/** Current device state, for event values used outside of an event (or not provided by it). */
object DeviceState {
    fun live(context: Context, key: String): String = when (EventValue.of(key)) {
        EventValue.WifiName -> wifiName(context)
        EventValue.Network -> networkType(context)
        EventValue.Battery -> batteryLevel(context)?.toString() ?: ""
        EventValue.Charging -> charging(context).toString()
        EventValue.Dnd -> if (dndOn(context)) "zapnuto" else "vypnuto"
        EventValue.DarkMode -> darkMode(context).toString()
        else -> ""
    }

    fun darkMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun networkType(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Name of the connected Wi-Fi without quotes; empty when unknown (no location access). */
    @Suppress("DEPRECATION")
    fun wifiName(context: Context): String {
        if (!hasLocation(context)) return ""
        val ssid = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid ?: return ""
        if (ssid == WifiManager.UNKNOWN_SSID || ssid == "<unknown ssid>") return ""
        return ssid.removeSurrounding("\"")
    }

    fun batteryLevel(context: Context): Int? {
        val battery = context.getSystemService(BatteryManager::class.java) ?: return null
        return battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    fun charging(context: Context): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun dndOn(context: Context): Boolean {
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        return filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}
