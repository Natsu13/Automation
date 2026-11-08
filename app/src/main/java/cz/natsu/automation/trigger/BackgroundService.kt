package cz.natsu.automation.trigger

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.MainActivity
import cz.natsu.automation.R
import cz.natsu.automation.data.EventType
import cz.natsu.automation.data.TriggerType
import cz.natsu.automation.engine.DeviceState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one long-running part of the app. It runs only while it has something to do:
 * the clipboard monitor is on, or some automation reacts to an event that Android
 * delivers only to a running app (Wi-Fi, charger, screen, Bluetooth …).
 *
 * Everything is event-driven – nothing is polled and no wakelock is held, so while
 * the phone sleeps the service sleeps too.
 *
 * The clipboard log reader is NOT paused while the screen is off: Android 13+
 * gives full log access to a new logcat only right after the user allowed it
 * (with the app on screen); a reader restarted later from the background would
 * only see the app's own logs and miss every copy.
 */
class BackgroundService : Service() {
    companion object {
        private const val TAG = "BackgroundService"
        private const val CHANNEL_ID = "clipboard_monitor"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var logReaderAlive = false
            private set

        /** Events the running service listens to (for the settings screen). */
        @Volatile
        var listening: Set<EventType> = emptySet()
            private set

        @Volatile
        var serviceRunning = false
            private set

        private const val EXTRA_FOREGROUND = "foreground"

        /**
         * Starts or stops the service to match the settings and the automations.
         * [fromForeground] = called with the app on screen; a log reader started
         * earlier from the background is then restarted so it gets full log access.
         */
        fun sync(context: Context, fromForeground: Boolean = false) {
            val app = context.applicationContext
            val repository = (app as AutomationApplication).repository
            repository.appScope.launch {
                val needed = ClipboardMonitor.active(app) ||
                        repository.usedEventTypes().any { it.needsService }
                Handler(Looper.getMainLooper()).post {
                    val intent = Intent(app, BackgroundService::class.java).putExtra(EXTRA_FOREGROUND, fromForeground)
                    if (needed) {
                        try {
                            ContextCompat.startForegroundService(app, intent)
                        } catch (e: Exception) {
                            // Background start refused; it is started again when the app is opened.
                            Log.w(TAG, "Cannot start the service now", e)
                        }
                    } else if (serviceRunning) {
                        app.stopService(intent)
                    }
                }
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { (application as AutomationApplication).repository }

    private var clipboardOn = false
    private var events: Set<EventType> = emptySet()

    // ---------- clipboard log reader ----------
    private var reader: Thread? = null
    private var process: Process? = null
    private var lastLaunch = 0L
    /** The reader was started while the app was on screen, so Android gave it full log access. */
    private var readerTrusted = false

    // Has to be registered: the system only logs the denial for registered listeners.
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val foreground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        repository.appScope.launch {
            val types = repository.usedEventTypes().filter { it.needsService }.toSet()
            val clipboard = ClipboardMonitor.active(this@BackgroundService)
            handler.post { configure(clipboard, types, foreground) }
        }
        return START_STICKY
    }

    /** Applies what should be watched; stops the service when nothing is left. */
    private fun configure(clipboard: Boolean, types: Set<EventType>, foreground: Boolean) {
        if (!clipboard && types.isEmpty()) {
            stopSelf()
            return
        }
        UsageMeter.sample(this)

        if (clipboard != clipboardOn) {
            clipboardOn = clipboard
            val manager = getSystemService(ClipboardManager::class.java)
            if (clipboard) {
                manager?.addPrimaryClipChangedListener(clipListener)
                startReader(foreground)
            } else {
                manager?.removePrimaryClipChangedListener(clipListener)
                stopReader()
            }
        } else if (clipboard && foreground && !readerTrusted) {
            // Started in the background (e.g. after a reboot): restart now that log access can be granted.
            stopReader()
            startReader(foreground = true)
        }

        if (types != events) {
            unregisterEvents()
            events = types
            registerEvents()
        }
        listening = events
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification())
    }

    // ---------- screen (always watched: samples usage, screen events) ----------

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    UsageMeter.sample(context)
                    fireIf(EventType.ScreenOn)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    UsageMeter.sample(context)
                    fireIf(EventType.ScreenOff)
                }
                Intent.ACTION_USER_PRESENT -> fireIf(EventType.Unlocked)
            }
        }
    }

    private fun fireIf(type: EventType, values: Map<String, String> = emptyMap()) {
        if (type in events) EventDispatcher.fire(this, type, values)
    }

    // ---------- other events ----------

    private var eventReceiverRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: String? = null
    private var lastWifiName: String = ""
    private var lastBattery: Int? = null

    private val evaluateNetwork = Runnable { onNetworkSettled() }

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (isInitialStickyBroadcast) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) lastBattery = batteryPercent(intent)
                return
            }
            when (intent.action) {
                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    fireIf(if (intent.getBooleanExtra("state", false)) EventType.AirplaneOn else EventType.AirplaneOff)

                Intent.ACTION_POWER_CONNECTED -> fireIf(EventType.PowerConnected, batteryValues(charging = true))
                Intent.ACTION_POWER_DISCONNECTED -> fireIf(EventType.PowerDisconnected, batteryValues(charging = false))

                Intent.ACTION_BATTERY_CHANGED -> onBattery(batteryPercent(intent))

                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val name = try {
                        device?.name ?: device?.address ?: ""
                    } catch (e: SecurityException) {
                        ""
                    }
                    val type = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
                        EventType.BluetoothConnected else EventType.BluetoothDisconnected
                    fireIf(type, mapOf("bt_device" to name))
                }

                AudioManager.ACTION_HEADSET_PLUG ->
                    fireIf(if (intent.getIntExtra("state", 0) == 1) EventType.HeadsetPlugged else EventType.HeadsetUnplugged)

                android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED ->
                    fireIf(EventType.DndChanged, mapOf("dnd" to if (DeviceState.dndOn(context)) "zapnuto" else "vypnuto"))
            }
        }
    }

    private fun registerEvents() {
        val filter = IntentFilter()
        if (EventType.AirplaneOn in events || EventType.AirplaneOff in events) filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        if (EventType.PowerConnected in events) filter.addAction(Intent.ACTION_POWER_CONNECTED)
        if (EventType.PowerDisconnected in events) filter.addAction(Intent.ACTION_POWER_DISCONNECTED)
        if (EventType.BatteryBelow in events || EventType.BatteryAbove in events) filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        if ((EventType.BluetoothConnected in events || EventType.BluetoothDisconnected in events) && bluetoothAllowed()) {
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (EventType.HeadsetPlugged in events || EventType.HeadsetUnplugged in events) filter.addAction(AudioManager.ACTION_HEADSET_PLUG)
        if (EventType.DndChanged in events) filter.addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)

        if (filter.countActions() > 0) {
            ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            eventReceiverRegistered = true
        }

        val networkTypes = setOf(EventType.WifiConnected, EventType.WifiDisconnected, EventType.MobileConnected, EventType.Offline)
        if (events.any { it in networkTypes }) {
            lastNetwork = DeviceState.networkType(this)
            lastWifiName = if (lastNetwork == "wifi") DeviceState.wifiName(this) else ""
            val callback = object : ConnectivityManager.NetworkCallback() {
                // Switching networks produces a burst of callbacks; evaluate once it settles.
                private fun changed() {
                    handler.removeCallbacks(evaluateNetwork)
                    handler.postDelayed(evaluateNetwork, 1500)
                }

                override fun onAvailable(network: Network) = changed()
                override fun onLost(network: Network) = changed()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
            }
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback, handler)
            networkCallback = callback
        }
    }

    private fun unregisterEvents() {
        if (eventReceiverRegistered) {
            unregisterReceiver(eventReceiver)
            eventReceiverRegistered = false
        }
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        handler.removeCallbacks(evaluateNetwork)
        lastBattery = null
    }

    private fun bluetoothAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun onNetworkSettled() {
        val now = DeviceState.networkType(this)
        val wifiName = if (now == "wifi") DeviceState.wifiName(this) else ""
        val before = lastNetwork
        val beforeName = lastWifiName
        lastNetwork = now
        lastWifiName = wifiName
        if (before == null || (before == now && beforeName == wifiName)) return

        val wifiChanged = before == "wifi" && now == "wifi" && beforeName != wifiName
        if ((before == "wifi" && now != "wifi") || wifiChanged) {
            fireIf(EventType.WifiDisconnected, mapOf("wifi_name" to beforeName, "network" to now))
        }
        if ((before != "wifi" && now == "wifi") || wifiChanged) {
            fireIf(EventType.WifiConnected, mapOf("wifi_name" to wifiName, "network" to now))
        }
        if (now == "mobile" && before != "mobile") fireIf(EventType.MobileConnected, mapOf("network" to now))
        if (now == "none" && before != "none") fireIf(EventType.Offline, mapOf("network" to now))
    }

    private fun batteryPercent(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }

    private fun batteryValues(charging: Boolean? = null): Map<String, String> = mapOf(
        "battery" to (DeviceState.batteryLevel(this)?.toString() ?: ""),
        "charging" to (charging ?: DeviceState.charging(this)).toString(),
    )

    private fun onBattery(level: Int?) {
        level ?: return
        val before = lastBattery
        lastBattery = level
        if (before == null || before == level) return
        val values = mapOf("battery" to level.toString(), "charging" to DeviceState.charging(this).toString())
        if (level < before && EventType.BatteryBelow in events) {
            EventDispatcher.fire(this, EventType.BatteryBelow, values) { trigger ->
                val limit = trigger.param("percent").toIntOrNull() ?: return@fire false
                level < limit && before >= limit
            }
        }
        if (level > before && EventType.BatteryAbove in events) {
            EventDispatcher.fire(this, EventType.BatteryAbove, values) { trigger ->
                val limit = trigger.param("percent").toIntOrNull() ?: return@fire false
                level >= limit && before < limit
            }
        }
    }

    // ---------- clipboard log reader ----------

    private fun startReader(foreground: Boolean) {
        if (reader?.isAlive == true) return
        readerTrusted = foreground
        val needle = "Denying clipboard access to $packageName"
        // Only lines logged from now on.
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        reader = Thread({
            logReaderAlive = true
            try {
                val started = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-T", since, "-v", "brief", "ClipboardService:E", "*:S")
                )
                process = started
                started.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.contains(needle)) handler.post(::onClipboardChanged)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Log reader stopped", e)
            } finally {
                logReaderAlive = false
            }
        }, "clipboard-log-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopReader() {
        process?.destroy()
        process = null
        reader?.interrupt()
        reader = null
        logReaderAlive = false
    }

    private fun onClipboardChanged() {
        // One copy can produce several log lines.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunch < 700) return
        lastLaunch = now

        repository.appScope.launch {
            if (repository.withTrigger(TriggerType.Clipboard).isEmpty()) return@launch
            handler.post {
                try {
                    startActivity(TriggerActivity.clipboardIntent(this@BackgroundService))
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot open the clipboard reader", e)
                }
            }
        }
    }

    override fun onDestroy() {
        UsageMeter.sample(this)
        unregisterReceiver(screenReceiver)
        unregisterEvents()
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        stopReader()
        listening = emptySet()
        serviceRunning = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Běh na pozadí", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Trvalé oznámení, dokud aplikace hlídá schránku nebo události."
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parts = buildList {
            if (clipboardOn) add("schránku")
            if (events.isNotEmpty()) add(
                when (events.size) {
                    1 -> "1 událost"
                    in 2..4 -> "${events.size} události"
                    else -> "${events.size} událostí"
                }
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (parts.isEmpty()) "Automatizace běží na pozadí" else "Hlídám ${parts.joinToString(" a ")}")
            .setContentText("Klepnutím otevřeš nastavení a spotřebu.")
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
