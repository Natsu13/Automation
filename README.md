# Automation

Android aplikace na skládání vlastních automatizací z bloků – něco jako Zkratky z iOS.
Automatizaci poskládáš z kroků (text, schránka, podmínky, zařízení, dialogy…) a spustíš ji
ručně, z výběru textu, přes Sdílet, dlaždicí, změnou schránky nebo systémovou událostí.

Minimální verze: **Android 9** (API 28), cílová: Android 15 (API 35).

## Co umí

**Spouštění**
- ručně z aplikace, zástupcem na ploše nebo dlaždicí v rychlém nastavení
- z nabídky po označení textu a přes systémové Sdílet (výsledek může text rovnou nahradit)
- při změně schránky (vyžaduje povolení přes ADB, viz níže)
- systémovou událostí: Wi-Fi / mobilní data / offline, režim letadlo, nabíječka, baterie pod / nad
  hranicí, zapnutí / vypnutí / odemčení obrazovky, Bluetooth, sluchátka, Nerušit, čas a opakování,
  příchozí oznámení, spuštění telefonu
- **podmínky spuštění** – automatizace se spustí jen když platí (např. „Aplikace oznámení je Chrome“),
  s nabídkou možných hodnot

**Bloky**
- Text: text, nahradit (i regex), velikost písmen, vyčistit, spočítat, vypočítat, najít v textu, převést
  (diakritika, URL, Base64…)
- Schránka: získat, nastavit
- Podmínky a řízení: Pokud / Jinak / Konec, proměnné, počkat, vrátit výsledek
- Dialogy: zeptat se na text, vybrat z nabídky, ukázat text
- Sdílení a web: sdílet, otevřít odkaz, načíst webovou stránku
- Zařízení: hlasitost, režim zvonění, vibrace, svítilna, síť, baterie, orientace, údaje z události
- Ostatní: zpráva, datum a čas, náhodné číslo, **oznámení s tlačítky** (kopírovat, spustit jinou
  automatizaci, otevřít odkaz, sdílet)

**Editor**
- přetahování kroků, sbalené kroky jako čitelná věta, proměnné jako barevné štítky
- zpět / znovu, vypínání jednotlivých kroků
- krokování – po každém kroku vidíš jeho výsledek a hodnoty proměnných
- export / import a sdílení automatizací
- přehled spotřeby baterie a varování, když aplikace na pozadí bere moc

## Sestavení

Projekt se otevře v Android Studiu, nebo z příkazové řádky:

```bash
./gradlew :app:assembleDebug
```

APK najdeš v `app/build/outputs/apk/debug/`. Klíč pro podepsání release verze není součástí repozitáře.

## Instalace mimo Google Play

**Play Protect může instalaci zablokovat** („Aplikace byla za účelem ochrany zařízení zablokována“).
Děje se to u APK staženého z prohlížeče, chatu nebo Disku, protože aplikace umí číst oznámení
(událost „Příchozí oznámení“). Možnosti:

- nainstalovat přes ADB: `adb install app-release.apk`
- nebo dočasně vypnout Obchod Play → Play Protect → ⚙ → *Kontrolovat aplikace pomocí Play Protect*,
  nainstalovat a zase zapnout

Na Androidu 13+ je u aplikace mimo Obchod Play potřeba před povolením přístupu k oznámením otevřít
*Informace o aplikaci → ⋮ → Povolit omezená nastavení*.

## Volitelná oprávnění přes ADB

Některé funkce Android běžné aplikaci nedovolí. Aplikace k nim v nastavení ukazuje návod; příkazy jsou:

Sledování schránky na pozadí (čtení systémového logu a okno přes jiné aplikace):

```bash
adb shell pm grant cz.natsu.automation android.permission.READ_LOGS
adb shell appops set cz.natsu.automation SYSTEM_ALERT_WINDOW allow
```

Přesné měření spotřeby (mAh) v nastavení:

```bash
adb shell pm grant cz.natsu.automation android.permission.DUMP
adb shell pm grant cz.natsu.automation android.permission.PACKAGE_USAGE_STATS
adb shell pm grant cz.natsu.automation android.permission.BATTERY_STATS
```

Bez nich aplikace funguje, jen tyhle dvě věci nejsou k dispozici.

## Technologie

Kotlin, Jetpack Compose (Material 3), Room, kotlinx.serialization.
