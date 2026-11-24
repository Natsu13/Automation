package cz.natsu.automation.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cz.natsu.automation.i18n.tr
import java.text.Normalizer

/** An icon an automation can have. [words] are what it is found by, besides its [key]. */
class IconEntry(val key: String, val icon: ImageVector, val words: String)

/** All icons offered for an automation. The key is what is stored. */
object IconCatalog {
    val all: List<IconEntry> = listOf(
        IconEntry("bolt", Icons.Rounded.Bolt, "blesk akce lightning action"),
        IconEntry("paste", Icons.Rounded.ContentPaste, "schránka vložit clipboard"),
        IconEntry("text", Icons.Rounded.TextFields, "písmo text font"),
        IconEntry("translate", Icons.Rounded.Translate, "překlad jazyk language"),
        IconEntry("link", Icons.Rounded.Link, "odkaz url adresa"),
        IconEntry("volume", Icons.AutoMirrored.Rounded.VolumeUp, "hlasitost zvuk sound"),
        IconEntry("phone", Icons.Rounded.Smartphone, "telefon mobil"),
        IconEntry("star", Icons.Rounded.Star, "hvězda oblíbené favorite"),
        IconEntry("code", Icons.Rounded.Code, "kód skript script"),
        IconEntry("magic", Icons.Rounded.AutoFixHigh, "kouzlo hůlka wand"),
        IconEntry("home", Icons.Rounded.Home, "domů dům house"),
        IconEntry("work", Icons.Rounded.Work, "práce kufřík job"),
        IconEntry("school", Icons.Rounded.School, "škola study"),
        IconEntry("favorite", Icons.Rounded.Favorite, "srdce heart love"),
        IconEntry("alarm", Icons.Rounded.Alarm, "budík"),
        IconEntry("schedule", Icons.Rounded.Schedule, "čas hodiny time clock"),
        IconEntry("timer", Icons.Rounded.Timer, "časovač stopky"),
        IconEntry("hourglass", Icons.Rounded.HourglassTop, "přesýpací hodiny čekat wait"),
        IconEntry("calendar", Icons.Rounded.CalendarMonth, "kalendář datum date"),
        IconEntry("event", Icons.Rounded.Event, "událost"),
        IconEntry("notifications", Icons.Rounded.Notifications, "oznámení zvonek bell"),
        IconEntry("mail", Icons.Rounded.Email, "e-mail pošta email"),
        IconEntry("chat", Icons.Rounded.ChatBubble, "zpráva message sms"),
        IconEntry("call", Icons.Rounded.Call, "volat hovor telefonovat"),
        IconEntry("send", Icons.AutoMirrored.Rounded.Send, "odeslat poslat"),
        IconEntry("share", Icons.Rounded.Share, "sdílet"),
        IconEntry("camera", Icons.Rounded.PhotoCamera, "foto fotoaparát photo"),
        IconEntry("image", Icons.Rounded.Image, "obrázek picture"),
        IconEntry("video", Icons.Rounded.Videocam, "video kamera"),
        IconEntry("music", Icons.Rounded.MusicNote, "hudba nota"),
        IconEntry("headphones", Icons.Rounded.Headphones, "sluchátka"),
        IconEntry("mic", Icons.Rounded.Mic, "mikrofon hlas voice"),
        IconEntry("play", Icons.Rounded.PlayArrow, "přehrát spustit run"),
        IconEntry("pause", Icons.Rounded.Pause, "pauza"),
        IconEntry("repeat", Icons.Rounded.Repeat, "opakovat smyčka loop"),
        IconEntry("shuffle", Icons.Rounded.Shuffle, "náhodně random"),
        IconEntry("sync", Icons.Rounded.Sync, "synchronizace"),
        IconEntry("refresh", Icons.Rounded.Refresh, "obnovit reload"),
        IconEntry("wifi", Icons.Rounded.Wifi, "wi-fi síť network"),
        IconEntry("bluetooth", Icons.Rounded.Bluetooth, "bt"),
        IconEntry("nfc", Icons.Rounded.Nfc, "tag"),
        IconEntry("airplane", Icons.Rounded.AirplanemodeActive, "letadlo režim flight"),
        IconEntry("battery", Icons.Rounded.BatteryFull, "baterie"),
        IconEntry("power", Icons.Rounded.Power, "nabíječka zásuvka charger"),
        IconEntry("flashlight", Icons.Rounded.FlashlightOn, "svítilna baterka torch"),
        IconEntry("vibration", Icons.Rounded.Vibration, "vibrace"),
        IconEntry("dnd", Icons.Rounded.DoNotDisturbOn, "nerušit do not disturb"),
        IconEntry("rotation", Icons.Rounded.ScreenRotation, "otočení obrazovka screen"),
        IconEntry("light", Icons.Rounded.LightMode, "jas slunce den sun brightness"),
        IconEntry("dark", Icons.Rounded.DarkMode, "tma noc měsíc night moon"),
        IconEntry("sleep", Icons.Rounded.Bedtime, "spánek spát"),
        IconEntry("location", Icons.Rounded.LocationOn, "poloha místo gps place"),
        IconEntry("map", Icons.Rounded.Map, "mapa"),
        IconEntry("car", Icons.Rounded.DirectionsCar, "auto"),
        IconEntry("bike", Icons.Rounded.DirectionsBike, "kolo"),
        IconEntry("train", Icons.Rounded.Train, "vlak"),
        IconEntry("flight", Icons.Rounded.Flight, "let letadlo cesta travel"),
        IconEntry("shopping", Icons.Rounded.ShoppingCart, "nákup košík cart"),
        IconEntry("money", Icons.Rounded.AttachMoney, "peníze dolar"),
        IconEntry("card", Icons.Rounded.CreditCard, "karta platba payment"),
        IconEntry("savings", Icons.Rounded.Savings, "úspory prasátko"),
        IconEntry("food", Icons.Rounded.Restaurant, "jídlo restaurace"),
        IconEntry("coffee", Icons.Rounded.Coffee, "káva"),
        IconEntry("fitness", Icons.Rounded.FitnessCenter, "cvičení posilovna sport"),
        IconEntry("health", Icons.Rounded.MonitorHeart, "zdraví tep"),
        IconEntry("pets", Icons.Rounded.Pets, "zvíře pes kočka"),
        IconEntry("cloud", Icons.Rounded.Cloud, "mrak počasí weather"),
        IconEntry("water", Icons.Rounded.WaterDrop, "voda kapka"),
        IconEntry("fire", Icons.Rounded.LocalFireDepartment, "oheň"),
        IconEntry("eco", Icons.Rounded.Eco, "příroda list leaf"),
        IconEntry("lock", Icons.Rounded.Lock, "zámek zamknout"),
        IconEntry("unlock", Icons.Rounded.LockOpen, "odemknout"),
        IconEntry("key", Icons.Rounded.Key, "klíč heslo password"),
        IconEntry("security", Icons.Rounded.Security, "bezpečnost štít shield"),
        IconEntry("visibility", Icons.Rounded.Visibility, "oko vidět eye"),
        IconEntry("search", Icons.Rounded.Search, "hledat lupa"),
        IconEntry("settings", Icons.Rounded.Settings, "nastavení ozubené kolo"),
        IconEntry("tune", Icons.Rounded.Tune, "ladění posuvníky"),
        IconEntry("build", Icons.Rounded.Build, "nástroj oprava tool"),
        IconEntry("bug", Icons.Rounded.BugReport, "chyba brouk"),
        IconEntry("terminal", Icons.Rounded.Terminal, "příkaz konzole"),
        IconEntry("api", Icons.Rounded.Api, "rozhraní"),
        IconEntry("http", Icons.Rounded.Http, "web požadavek request"),
        IconEntry("data", Icons.Rounded.DataObject, "json objekt"),
        IconEntry("storage", Icons.Rounded.Storage, "databáze úložiště"),
        IconEntry("public", Icons.Rounded.Public, "svět internet web globe"),
        IconEntry("rss", Icons.Rounded.RssFeed, "novinky feed"),
        IconEntry("download", Icons.Rounded.Download, "stáhnout"),
        IconEntry("upload", Icons.Rounded.Upload, "nahrát"),
        IconEntry("save", Icons.Rounded.Save, "uložit disketa"),
        IconEntry("folder", Icons.Rounded.Folder, "složka"),
        IconEntry("file", Icons.Rounded.Description, "soubor dokument"),
        IconEntry("note", Icons.Rounded.EditNote, "poznámka zápisek"),
        IconEntry("edit", Icons.Rounded.Edit, "upravit tužka pencil"),
        IconEntry("copy", Icons.Rounded.ContentCopy, "kopírovat"),
        IconEntry("cut", Icons.Rounded.ContentCut, "vyjmout nůžky"),
        IconEntry("delete", Icons.Rounded.Delete, "smazat koš"),
        IconEntry("checklist", Icons.Rounded.Checklist, "seznam úkoly todo"),
        IconEntry("check", Icons.Rounded.CheckCircle, "hotovo ok done"),
        IconEntry("info", Icons.Rounded.Info, "informace"),
        IconEntry("warning", Icons.Rounded.Warning, "varování pozor"),
        IconEntry("question", Icons.Rounded.QuestionMark, "otázka otazník"),
        IconEntry("lightbulb", Icons.Rounded.Lightbulb, "nápad žárovka idea"),
        IconEntry("rocket", Icons.Rounded.RocketLaunch, "raketa start"),
        IconEntry("game", Icons.Rounded.SportsEsports, "hra ovladač"),
        IconEntry("tv", Icons.Rounded.Tv, "televize"),
        IconEntry("computer", Icons.Rounded.Computer, "počítač notebook"),
        IconEntry("watch", Icons.Rounded.Watch, "hodinky"),
        IconEntry("print", Icons.Rounded.Print, "tisk tiskárna"),
        IconEntry("qr", Icons.Rounded.QrCode, "kód qr"),
        IconEntry("numbers", Icons.Rounded.Numbers, "čísla"),
        IconEntry("calculate", Icons.Rounded.Calculate, "kalkulačka výpočet"),
        IconEntry("person", Icons.Rounded.Person, "osoba člověk"),
        IconEntry("group", Icons.Rounded.Group, "lidé skupina people"),
        IconEntry("face", Icons.Rounded.Face, "obličej"),
        IconEntry("thumb", Icons.Rounded.ThumbUp, "palec like"),
        IconEntry("flag", Icons.Rounded.Flag, "vlajka"),
        IconEntry("bookmark", Icons.Rounded.Bookmark, "záložka"),
        IconEntry("tag", Icons.Rounded.Sell, "štítek cena label"),
        IconEntry("pin", Icons.Rounded.PushPin, "připínáček"),
        IconEntry("palette", Icons.Rounded.Palette, "barvy color"),
        IconEntry("brush", Icons.Rounded.Brush, "štětec malovat"),
        IconEntry("crop", Icons.Rounded.Crop, "oříznout"),
        IconEntry("filter", Icons.Rounded.FilterAlt, "filtr"),
    )

    private val byKey = all.associateBy { it.key }

    fun icon(key: String): ImageVector = byKey[key]?.icon ?: Icons.Rounded.Bolt

    /** The icon with this key, or null when the catalogue has none. */
    fun find(key: String): ImageVector? = byKey[key.trim()]?.icon

    /** Icons whose key or words contain [query]; diacritics and case do not matter. */
    fun search(query: String): List<IconEntry> {
        val wanted = fold(query.trim())
        if (wanted.isEmpty()) return all
        return all.filter { fold(it.key + " " + it.words).contains(wanted) }
    }

    private fun fold(text: String) =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

/**
 * The button next to a text field that holds the key of an icon: shows that icon (a generic
 * one while the text is not a known icon) and opens a search through all icons.
 */
@Composable
fun IconFieldButton(key: String, onPick: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val known = IconCatalog.find(key)
    androidx.compose.material3.FilledTonalIconButton(onClick = { picking = true }) {
        Icon(known ?: Icons.Rounded.Interests, tr("Vybrat ikonu"))
    }
    if (picking) IconSearchDialog(key.trim(), onDismiss = { picking = false }) {
        picking = false
        onPick(it)
    }
}

@Composable
private fun IconSearchDialog(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = remember(query) { IconCatalog.search(query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ikona")) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(tr("např. dům, hudba, wifi")) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (found.isEmpty()) Text(tr("Nic nenalezeno."), Modifier.padding(top = 16.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(52.dp),
                    modifier = Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp),
                ) {
                    items(found, key = { it.key }) { entry ->
                        FilledIconToggleButton(
                            checked = entry.key == selected,
                            onCheckedChange = { onPick(entry.key) },
                            shape = CircleShape,
                            modifier = Modifier.padding(2.dp),
                        ) { Icon(entry.icon, contentDescription = entry.key) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Zavřít")) } },
    )
}

/**
 * Icon choice: two rows that scroll sideways (the chosen icon first) and a search
 * through all icons.
 */
@Composable
fun IconPicker(selected: String, onPick: (String) -> Unit) {
    var searching by remember { mutableStateOf(false) }
    val ordered = remember(selected) {
        IconCatalog.all.sortedByDescending { it.key == selected }
    }
    Column {
        LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(104.dp)) {
            items(ordered, key = { it.key }) { entry ->
                FilledIconToggleButton(
                    checked = entry.key == selected,
                    onCheckedChange = { onPick(entry.key) },
                    shape = CircleShape,
                    modifier = Modifier.padding(2.dp),
                ) { Icon(entry.icon, contentDescription = entry.key) }
            }
        }
        OutlinedButton(onClick = { searching = true }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tr("Hledat ikonu") + " (${IconCatalog.all.size})")
        }
    }
    if (searching) {
        var query by remember { mutableStateOf("") }
        val found = remember(query) { IconCatalog.search(query) }
        AlertDialog(
            onDismissRequest = { searching = false },
            title = { Text(tr("Ikona")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(tr("např. dům, hudba, wifi")) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (found.isEmpty()) Text(tr("Nic nenalezeno."), Modifier.padding(top = 16.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(52.dp),
                        modifier = Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp),
                    ) {
                        items(found, key = { it.key }) { entry ->
                            FilledIconToggleButton(
                                checked = entry.key == selected,
                                onCheckedChange = {
                                    onPick(entry.key)
                                    searching = false
                                },
                                shape = CircleShape,
                                modifier = Modifier.padding(2.dp),
                            ) { Icon(entry.icon, contentDescription = entry.key) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { searching = false }) { Text(tr("Zavřít")) } },
        )
    }
}
