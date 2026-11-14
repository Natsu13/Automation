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
    )
}
