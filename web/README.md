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

## Katalog bloků

`catalog.js` se generuje z kódu aplikace (bloky, parametry, výchozí hodnoty, pravidla skrývání parametrů,
události, ikony, ukázky, nápověda, CZ/EN). Po změně bloků v aplikaci ho přegeneruj:

```bash
./gradlew :app:testDebugUnitTest --tests "*WebCatalogExport*"
```
