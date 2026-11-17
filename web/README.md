# Automation Studio – webový editor

Editor automatizací pro aplikaci Automation v prohlížeči. Bez sestavování, bez serveru na pozadí –
statické soubory, data se ukládají v `localStorage` prohlížeče.

## Spuštění

```bash
python -m http.server 8765 --directory web
```

Pak otevři http://localhost:8765 (funguje i přímo `web/index.html` ze souboru).

## Co umí

- **Bloky** – karty jako v aplikaci: souhrn s hodnotami, rozbalení na parametry, proměnné jako „čipy“,
  přetahování (skupina Pokud/Opakovat se přesouvá celá), vypnutí kroku, duplikace, nápověda.
- **Knihovna** – všechny bloky z aplikace podle kategorií, hledání, klik = přidat za vybraný krok, táhnout = vložit.
- **Spuštění** – ruční spuštění (označený text, sdílení, dlaždice, schránka), události s parametry, podmínky spuštění.
- **Kód** – stejný skript jako „Zobrazit jako skript“ v aplikaci (jazyk bloku Skript), znak po znaku.
  Jde i upravovat: úpravy se průběžně promítají zpět do bloků.
- **JSON** – přesně ten formát, který aplikace ukládá a exportuje.
- **Import / export** – soubory `.automation.json` z aplikace (Sdílet → Export) a zpět (Import v aplikaci),
  i přetažením souboru do okna; import skriptu zkopírovaného z aplikace.
- Ukázky z aplikace, zpět/znovu (Ctrl+Z / Ctrl+Y), CZ/EN, světlý/tmavý vzhled, mobilní rozložení.

Klávesy v Blocích: ↑/↓ výběr, Enter rozbalit, Alt+↑/↓ posunout, Ctrl+D duplikovat, Delete smazat.

## Kód

`script.js` je přepis `ScriptWriter` z `ui/editor/TextView.kt` – pro všechny ukázky dává stejný text jako
aplikace (CZ i EN). Zpětné čtení:

- `jmeno = blok(…)`, `block("replace", …)`, `if / else if / else / end`, `for x in …`, `while`, `return`,
  `# Vyber podle …`, `# Zkusit (ve skriptu jen jako bloky)` … → odpovídající bloky;
- zakomentovaný krok (`# toast(…)`) → vypnutý krok, ostatní komentáře → blok Poznámka;
- cokoli jiného v jazyce skriptu → blok Skript (nezměněný blok Skript se pozná a zůstane celý);
- co text neukazuje (skryté parametry, nastavení Zkusit a souběžných smyček, id kroků), se převezme
  z bloků, ze kterých text vznikl.

Když se změní `ScriptWriter` v aplikaci, je potřeba stejně upravit `script.js`.

## Návod pro AI (`ai-guide.md`)

Návod, podle kterého AI model (ChatGPT, Claude…) napíše automatizaci na přání. Na webu je na
`…/ai-guide.md`; v editoru tlačítko **AI → Zkopírovat návod pro AI** ho zkopíruje i s připraveným zadáním.

- Jak automatizaci předat uživateli (editor → Import → Export → appka „Importovat ze souboru“),
  co povolit a jak ji spustit.
- Formát: skript jako „Zobrazit jako skript“ + hlavička pro spuštění:

  ```
  # @name "Ranní počasí"
  # @icon light
  # @triggers share, process_text, tile, clipboard
  # @event time_daily(time: "07:15", days: "12345")
  # @conditions all
  # @condition launch_condition({source: "wifi_name", op: "equals", right: "Domov"})
  ```

  V bloku Skript jsou řádky `# @` jen komentáře; editor i appka z nich při importu nastaví spuštění.
- Skript jde naimportovat přímo v appce: **⋮ → Vložit skript** (předvyplní se ze schránky) nebo
  **⋮ → Importovat ze souboru** se souborem `.txt`. Čte ho `engine/ScriptReader.kt` – stejná pravidla
  jako `web/script.js`, změny je potřeba dělat v obou.
- Reference všech bloků (signatura, parametry, výchozí hodnoty, povolené hodnoty, kdy se parametr
  používá, co vyžaduje), události, hodnoty událostí, jazyk Skriptu a ukázky z appky napsané jejím
  vlastním `ScriptWriter`.

Generuje ho `AiGuideExport.kt` (JVM test) spolu s katalogem.

## Katalog bloků

`catalog.js` se generuje z kódu aplikace (bloky, parametry, výchozí hodnoty, pravidla skrývání parametrů,
události, ikony, ukázky, nápověda, CZ/EN). Po změně bloků v aplikaci ho přegeneruj:

```bash
./gradlew :app:testDebugUnitTest --tests "*WebCatalogExport*" --tests "*AiGuideExport*"
```

Oba soubory se přegenerují i při každém běhu unit testů (`./gradlew test`) a znovu při nasazení
na GitHub Pages (workflow `pages.yml`), takže web vždy odpovídá blokům v `master`.
