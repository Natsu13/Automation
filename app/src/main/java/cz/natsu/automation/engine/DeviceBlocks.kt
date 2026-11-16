package cz.natsu.automation.engine

import cz.natsu.automation.i18n.tr

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.KeyEvent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.core.location.LocationManagerCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.VarRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Small blocks around the phone itself: speaking and listening, where it is, what
 * plays, the display, Do not disturb, the camera, QR codes and reading files. Each
 * does one thing and hands its output to the next step.
 */
object DeviceBlocks {
    private const val MAX_TEXT_FILE = 2 * 1024 * 1024
    private const val MAX_BASE64_FILE = 10 * 1024 * 1024

    val all: List<BlockDef> = listOf(
        // ---------- voice ----------
        BlockDef(
            type = "speak",
            summary = "Přečíst nahlas {text}",
            title = "Přečíst nahlas",
            description = "Přečte text hlasem telefonu. Jazyk se bere z telefonu, nebo ho zadej zkratkou (cs, en, de…).",
            category = BlockCategory.Device,
            icon = Icons.Rounded.RecordVoiceOver,
            params = listOf(
                ParamDef.Text("text", "Text", defaultRef = VarRef.Input),
                ParamDef.Text("language", "Jazyk", placeholder = "podle telefonu (cs, en, de…)", plain = true).apply {
                    hiddenInSummaryWhen = { true }
                },
                ParamDef.Toggle("wait", "Počkat, až dočte", default = true),
            ),
        ) { block ->
            val text = text(block, "text")
            if (text.isNotBlank()) Speech.say(context, text, text(block, "language").trim(), bool(block, "wait"))
            BlockResult.None
        },

        BlockDef(
            type = "dictate",
            needsWindow = true,
            summary = "Nadiktovat text {prompt}",
            title = "Nadiktovat text",
            description = "Otevře rozpoznávání řeči a vrátí, co jsi řekl. Hodí se místo psaní: nadiktuj a pošli dál.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Mic,
            params = listOf(
                ParamDef.Text("prompt", "Výzva", placeholder = "co se má říct (nepovinné)"),
                ParamDef.Text("language", "Jazyk", placeholder = "podle telefonu (cs, en, de…)", plain = true).apply {
                    hiddenInSummaryWhen = { true }
                },
            ),
            outputName = "Nadiktovaný text",
        ) { block ->
            val ui = ui ?: throw BlockException(tr("diktování potřebuje okno (spusť akci ručně)"))
            val said = ui.dictate(text(block, "prompt"), text(block, "language").trim()) ?: throw RunCancelledException()
            BlockResult.Output(said)
        },

        // ---------- where the phone is ----------
        BlockDef(
            type = "location",
            summary = "Zjistit polohu ({format})",
            title = "Zjistit polohu",
            description = "Kde telefon právě je. Jako text „šířka,délka“ (jde rovnou do odkazu na mapu), nebo jako JSON " +
                    "s šířkou, délkou a přesností, ze kterého si hodnoty vytáhneš blokem Získat z JSON.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.MyLocation,
            params = listOf(
                ParamDef.Choice(
                    "format", "Výstup", listOf(
                        ChoiceOption("coords", "šířka,délka", "šířka,délka"),
                        ChoiceOption("json", "JSON (lat, lon, accuracy)", "JSON"),
                    )
                ),
            ),
            outputName = "Poloha",
            requirement = Requirement.Location,
        ) { block ->
            val found = Locator.current(context)
            BlockResult.Output(
                if (choice(block, "format") == "json") {
                    JsonObject(
                        mapOf(
                            "lat" to JsonPrimitive(found.latitude),
                            "lon" to JsonPrimitive(found.longitude),
                            "accuracy" to JsonPrimitive(found.accuracy.roundToInt()),
                        )
                    ).toString()
                } else {
                    Locator.coords(found.latitude, found.longitude)
                }
            )
        },

        BlockDef(
            type = "address_of",
            summary = "Adresa místa {coords}",
            title = "Adresa z polohy",
            description = "Ze souřadnic (výstup bloku Zjistit polohu, nebo „50.08,14.42“) udělá adresu. Potřebuje internet.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.Home,
            params = listOf(ParamDef.Text("coords", "Souřadnice", placeholder = "50.08,14.42")),
            outputName = "Adresa",
        ) { block ->
            val (lat, lon) = Locator.parse(text(block, "coords"))
                ?: throw BlockException(tr("souřadnice čekám jako \"šířka,délka\""))
            val address = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                } catch (e: Exception) {
                    throw BlockException(tr("adresu nejde zjistit (%s)", e.message ?: tr("bez připojení")))
                }
            } ?: throw BlockException(tr("k místu není známá žádná adresa"))
            BlockResult.Output((0..address.maxAddressLineIndex).joinToString(", ") { address.getAddressLine(it) })
        },

        // ---------- sound and display ----------
        BlockDef(
            type = "media",
            summary = "Přehrávání: {action}",
            title = "Ovládat přehrávání",
            description = "Pošle přehrávači (hudba, podcast, video) totéž co tlačítka na sluchátkách: přehrát, pozastavit, další.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.SkipNext,
            params = listOf(
                ParamDef.Choice(
                    "action", "Co udělat", listOf(
                        ChoiceOption("toggle", "Přehrát / pozastavit", "přehrát / pozastavit"),
                        ChoiceOption("play", "Přehrát", "přehrát"),
                        ChoiceOption("pause", "Pozastavit", "pozastavit"),
                        ChoiceOption("next", "Další skladba", "další"),
                        ChoiceOption("previous", "Předchozí skladba", "předchozí"),
                        ChoiceOption("stop", "Zastavit", "zastavit"),
                    )
                ),
            ),
        ) { block ->
            val audio = context.getSystemService(AudioManager::class.java) ?: throw BlockException(tr("zvuk není dostupný"))
            val key = when (choice(block, "action")) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            BlockResult.None
        },

        BlockDef(
            type = "brightness",
            summary = "Jas displeje {mode} {level}",
            title = "Nastavit jas",
            description = "Nastaví jas displeje na zvolenou úroveň, nebo ho vrátí na automatický.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.BrightnessMedium,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Jas", listOf(
                        ChoiceOption("manual", "Nastavit úroveň", "na"),
                        ChoiceOption("auto", "Automatický", "automaticky"),
                    )
                ),
                ParamDef.Number("level", "Úroveň", 0f, 100f, 50f, step = 5f, unit = "%").apply {
                    hiddenWhen = { (it["mode"] as? ParamValue.Choice)?.value == "auto" }
                },
            ),
            requirement = Requirement.WriteSettings,
        ) { block ->
            if (!Settings.System.canWrite(context)) {
                throw BlockException(tr("chybí povolení měnit nastavení systému"), Requirement.WriteSettings)
            }
            val resolver = context.contentResolver
            val auto = choice(block, "mode") == "auto"
            Settings.System.putInt(
                resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            if (!auto) {
                val value = (number(block, "level") / 100f * 255f).roundToInt().coerceIn(1, 255)
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            }
            BlockResult.None
        },

        BlockDef(
            type = "dnd",
            summary = "Nerušit: {mode}",
            title = "Režim Nerušit",
            description = "Zapne nebo vypne Nerušit. Co přesně projde v režimu „jen prioritní“, určuje nastavení telefonu.",
            category = BlockCategory.Device,
            icon = Icons.Rounded.DoNotDisturbOn,
            params = listOf(
                ParamDef.Choice(
                    "mode", "Režim", listOf(
                        ChoiceOption("off", "Vypnout (vše projde)", "vypnout"),
                        ChoiceOption("priority", "Jen prioritní", "jen prioritní"),
                        ChoiceOption("alarms", "Jen budíky", "jen budíky"),
                        ChoiceOption("none", "Úplné ticho", "úplné ticho"),
                    )
                ),
            ),
            requirement = Requirement.NotificationPolicy,
        ) { block ->
            val notifications = context.getSystemService(NotificationManager::class.java)
            if (notifications?.isNotificationPolicyAccessGranted != true) {
                throw BlockException(tr("chybí přístup k režimu Nerušit"), Requirement.NotificationPolicy)
            }
            notifications.setInterruptionFilter(
                when (choice(block, "mode")) {
                    "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                    "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
                    else -> NotificationManager.INTERRUPTION_FILTER_ALL
                }
            )
            BlockResult.None
        },

        // ---------- camera, codes, files ----------
        BlockDef(
            type = "take_photo",
            needsWindow = true,
            summary = "Vyfotit",
            title = "Vyfotit",
            description = "Otevře fotoaparát a vrátí pořízenou fotku. Dál ji jde upravit, poslat přes HTTP, uložit " +
                    "nebo z ní blokem Přečíst kód z obrázku dostat QR kód.",
            category = BlockCategory.Images,
            icon = Icons.Rounded.PhotoCamera,
            outputName = "Fotka",
        ) {
            val ui = ui ?: throw BlockException(tr("focení potřebuje okno (spusť akci ručně)"))
            BlockResult.Output(ui.takePhoto() ?: throw RunCancelledException())
        },

        BlockDef(
            type = "qr_create",
            summary = "QR kód z {text}",
            title = "Vytvořit QR kód",
            description = "Udělá z textu nebo odkazu obrázek s QR kódem. Zobraz ho blokem Ukázat obrázky, sdílej nebo ulož.",
            category = BlockCategory.Images,
            icon = Icons.Rounded.QrCode2,
            params = listOf(
                ParamDef.Text("text", "Text nebo odkaz", defaultRef = VarRef.Input),
                ParamDef.Number("size", "Velikost", 200f, 1600f, 800f, step = 100f, unit = "px").apply {
                    hiddenInSummaryWhen = { true }
                },
            ),
            outputName = "QR kód",
        ) { block ->
            val text = text(block, "text")
            if (text.isEmpty()) throw BlockException(tr("není z čeho udělat QR kód"))
            val size = number(block, "size").toInt().coerceIn(100, 2000)
            val bitmap = withContext(Dispatchers.Default) {
                val matrix = try {
                    QRCodeWriter().encode(
                        text, BarcodeFormat.QR_CODE, size, size,
                        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2)
                    )
                } catch (e: Exception) {
                    throw BlockException(tr("text je na QR kód moc dlouhý"))
                }
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
            BlockResult.Output(ImageTools.save(context, bitmap, ImageTools.Format.PNG, 100, "qr"))
        },

        BlockDef(
            type = "qr_read",
            summary = "Přečíst kód z {image}",
            title = "Přečíst kód z obrázku",
            description = "Najde v obrázku QR nebo čárový kód a vrátí jeho obsah. Obrázek dodá Vyfotit, " +
                    "Vybrat soubor / obrázek nebo odkaz https://.",
            category = BlockCategory.Images,
            icon = Icons.Rounded.QrCodeScanner,
            params = listOf(ParamDef.Text("image", "Obrázek", placeholder = "výstup Vyfotit / Vybrat obrázek")),
            outputName = "Obsah kódu",
        ) { block ->
            val bitmap = ImageTools.load(context, text(block, "image")).bitmap
            val found = withContext(Dispatchers.Default) { Codes.read(bitmap) }
                ?: throw BlockException(tr("v obrázku není žádný čitelný kód"))
            BlockResult.Output(found)
        },

        BlockDef(
            type = "read_file",
            summary = "Přečíst {file} jako {format}",
            title = "Přečíst soubor",
            description = "Vrátí obsah souboru: jako text (poznámky, CSV, JSON), nebo zakódovaný do Base64 pro API, " +
                    "která chtějí soubor v těle požadavku.",
            category = BlockCategory.Share,
            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
            params = listOf(
                ParamDef.Text("file", "Soubor", placeholder = "výstup Vybrat soubor, file://… nebo https://…"),
                ParamDef.Choice(
                    "format", "Jako", listOf(
                        ChoiceOption("text", "Text", "text"),
                        ChoiceOption("base64", "Base64", "Base64"),
                    )
                ),
            ),
            outputName = "Obsah souboru",
        ) { block ->
            val source = HttpBlocks.openSource(context, FileTools.local(context, text(block, "file")))
            val base64 = choice(block, "format") == "base64"
            val limit = if (base64) MAX_BASE64_FILE else MAX_TEXT_FILE
            val bytes = withContext(Dispatchers.IO) {
                source.open().use { input ->
                    val data = input.readNBytesCompat(limit + 1)
                    if (data.size > limit) throw BlockException(tr("soubor je moc velký (nejvýš %s MB)", limit / (1024 * 1024)))
                    data
                }
            }
            BlockResult.Output(if (base64) Base64.encodeToString(bytes, Base64.NO_WRAP) else String(bytes, Charsets.UTF_8))
        },
    )

    /** At most [limit] bytes of the stream (InputStream.readNBytes needs Android 13). */
    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/** The phone's speech synthesis; one engine for the whole app, started when first needed. */
object Speech {
    private var engine: TextToSpeech? = null
    private val starting = Mutex()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun engine(context: Context): TextToSpeech = starting.withLock {
        engine?.let { return it }
        val ready = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main) {
            TextToSpeech(context.applicationContext) { status -> ready.complete(status) }
        }
        if (withTimeoutOrNull(10_000) { ready.await() } != TextToSpeech.SUCCESS) {
            created.shutdown()
            throw BlockException(tr("v telefonu není hlas pro čtení (chybí syntéza řeči)"))
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = finished(id)
            override fun onStop(id: String?, interrupted: Boolean) = finished(id)
            override fun onError(id: String?, errorCode: Int) = finished(id)

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) = finished(id)
        })
        engine = created
        created
    }

    private fun finished(id: String?) {
        id?.let { waiting.remove(it) }?.complete(Unit)
    }

    /** Says [text]; with [wait] returns when it has been said. [language] = "cs", "en-GB"…, empty = the phone's. */
    suspend fun say(context: Context, text: String, language: String, wait: Boolean) {
        val tts = engine(context)
        val locale = if (language.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(language.replace('_', '-'))
        val supported = tts.setLanguage(locale)
        if (language.isNotEmpty() && (supported == TextToSpeech.LANG_MISSING_DATA || supported == TextToSpeech.LANG_NOT_SUPPORTED)) {
            throw BlockException(tr("hlas pro jazyk \"%s\" v telefonu není", language))
        }
        // The engine takes a limited length at once; a long text goes in pieces, one after another.
        val pieces = text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceIn(500, 3500))
        var last: CompletableDeferred<Unit>? = null
        for (piece in pieces) {
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            waiting[id] = done
            if (tts.speak(piece, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                waiting.remove(id)
                throw BlockException(tr("text nejde přečíst"))
            }
            last = done
        }
        if (!wait) return
        try {
            last?.await()
        } catch (e: CancellationException) {
            // The run was stopped: stop talking too.
            tts.stop()
            throw e
        }
    }
}

/** Where the phone is, from the system location service (no Google Play services needed). */
object Locator {
    private const val WAIT_MS = 15_000L

    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    /** "50.08,14.42", "50.08 14.42" or the JSON of "Zjistit polohu". */
    fun parse(text: String): Pair<Double, Double>? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val json = JsonTools.parseOrNull(trimmed) as? JsonObject ?: return null
            val lat = JsonTools.asText(json["lat"]).toDoubleOrNull() ?: return null
            val lon = JsonTools.asText(json["lon"]).toDoubleOrNull() ?: return null
            return valid(lat, lon)
        }
        val parts = trimmed.split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        return valid(parts[0].toDoubleOrNull() ?: return null, parts[1].toDoubleOrNull() ?: return null)
    }

    private fun valid(lat: Double, lon: Double) = if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null

    suspend fun current(context: Context): Location {
        if (!DeviceState.hasLocation(context)) throw BlockException(tr("chybí přístup k poloze"), Requirement.Location)
        val manager = context.getSystemService(LocationManager::class.java)
            ?: throw BlockException(tr("poloha není dostupná"))
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) throw BlockException(tr("poloha je v telefonu vypnutá"))

        try {
            val fresh = withTimeoutOrNull(WAIT_MS) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = android.os.CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    LocationManagerCompat.getCurrentLocation(manager, providers.first(), signal, { it.run() }) { found ->
                        if (continuation.isActive) continuation.resume(found)
                    }
                }
            }
            // Nothing fresh in time (indoors, no signal): the last place the phone knew about.
            val found = fresh ?: providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            return found ?: throw BlockException(tr("polohu se nepodařilo zjistit (zkus to venku nebo zapni Wi-Fi)"))
        } catch (e: SecurityException) {
            throw BlockException(tr("chybí přístup k poloze (na pozadí povol polohu „Vždy“)"), Requirement.Location)
        }
    }
}

/** QR and bar codes in pictures. */
object Codes {
    /** Bigger pictures are scaled down first: it is much faster and codes are read better. */
    private const val SCAN_SIDE = 1400

    fun read(bitmap: Bitmap): String? {
        val biggest = maxOf(bitmap.width, bitmap.height)
        val small = if (biggest > SCAN_SIDE) {
            val scale = SCAN_SIDE.toFloat() / biggest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        return decode(small) ?: if (small !== bitmap) decode(bitmap) else null
    }

    private fun decode(bitmap: Bitmap): String? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        // A code printed light on dark is read from the inverted picture.
        for (candidate in listOf(source, source.invert())) {
            try {
                return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
            } catch (_: NotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }
}
