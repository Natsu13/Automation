package cz.natsu.automation.trigger

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import cz.natsu.automation.AutomationApplication
import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.EventType
import cz.natsu.automation.engine.Locator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Events that come from outside the background service: an NFC tag, a text message,
 * a call, another app coming to the screen, arriving at a place. Android delivers
 * each of them to its own small component here, so nothing has to keep running.
 */
object MoreEvents {
    private const val TAG = "MoreEvents"

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 17: an app built for it gets tags only through a screen guarded by the
     * DISPATCH_NFC_MESSAGE permission; older Androids may not let the NFC open such a screen.
     * So the manifest has one entry of each kind and only the fitting one is switched on.
     */
    private const val NFC_GUARDED_FROM = 37

    private fun nfcEntry(guarded: Boolean) = ComponentName(
        AutomationApplication.instance, "cz.natsu.automation.trigger." + if (guarded) "NfcTagGuarded" else "NfcTag"
    )

    /** Called whenever the automations change (and after a reboot, [afterBoot]). */
    fun sync(context: Context, all: List<AutomationEntity>, afterBoot: Boolean = false) {
        val used = all.flatMap { it.activeEvents }.mapNotNull { EventType.of(it.type) }.toSet()
        syncNfc(context, EventType.NfcTag in used)
        PlaceEvents.sync(context, all, afterBoot)
    }

    /**
     * Tags open the app only while some automation waits for one ([used]); otherwise other apps keep them.
     *
     * [renew] (when the app's process starts): the phone's NFC keeps its own list of apps that take
     * tags and renews it only when a package changes. After the app was force-stopped (also by an
     * install from the IDE) the list still says "stopped" and tags go elsewhere; switching the
     * entry off and on again is such a change.
     */
    fun syncNfc(context: Context, used: Boolean, renew: Boolean = false) {
        try {
            val pm = context.packageManager
            // The screen itself is always there (older versions of the app switched it).
            pm.setComponentEnabledSetting(
                ComponentName(context, NfcActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
            )
            for (guarded in listOf(false, true)) {
                val entry = nfcEntry(guarded)
                val on = used && guarded == (Build.VERSION.SDK_INT >= NFC_GUARDED_FROM)
                val isOn = pm.getComponentEnabledSetting(entry) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                if (on == isOn && !(on && renew)) continue
                if (on && isOn) {
                    pm.setComponentEnabledSetting(entry, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
                pm.setComponentEnabledSetting(
                    entry,
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot switch the NFC screen", e)
        }
    }

    /** Lets a broadcast receiver wait for the automations of [type] (Android gives it about 10 s). */
    fun fireFrom(receiver: BroadcastReceiver, context: Context, type: EventType, values: Map<String, String>, wait: Long = 0) {
        val app = context.applicationContext
        val pending = receiver.goAsync()
        (app as AutomationApplication).repository.appScope.launch {
            try {
                if (wait > 0) delay(wait)
                // The run goes on in the app scope; the receiver just stops waiting.
                withTimeoutOrNull(9_000 - wait) { EventDispatcher.fire(app, type, values).join() }
            } finally {
                pending.finish()
            }
        }
    }
}

// ---------- text messages and calls ----------

/** "Příchozí SMS": needs the RECEIVE_SMS permission. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            null
        }
        if (parts.isNullOrEmpty()) return
        // A long message comes in several parts.
        MoreEvents.fireFrom(
            this, context, EventType.SmsReceived,
            mapOf(
                "sms_sender" to (parts[0].displayOriginatingAddress ?: ""),
                "sms_text" to parts.joinToString("") { it.displayMessageBody ?: "" },
            )
        )
    }
}

/** "Příchozí hovor": the phone starts ringing. The number needs the call log permission as well. */
class CallReceiver : BroadcastReceiver() {
    companion object {
        /** The ringing call has already fired the event (Android may tell about it twice). */
        @Volatile
        private var fired = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) {
            fired = false
            return
        }
        if (fired) return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        if (number.isNotEmpty() || !MoreEvents.granted(context, Manifest.permission.READ_CALL_LOG)) {
            fired = true
            MoreEvents.fireFrom(this, context, EventType.IncomingCall, mapOf("call_number" to number))
            return
        }
        // With the call log permission the same news comes twice, once with the number: wait for
        // that one for a moment; a hidden number never comes.
        val app = context.applicationContext
        val pending = goAsync()
        (app as AutomationApplication).repository.appScope.launch {
            try {
                delay(800)
                if (!fired) {
                    fired = true
                    withTimeoutOrNull(8_000) { EventDispatcher.fire(app, EventType.IncomingCall, mapOf("call_number" to "")).join() }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

// ---------- NFC ----------

/** A scanned NFC tag opens this screen for a moment; it only passes the tag on and closes. */
class NfcActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.w("NfcActivity", "Cannot read the tag", e)
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val tag: Tag? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        val id = tag?.id?.joinToString(":") { "%02X".format(it) }.orEmpty()
        val messages = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.filterIsInstance<NdefMessage>()
        }.orEmpty()
        val text = messages.flatMap { it.records.toList() }.mapNotNull { textOf(it) }.joinToString("\n")
        if (id.isEmpty() && text.isEmpty()) return
        EventDispatcher.fire(this, EventType.NfcTag, mapOf("nfc_id" to id, "nfc_text" to text))
    }

    /** What a record of the tag says: a text, a link, or plain data read as text. */
    private fun textOf(record: NdefRecord): String? {
        val payload = record.payload ?: return null
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            if (payload.isEmpty()) return null
            // The first byte says how the text is stored and how long the language code before it is.
            val status = payload[0].toInt()
            val language = status and 0x3F
            val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
            return if (payload.size > language) String(payload, 1 + language, payload.size - 1 - language, charset) else null
        }
        // The app mark ("Přidat značku této aplikace") and other technical records are not content.
        if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE) return null
        record.toUri()?.let { return it.toString() }
        return if (record.tnf == NdefRecord.TNF_MIME_MEDIA) String(payload, Charsets.UTF_8) else null
    }
}

// ---------- another app on the screen ----------

/**
 * "Otevření aplikace": an accessibility service is the only way Android tells an app which
 * other app came to the screen without asking again and again. It reads nothing from the
 * screens – only the name of the app whose screen opened.
 */
class AppWatchService : AccessibilityService() {
    companion object {
        fun enabled(context: Context): Boolean {
            val own = ComponentName(context, AppWatchService::class.java).flattenToString()
            return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { it.equals(own, ignoreCase = true) }
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private var last: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val app = event.packageName?.toString() ?: return
        if (app == last || app == packageName) return
        // Only real screens of apps: dialogs of the system, the keyboard or the notification shade are not "opening an app".
        val screen = event.className?.toString() ?: return
        val label = try {
            packageManager.getActivityInfo(ComponentName(app, screen), 0)
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(app, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return
        }
        last = app
        EventDispatcher.fire(this, EventType.AppOpened, mapOf("app_package" to app, "app_name" to label))
    }

    override fun onInterrupt() = Unit
}

// ---------- places ----------

/**
 * "Příchod na místo" / "Odchod z místa": the system location service watches the circle
 * around the place and tells [PlaceReceiver] when the phone crosses it. Needs location
 * allowed "always"; how soon it notices is up to Android (usually within a few minutes).
 */
object PlaceEvents {
    private const val TAG = "PlaceEvents"
    private const val PREFS = "places"
    private const val KEY_WATCHED = "watched"
    const val EXTRA_AUTOMATION = "automation"
    const val EXTRA_EVENT = "event"
    const val DEFAULT_RADIUS = 200

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context, automationId: Long, eventId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            eventId.hashCode(),
            Intent(context, PlaceReceiver::class.java)
                .setAction("cz.natsu.automation.PLACE.$eventId")
                .putExtra(EXTRA_AUTOMATION, automationId)
                .putExtra(EXTRA_EVENT, eventId),
            // Mutable: the system adds whether the phone is entering or leaving.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        )

    fun sync(context: Context, all: List<AutomationEntity>, afterBoot: Boolean = false) {
        val prefs = prefs(context)
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val watched = mutableSetOf<String>()
        val edit = prefs.edit()
        for (automation in all) {
            for (event in automation.activeEvents) {
                val type = EventType.of(event.type)
                if (type != EventType.PlaceEntered && type != EventType.PlaceLeft) continue
                val (lat, lon) = Locator.parse(event.param("place")) ?: continue
                val radius = (event.param("radius").toIntOrNull() ?: DEFAULT_RADIUS).coerceIn(50, 5000)
                watched += event.id
                // Watched already with the same settings: leave it, so the event does not fire again.
                val config = "$lat,$lon,$radius"
                if (!afterBoot && prefs.getString("cfg_${event.id}", null) == config) continue
                val intent = pendingIntent(context, automation.id, event.id)
                try {
                    manager.removeProximityAlert(intent)
                    manager.addProximityAlert(lat, lon, radius.toFloat(), -1, intent)
                    edit.putString("cfg_${event.id}", config)
                    // Where the phone is now, so only a real arrival or departure fires the event.
                    if (!afterBoot) edit.putBoolean("inside_${event.id}", insideNow(manager, lat, lon, radius))
                } catch (e: SecurityException) {
                    // No location permission yet; tried again with the next change.
                    Log.w(TAG, "Cannot watch the place", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot watch the place", e)
                }
            }
        }
        for (old in prefs.getStringSet(KEY_WATCHED, emptySet()).orEmpty() - watched) {
            try {
                manager.removeProximityAlert(pendingIntent(context, 0, old))
            } catch (_: Exception) {
            }
            edit.remove("cfg_$old").remove("inside_$old")
        }
        edit.putStringSet(KEY_WATCHED, watched).apply()
    }

    /** Whether the last place the phone knew about is inside the circle. */
    private fun insideNow(manager: LocationManager, lat: Double, lon: Double, radius: Int): Boolean {
        val last = try {
            manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        } ?: return false
        val distance = FloatArray(1)
        android.location.Location.distanceBetween(last.latitude, last.longitude, lat, lon, distance)
        return distance[0] <= radius
    }

    /**
     * The phone is [entering] (or leaving) the place of [eventId]; true when that is news –
     * the system repeats itself now and then, and says "inside" again after a reboot.
     */
    fun changed(context: Context, eventId: String, entering: Boolean): Boolean {
        val prefs = prefs(context)
        val key = "inside_$eventId"
        val before = prefs.getBoolean(key, false)
        prefs.edit().putBoolean(key, entering).apply()
        return before != entering
    }
}

class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val automationId = intent.getLongExtra(PlaceEvents.EXTRA_AUTOMATION, -1L)
        val eventId = intent.getStringExtra(PlaceEvents.EXTRA_EVENT) ?: return
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val app = context.applicationContext
        if (!PlaceEvents.changed(app, eventId, entering)) return
        val repository = (app as AutomationApplication).repository
        val pending = goAsync()
        repository.appScope.launch {
            try {
                val automation = repository.get(automationId) ?: return@launch
                val event = automation.activeEvents.firstOrNull { it.id == eventId } ?: return@launch
                val type = EventType.of(event.type) ?: return@launch
                if ((type == EventType.PlaceEntered) != entering) return@launch
                withTimeoutOrNull(9_000) { EventDispatcher.fireFor(app, automation, type).join() }
            } finally {
                pending.finish()
            }
        }
    }
}
