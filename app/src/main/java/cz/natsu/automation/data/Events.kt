package cz.natsu.automation.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/**
 * A system event an automation reacts to ("Wi-Fi connected", "charger plugged in", ...).
 * [params] hold the few settings some events have (time, battery level).
 */
@Serializable
data class EventTrigger(
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val id: String = UUID.randomUUID().toString(),
) {
    fun param(key: String): String = params[key] ?: EventType.of(type)?.defaults?.get(key) ?: ""
}

/** Something the user has to allow for an event (or its values) to work. */
enum class EventPermission {
    Location, Bluetooth, NotificationAccess,

    /** Location allowed "always": the system watches a place also while the app sleeps. */
    Place,
    Nfc, Sms, Phone,

    /** The accessibility service that tells which app came to the screen. */
    Accessibility,
}

enum class EventGroup(val title: String) {
    Network("Síť"),
    Power("Baterie a nabíjení"),
    Screen("Displej"),
    Devices("Zařízení"),
    Phone("Zprávy a hovory"),
    Place("Místo"),
    Time("Čas"),
    Other("Ostatní"),
}

/**
 * Events the app can react to. [needsService] = the event is delivered only to a
 * running process, so the background service has to run while some automation uses it.
 * [input] says what the "Vstup" variable contains when the event starts an automation.
 */
enum class EventType(
    val key: String,
    val title: String,
    val description: String,
    val group: EventGroup,
    val needsService: Boolean = true,
    val permission: EventPermission? = null,
    val defaults: Map<String, String> = emptyMap(),
    val input: String? = null,
) {
    WifiConnected(
        "wifi_connected", "Připojení k Wi-Fi",
        "Když se telefon připojí k Wi-Fi. Vstup = název sítě (potřebuje přístup k poloze).",
        EventGroup.Network, permission = EventPermission.Location, input = "název Wi-Fi",
    ),
    WifiDisconnected(
        "wifi_disconnected", "Odpojení od Wi-Fi",
        "Když telefon přestane používat Wi-Fi. Vstup = síť, od které se odpojil.",
        EventGroup.Network, permission = EventPermission.Location, input = "název Wi-Fi",
    ),
    MobileConnected(
        "mobile_connected", "Přechod na mobilní data",
        "Když se internet začne používat přes mobilní data.",
        EventGroup.Network,
    ),
    Offline(
        "offline", "Ztráta internetu",
        "Když telefon nemá žádné připojení k internetu.",
        EventGroup.Network,
    ),
    AirplaneOn("airplane_on", "Zapnutí režimu letadlo", "Když se zapne režim letadlo.", EventGroup.Network),
    AirplaneOff("airplane_off", "Vypnutí režimu letadlo", "Když se vypne režim letadlo.", EventGroup.Network),

    PowerConnected(
        "power_connected", "Připojení nabíječky", "Když se telefon začne nabíjet.",
        EventGroup.Power, input = "baterie v %",
    ),
    PowerDisconnected(
        "power_disconnected", "Odpojení nabíječky", "Když se nabíječka odpojí.",
        EventGroup.Power, input = "baterie v %",
    ),
    BatteryBelow(
        "battery_below", "Baterie klesne pod",
        "Jednou, když nabití klesne pod zvolenou hranici.",
        EventGroup.Power, defaults = mapOf("percent" to "20"), input = "baterie v %",
    ),
    BatteryAbove(
        "battery_above", "Baterie nabitá nad",
        "Jednou, když nabití při nabíjení překročí zvolenou hranici (třeba 80 % pro šetrné nabíjení).",
        EventGroup.Power, defaults = mapOf("percent" to "80"), input = "baterie v %",
    ),

    ScreenOn("screen_on", "Rozsvícení displeje", "Když se rozsvítí displej (i na zamykací obrazovce).", EventGroup.Screen),
    ScreenOff("screen_off", "Zhasnutí displeje", "Když displej zhasne.", EventGroup.Screen),
    Unlocked("unlocked", "Odemčení telefonu", "Když odemkneš telefon.", EventGroup.Screen),

    BluetoothConnected(
        "bt_connected", "Připojení Bluetooth zařízení",
        "Když se připojí sluchátka, auto, hodinky… Vstup = jméno zařízení.",
        EventGroup.Devices, permission = EventPermission.Bluetooth, input = "jméno zařízení",
    ),
    BluetoothDisconnected(
        "bt_disconnected", "Odpojení Bluetooth zařízení",
        "Když se Bluetooth zařízení odpojí.",
        EventGroup.Devices, permission = EventPermission.Bluetooth, input = "jméno zařízení",
    ),
    HeadsetPlugged("headset_plugged", "Zapojení sluchátek", "Kabelová sluchátka (jack nebo USB-C).", EventGroup.Devices),
    HeadsetUnplugged("headset_unplugged", "Odpojení sluchátek", "Kabelová sluchátka (jack nebo USB-C).", EventGroup.Devices),
    DndChanged(
        "dnd_changed", "Změna režimu Nerušit",
        "Když se Nerušit zapne nebo vypne. Vstup = zapnuto / vypnuto.",
        EventGroup.Devices, input = "zapnuto / vypnuto",
    ),

    NfcTag(
        "nfc_tag", "Přiložení NFC štítku",
        "Když k telefonu přiložíš NFC štítek (displej musí svítit). Vstup = text na štítku, nebo jeho ID, když je prázdný.",
        EventGroup.Devices, needsService = false, permission = EventPermission.Nfc, input = "text štítku nebo ID",
    ),

    SmsReceived(
        "sms_received", "Příchozí SMS",
        "Když přijde textová zpráva. Vstup = text; odesílatele vrátí blok Údaje z události.",
        EventGroup.Phone, needsService = false, permission = EventPermission.Sms, input = "text zprávy",
    ),
    IncomingCall(
        "incoming_call", "Příchozí hovor",
        "Když telefon začne zvonit. Vstup = číslo volajícího (prázdné u skrytého čísla).",
        EventGroup.Phone, needsService = false, permission = EventPermission.Phone, input = "číslo volajícího",
    ),

    PlaceEntered(
        "place_entered", "Příchod na místo",
        "Když telefon dorazí do okruhu kolem zvoleného místa. Android si toho všimne obvykle do pár minut.",
        EventGroup.Place, needsService = false, permission = EventPermission.Place,
        defaults = mapOf("place" to "", "radius" to "200"),
    ),
    PlaceLeft(
        "place_left", "Odchod z místa",
        "Když telefon opustí okruh kolem zvoleného místa.",
        EventGroup.Place, needsService = false, permission = EventPermission.Place,
        defaults = mapOf("place" to "", "radius" to "200"),
    ),

    Sunrise(
        "sunrise", "Východ slunce",
        "Každý den při východu slunce na zvoleném místě. Posun (třeba půl hodiny potom) udělá blok Počkat.",
        EventGroup.Time, needsService = false, defaults = mapOf("place" to ""),
    ),
    Sunset(
        "sunset", "Západ slunce",
        "Každý den při západu slunce na zvoleném místě.",
        EventGroup.Time, needsService = false, defaults = mapOf("place" to ""),
    ),
    TimeDaily(
        "time_daily", "V určený čas",
        "Každý zvolený den v daný čas.",
        EventGroup.Time, needsService = false, defaults = mapOf("time" to "07:00", "days" to "1234567"),
    ),
    TimeInterval(
        "time_interval", "Opakovaně",
        "Každých N minut (nejméně 15). V úsporném režimu může Android spuštění o pár minut posunout.",
        EventGroup.Time, needsService = false, defaults = mapOf("minutes" to "60"),
    ),

    Notification(
        "notification", "Příchozí oznámení",
        "Když jakákoli aplikace zobrazí oznámení. Vstup = text; aplikaci a nadpis vrátí blok Údaje z události, filtrovat je jde v Podmínkách spuštění.",
        EventGroup.Other, needsService = false, permission = EventPermission.NotificationAccess, input = "text oznámení",
    ),
    AppOpened(
        "app_opened", "Otevření aplikace",
        "Když se na displeji objeví jiná aplikace. Vstup = její balíček (třeba com.spotify.music); " +
                "kterou aplikaci chceš, nastav v Podmínkách spuštění.",
        EventGroup.Other, needsService = false, permission = EventPermission.Accessibility, input = "balíček aplikace",
    ),
    Boot("boot", "Po zapnutí telefonu", "Jednou po startu telefonu.", EventGroup.Other, needsService = false),
    ThisAppOpened(
        "this_app_opened", "Otevření této aplikace",
        "Když otevřeš Automatizace. S podmínkou spuštění „Hodin od posledního spuštění“ třeba jen jednou denně.",
        EventGroup.Other, needsService = false,
    );

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): EventType? = byKey[key]
    }
}

object EventJson {
    private val serializer = ListSerializer(EventTrigger.serializer())

    fun encode(events: List<EventTrigger>): String = BlockJson.json.encodeToString(serializer, events)

    fun decode(text: String): List<EventTrigger> = try {
        BlockJson.json.decodeFromString(serializer, text)
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Values an event provides to the automation (VarRef.Event keys). Outside an event
 * the device-related ones are read live when the automation needs them.
 */
enum class EventValue(val key: String, val label: String, val description: String) {
    WifiName("wifi_name", "Název Wi-Fi", "Název připojené Wi-Fi sítě (potřebuje přístup k poloze)."),
    Network("network", "Typ připojení", "wifi / mobile / none"),
    Battery("battery", "Baterie (%)", "Aktuální nabití baterie v procentech."),
    Charging("charging", "Nabíjí se", "true / false"),
    BluetoothDevice("bt_device", "Bluetooth zařízení", "Jméno zařízení z události Bluetooth."),
    Dnd("dnd", "Nerušit", "zapnuto / vypnuto"),
    NotificationApp("notif_app", "Oznámení – aplikace", "Název aplikace, která oznámení poslala."),
    NotificationPackage("notif_package", "Oznámení – balíček", "Balíček aplikace, třeba com.whatsapp."),
    NotificationTitle("notif_title", "Oznámení – nadpis", "Nadpis oznámení (často odesílatel)."),
    NotificationText("notif_text", "Oznámení – text", "Text oznámení."),
    NfcId("nfc_id", "NFC štítek – ID", "Číslo štítku, třeba 04:A2:1B:…; má ho i prázdný štítek."),
    NfcText("nfc_text", "NFC štítek – text", "Text nebo odkaz uložený na štítku."),
    SmsSender("sms_sender", "SMS – odesílatel", "Číslo nebo jméno odesílatele zprávy."),
    SmsText("sms_text", "SMS – text", "Text zprávy."),
    CallNumber("call_number", "Hovor – číslo", "Číslo volajícího (potřebuje přístup k historii hovorů)."),
    AppPackage("app_package", "Aplikace – balíček", "Balíček otevřené aplikace, třeba com.spotify.music."),
    AppName("app_name", "Aplikace – název", "Název otevřené aplikace."),
    WidgetId("widget_id", "Widget – id", "Číslo widgetu na ploše, který se překresluje."),
    WidgetWidth("widget_width", "Widget – šířka (dp)", "Šířka widgetu v dp (jednotkách CSS px)."),
    WidgetHeight("widget_height", "Widget – výška (dp)", "Výška widgetu v dp (jednotkách CSS px)."),
    WidgetAction("widget_action", "Widget – akce klepnutí", "Vstup tlačítka nebo prvku, na který se klepnulo; prázdné při obnovení."),
    DarkMode("dark_mode", "Tmavý režim", "true / false");

    companion object {
        private val byKey = entries.associateBy { it.key }
        fun of(key: String): EventValue? = byKey[key]
    }
}
