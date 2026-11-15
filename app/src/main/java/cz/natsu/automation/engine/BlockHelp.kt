package cz.natsu.automation.engine

/**
 * Longer help shown by the (?) button on a block card – what the block can do,
 * with examples. Only blocks that need more than their one-line description.
 */
object BlockHelp {
    fun of(type: String): String? = texts[type]

    private val texts = mapOf(
        "math" to """
            Jednoduchý zápis: dvě čísla a operace (+ − × ÷, zbytek po dělení).

            Expert – výraz zapsaný textem. Čísla můžeš vkládat z proměnných.

            Operace
            • + − sčítání, odčítání
            • * nebo × násobení, / nebo : dělení
            • % zbytek po dělení (10 % 3 = 1)
            • ^ mocnina (2 ^ 10 = 1024)
            • závorky ( ) určují pořadí

            Funkce (argumenty odděluj středníkem ;)
            • round(x) a round(x; míst) – zaokrouhlení
            • floor(x) dolů, ceil(x) nahoru
            • abs(x) absolutní hodnota, sqrt(x) odmocnina
            • min(a; b; …), max(a; b; …)

            Čísla
            • desetinná čárka i tečka: 1,5 = 1.5
            • mezery mezi tisíci se ignorují: 1 000 000

            Příklady
            • cena * 1,21 → cena s DPH
            • round(součet / počet; 2) → průměr na 2 místa
            • max(0; zůstatek - platba)
            • (teplota * 9 / 5) + 32 → °C na °F
        """.trimIndent(),

        "json_get" to """
            Cesta říká, kde v JSON hodnota je.

            • name → hodnota klíče name
            • user.address.city → vnořené objekty
            • items[0] → první položka pole, items[-1] poslední
            • items[*].url → url ze všech položek (vrátí seznam)
            • ["klíč s mezerou"] → klíč se zvláštními znaky
            • prázdná cesta → celý JSON

            Objekty a pole se vrací jako JSON, takže je jde dát dalšímu bloku
            Získat z JSON, Pro každou položku nebo Práce se seznamem.

            U odpovědi HTTP požadavku je tělo pod body: body.items[0].title,
            stavový kód je status.
        """.trimIndent(),

        "json_build" to """
            Každý řádek je klíč: hodnota.

            • title: Ahoj → {"title": "Ahoj"}
            • user.id: 42 → vnořený objekt {"user": {"id": 42}}
            • tags: ["a", "b"] → pole
            • hotovo: true → true / false

            S přepínačem „Čísla a true / false jako hodnoty“ se 42 uloží jako číslo,
            bez něj jako text "42".

            Pole z řádků: každý řádek je jedna položka.
        """.trimIndent(),

        "http_request" to """
            Výsledek je objekt:
            {
              "status": 200,
              "ok": true,
              "headers": { "content-type": … },
              "body": … (JSON jako JSON, jinak text),
              "file": "file://…" (jen při uložení do souboru)
            }
            Hodnoty z něj vytáhneš blokem Získat z JSON: status, body.items[0].url…

            Hlavičky: každá na řádek, Authorization: Bearer 🔑token
            (klíč vlož jako tajnou hodnotu, do exportu se nedostane).

            Tělo
            • JSON – text JSON nebo výstup bloku Vytvořit JSON
            • Formulář – řádky klíč=hodnota
            • Multipart – klíč=hodnota, soubor jako foto=@výstup bloku Vybrat soubor
            • Celý soubor – pošle soubor jako tělo

            Při chybě 4xx / 5xx akce skončí, pokud nevypneš „Chyba, když server vrátí…“
            nebo krok nedáš do bloku Zkusit.
        """.trimIndent(),

        "try" to """
            Když některý krok uvnitř selže (síť, API, neplatný JSON…), akce neskončí.

            • Pokusů celkem – kolikrát kroky zkusit, než se to vzdá
            • Pauza – kolik sekund počkat před dalším pokusem
            • Když selže – kroky, které se spustí po posledním neúspěšném pokusu
            • výstup bloku Zkusit je text chyby (prázdný, když vše prošlo)

            Zkusit jde vnořovat i dávat do opakování.
        """.trimIndent(),

        "for_each" to """
            Seznam může být JSON pole ["a", "b"] nebo text s jednou položkou na řádek.

            Výstup bloku je aktuální položka. U pole objektů je to celý objekt –
            jeho části vytáhneš blokem Získat z JSON.

            Kolikáté kolo běží, zjistíš blokem Pořadí v opakování. Opakování
            ukončíš nebo přeskočíš blokem Ukončit / přeskočit kolo.
        """.trimIndent(),

        "list" to """
            Seznam je JSON pole nebo text s jednou položkou na řádek.
            Výsledný seznam je vždy JSON pole.

            • Položka na pozici – počítá se od 1, -1 je poslední
            • Rozdělit text – podle oddělovače (prázdný = nový řádek)
            • Součet, průměr, minimum, maximum – jen pro čísla
            • Seřadit – čísla podle hodnoty, text abecedně
        """.trimIndent(),

        "store_set" to """
            Hodnota zůstane uložená i po skončení akce, příští spuštění ji načte
            blokem Načíst hodnotu.

            • Jen pro tuto automatizaci (výchozí) – každá automatizace má svoje,
              názvy jako last_state se nepřepisují
            • Sdílená – jedna hodnota pro všechny automatizace

            Uložené hodnoty vidíš a můžeš upravit v nastavení automatizace
            (sdílené v Nastavení aplikace).
        """.trimIndent(),

        "type_of" to """
            Vrátí, co je hodnota zač:
            • číslo – 42, -1,5, 1e3
            • text – cokoli jiného
            • objekt – JSON {…}
            • seznam – JSON pole […]
            • ano/ne – true nebo false
            • prázdné – nic, mezery nebo null

            Hodí se do podmínky Pokud: Pokud typ je rovno „seznam“…
        """.trimIndent(),

        "script" to SCRIPT,
        "if" to CONDITION,
        "else_if" to CONDITION,
        "while" to CONDITION,

        "send_intent" to """
            Intent je zpráva pro jinou aplikaci. Co přesně poslat, najdeš v dokumentaci
            té aplikace (hledej „intent“, „Tasker“ nebo „automation“).

            Co udělat
            • Otevřít obrazovku – konkrétní okno aplikace (activity)
            • Spustit službu – práce na pozadí (service); aplikace ji musí dovolit
              spouštět zvenku (exported), jinak Android vrátí chybu
            • Spustit službu na popředí – když Android obyčejné spuštění nepustí
            • Poslat broadcast – zpráva, na kterou aplikace čeká

            Pole
            • Balíček – com.example.app (jde vybrat ze seznamu)
            • Třída – celé jméno, nebo zkráceně .SyncService
            • Akce – např. android.intent.action.VIEW
            • Data – adresa: https://…, tel:123, spotify:track:…

            Parametry (extras), jeden na řádek
            • query=ahoj → text
            • count=5 → celé číslo, enabled=true → ano/ne, ratio=1.5 → desetinné
            • typ jde vynutit: id:long=5, name:string=123, x:float=1.5,
              y:double=2.5, n:int=1, on:bool=true

            Příklady
            • Otevřít obrazovku, akce android.intent.action.VIEW,
              data geo:0,0?q=Praha → mapa
            • Poslat broadcast, balíček aplikace, akce z její dokumentace
        """.trimIndent(),
    )

    private val CONDITION get() = """
        Jednoduchý zápis: hodnota, podmínka a s čím porovnat.

        Výraz – podmínka zapsaná textem, když potřebuješ víc věcí najednou.
        Proměnné vkládáš tlačítkem { } nebo píšeš jménem (ty z bloku
        Nastavit proměnnou).

        • porovnání: == != < <= > >=
        • spojení: a zaroven b, a nebo b, not a (také and / or / && / ||)
        • čísla se porovnávají jako čísla: "10" > 9 platí
        • texty v uvozovkách: stav == "ok"
        • funkce: contains(text, "x"), len(seznam) > 0, starts(url, "https")

        Příklady
        • baterie < 20 zaroven not nabiji
        • stav == "vip" nebo cena > 500
        • len(odpoved.body.items) == 0

        Větve: v nabídce ⋮ přidáš Jinak pokud (další podmínka) a Jinak.
        Celý jazyk popisuje nápověda bloku Skript.
    """.trimIndent()

    private val SCRIPT get() = """
        Krátký program. Výsledek bloku je hodnota za vrat, nebo výsledek
        posledního řádku. Proměnné jsou společné s blokem Nastavit proměnnou:
        co skript nastaví, vidí další kroky, a naopak. Hodnoty z jiných kroků
        vložíš tlačítkem { }, vstup automatizace je vstup.

        Hodnoty
        • čísla 12 a 1.5 (desetinná tečka), texty "ahoj" nebo 'ahoj'
        • true / false, nic (prázdná hodnota)
        • seznam [1, 2, 3], objekt {jmeno: "Jan", vek: 30}
        • text s JSON (třeba odpověď API) se chová jako seznam / objekt

        Operace
        • + - * / % (zbytek) ^ (mocnina), závorky
        • & spojí texty: "Ahoj " & jmeno
        • == != < <= > >=
        • zaroven, nebo, not (také and, or, &&, ||, !)

        Příkazy (anglicky i česky)
        • x = 5, x += 1, seznam[0] = "a", osoba.vek = 31
        • pokud podmínka … jinak pokud … jinak … konec
        • dokud podmínka … konec
        • pro polozka v seznam … konec
        • ukonci (konec opakování), pokracuj (další kolo)
        • vrat hodnota
        • # poznámka do konce řádku

        Čtení z dat
        • seznam[0] první, seznam[-1] poslední
        • odpoved.body.items[0].title

        Funkce – text
        • len(x), upper(x), lower(x), trim(x)
        • replace(text, co, cim), contains(text, co)
        • starts(text, zacatek), ends(text, konec)
        • split(text, oddelovac), join(seznam, oddelovac)
        • substr(text, od, delka), find(text, co)
        • match(text, regex), matches(text, regex)

        Funkce – čísla
        • round(x, mist), floor(x), ceil(x), abs(x), sqrt(x)
        • min(…), max(…), sum(seznam), avg(seznam)
        • num(text), int(x), random(od, do)
        • calc("1 000 * 1,21") – kalkulačka bloku Vypočítat

        Funkce – seznamy a ostatní
        • range(5) → 1 až 5, range(2, 4)
        • push(seznam, x), keys(objekt), values(objekt)
        • json(text), tojson(hodnota), type(x)
        • kdyz(podminka, a, b), date("d. M. yyyy"), now()
        • var("název s mezerou") – proměnná, která nejde napsat jménem

        Příklad
        soucet = 0
        pro p v odpoved.body.items
            pokud p.price > 100
                soucet += p.price
            konec
        konec
        vrat "Celkem " & round(soucet, 2) & " Kč"
    """.trimIndent()
}
