(function () {
  "use strict";
  const M = window.Model, Script = window.Script, C = window.CATALOG;
  const STORE_KEY = "automation-studio:v1";

  // ---------- texts of the editor itself ----------
  const UI = {
    automations: ["Automatizace", "Automations"],
    newAutomation: ["Nová automatizace", "New automation"],
    import: ["Import", "Import"],
    export: ["Export", "Export"],
    examples: ["Ukázky", "Examples"],
    undo: ["Zpět (Ctrl+Z)", "Undo (Ctrl+Z)"],
    redo: ["Znovu (Ctrl+Y)", "Redo (Ctrl+Y)"],
    theme: ["Světlý / tmavý vzhled", "Light / dark theme"],
    library: ["Knihovna bloků", "Block library"],
    searchBlocks: ["Hledat blok…", "Search blocks…"],
    all: ["Vše", "All"],
    blocks: ["Bloky", "Blocks"],
    code: ["Kód", "Code"],
    json: ["JSON", "JSON"],
    start: ["Spuštění", "How it starts"],
    manual: ["Ruční spuštění", "Manual start"],
    processText: ["Označený text", "Selected text"],
    processTextD: ["Z nabídky po označení textu", "From the text selection menu"],
    share: ["Sdílení", "Share"],
    shareD: ["Z nabídky Sdílet", "From the Share sheet"],
    tile: ["Dlaždice", "Quick tile"],
    tileD: ["V rychlém nastavení", "In quick settings"],
    clipboardT: ["Kopírování", "Clipboard copy"],
    clipboardTD: ["Když něco zkopíruješ", "When you copy something"],
    events: ["Události", "Events"],
    addEvent: ["Přidat událost", "Add event"],
    conditions: ["Podmínky spuštění", "Launch conditions"],
    addCondition: ["Přidat podmínku", "Add condition"],
    condAll: ["Musí platit všechny", "All must hold"],
    condAny: ["Stačí jedna", "Any one is enough"],
    steps: ["kroků", "steps"],
    structureOk: ["Struktura v pořádku", "Structure OK"],
    structureBad: ["Neuzavřené nebo nespárované bloky", "Unclosed or unpaired blocks"],
    emptyTitle: ["Zatím žádné kroky", "No steps yet"],
    emptyText: ["Přetáhni sem blok z knihovny nebo na něj klikni.", "Drag a block here from the library or click it."],
    expandAll: ["Rozbalit vše", "Expand all"],
    collapseAll: ["Sbalit vše", "Collapse all"],
    help: ["Nápověda", "Help"],
    disable: ["Vypnout krok", "Switch off"],
    enable: ["Zapnout krok", "Switch on"],
    duplicate: ["Duplikovat", "Duplicate"],
    remove: ["Odstranit", "Remove"],
    moveUp: ["Posunout nahoru", "Move up"],
    moveDown: ["Posunout dolů", "Move down"],
    more: ["Další", "More"],
    addElse: ["Přidat větev Jinak", "Add Otherwise branch"],
    addElseIf: ["Přidat Jinak pokud", "Add Otherwise if"],
    addCase: ["Přidat případ", "Add case"],
    addBranch: ["Přidat souběžnou větev", "Add parallel branch"],
    fold: ["Sbalit skupinu", "Fold the group"],
    unfold: ["Rozbalit skupinu", "Unfold the group"],
    removeBranchD: ["i s kroky větve", "with the branch's steps"],
    removedWith: ["Odstraněno i s vnitřními kroky (%s) – vrátíš přes Zpět", "Removed with the steps inside (%s) – Undo brings them back"],
    insertVar: ["Vložit proměnnou", "Insert variable"],
    basics: ["Základní", "Basics"],
    outputs: ["Výstupy kroků", "Step outputs"],
    variables: ["Proměnné", "Variables"],
    eventValues: ["Hodnoty události", "Event values"],
    secrets: ["Tajné hodnoty", "Secrets"],
    newVar: ["název proměnné", "variable name"],
    newSecret: ["název tajné hodnoty", "secret name"],
    step: ["krok", "step"],
    empty: ["prázdné", "empty"],
    codeOk: ["Kód je platný – změny se ukládají průběžně", "Code is valid – changes apply as you type"],
    codeErr: ["Řádek", "Line"],
    jsonOk: ["JSON je platný – změny se ukládají průběžně", "JSON is valid – changes apply as you type"],
    copy: ["Kopírovat", "Copy"],
    copied: ["Zkopírováno", "Copied"],
    format: ["Formátovat", "Format"],
    exportThis: ["Tuto automatizaci", "This automation"],
    exportThisD: [".automation.json pro aplikaci", ".automation.json for the app"],
    exportAll: ["Všechny automatizace", "All automations"],
    exportAllD: ["Jeden soubor se všemi", "One file with all of them"],
    exportCopy: ["Kopírovat JSON do schránky", "Copy JSON to clipboard"],
    exportCode: ["Stáhnout jako kód", "Download as code"],
    importTitle: ["Import automatizací", "Import automations"],
    importDrop: ["Přetáhni sem soubor .json z aplikace nebo klikni a vyber ho", "Drop a .json file from the app here, or click to choose one"],
    importPaste: ["…nebo sem vlož JSON / kód", "…or paste JSON / code here"],
    importBtn: ["Importovat", "Import"],
    cancel: ["Zrušit", "Cancel"],
    close: ["Zavřít", "Close"],
    imported: ["Importováno automatizací: ", "Automations imported: "],
    importFail: ["Soubor se nepodařilo přečíst – není to export automatizací.", "Could not read the file – it is not an automations export."],
    importFormat: ["Soubor není export z Automatizací.", "The file is not an Automation export."],
    examplesTitle: ["Ukázky z aplikace", "Examples from the app"],
    examplesHint: ["Klikni na ukázku a přidá se jako nová automatizace.", "Click an example to add it as a new automation."],
    added: ["Přidáno: ", "Added: "],
    icon: ["Ikona", "Icon"],
    searchIcons: ["Hledat ikonu…", "Search icons…"],
    deleteAuto: ["Smazat automatizaci", "Delete automation"],
    duplicateAuto: ["Duplikovat automatizaci", "Duplicate automation"],
    confirmDelete: ["Opravdu smazat „%s“? Jde to vrátit přes Zpět.", "Really delete “%s”? Undo brings it back."],
    copySuffix: [" (kopie)", " (copy)"],
    autoId: ["ID automatizace v telefonu", "Automation id on the phone"],
    autoIdHint: ["Číslo automatizace z aplikace; prázdné = žádná", "The automation's number in the app; empty = none"],
    dropFile: ["Pusť soubor pro import", "Drop the file to import"],
    sideNote: ["Ukládá se automaticky v tomto prohlížeči. Do telefonu se dostaneš přes <b>Export</b> → v aplikaci <b>Import</b>.", "Saved automatically in this browser. To get it to the phone use <b>Export</b> → <b>Import</b> in the app."],
    codeHelp: [
      "Stejný zápis jako <b>Zobrazit jako skript</b> v aplikaci (jazyk bloku Skript). Krok s výstupem je <code>jmeno = blok(…)</code>, výstup se dál používá jménem. Hodnoty: <code>input</code> <code>trigger</code> <code>clipboard</code> <code>date()</code>, proměnné jménem, tajné <code>secret(\"nazev\")</code>, text se skládá přes <code>&amp;</code>. Podmínky <code>if</code> / <code>else if</code> / <code>else</code> / <code>end</code>, smyčky <code>for x in …</code>, <code>while</code>. Bloky s názvem funkce: <code>block(\"replace\", …)</code>. Vypnutý krok je zakomentovaný <code>#</code>. Řádek, který není blok, se stane blokem Skript.",
      "The same notation as <b>View as script</b> in the app (the Script block's language). A step with an output is <code>name = block(…)</code> and is used by that name. Values: <code>input</code> <code>trigger</code> <code>clipboard</code> <code>date()</code>, variables by name, secrets <code>secret(\"name\")</code>, text is joined with <code>&amp;</code>. Conditions <code>if</code> / <code>else if</code> / <code>else</code> / <code>end</code>, loops <code>for x in …</code>, <code>while</code>. Blocks named like a function: <code>block(\"replace\", …)</code>. A switched-off step is commented out with <code>#</code>. A line that is not a block becomes a Script block.",
    ],
    libHint: ["Klikni = přidat za vybraný krok · táhni = vložit kamkoli", "Click = add after the selected step · drag = drop anywhere"],
    noResults: ["Nic nenalezeno", "Nothing found"],
    output: ["Výstup", "Output"],
    noEvents: ["Žádné události – automatizace se spouští jen ručně.", "No events – the automation only runs manually."],
    invalidJson: ["Neplatný JSON", "Invalid JSON"],
    days: [["Po", "Út", "St", "Čt", "Pá", "So", "Ne"], ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]],
    time: ["Čas", "Time"], percent: ["Hranice", "Level"], minutes: ["Každých (min)", "Every (min)"], radius: ["Okruh (m)", "Radius (m)"],
    place: ["Místo (šířka, délka nebo adresa)", "Place (lat, lng or address)"],
    nameInCode: ["Jméno výstupu v kódu", "Name of the output in the code"],
    currentDate: ["Aktuální datum", "Current date"],
    inputD: ["Text, se kterým byla akce spuštěna (označený, sdílený nebo zkopírovaný)", "The text the action was started with (selected, shared or copied)"],
    triggerD: ["Jak byla automatizace spuštěna", "How the automation was started"],
    clipboardD: ["Aktuální obsah schránky", "Current clipboard content"],
    dateD: ["Datum a čas spuštění", "Date and time of the start"],
    stepOutput: ["Výstup kroku", "Output of step"],
    variable: ["Proměnná", "Variable"],
    secretD: ["Tajná hodnota – v exportu se neukládá", "Secret – not saved in exports"],
  };

  const HUES = { Text: 250, Numbers: 175, Clipboard: 38, Share: 200, Data: 275, Images: 320, Dialogs: 190, Variables: 100, Control: 22, Device: 150, Apps: 220, Other: 230 };
  const EVENT_GROUP_ICONS = { Network: "wifi", Power: "battery_charging_full", Screen: "smartphone", Devices: "bluetooth", Phone: "sms", Place: "location_on", Time: "schedule", Other: "notifications" };
  const TRIGGER_UI = [
    ["triggerProcessText", "text_select_start", "processText", "processTextD"],
    ["triggerShare", "share", "share", "shareD"],
    ["triggerTile", "toggle_on", "tile", "tileD"],
    ["triggerClipboard", "content_paste", "clipboardT", "clipboardTD"],
  ];

  // ---------- state ----------
  const S = {
    automations: [], current: 0, view: "blocks", lang: "cs", theme: null,
    selected: null, expanded: new Set(), folded: new Set(), libQuery: "", libCat: null, startOpen: true,
  };
  const history = { stack: [], index: -1 };

  const L = () => (S.lang === "en" ? 1 : 0);
  const t = (key, ...args) => {
    let s = UI[key] ? UI[key][L()] : key;
    args.forEach((a, i) => { s = s.replace("%" + (i + 1), a).replace("%s", a); });
    return s;
  };
  const tx = (obj) => (obj ? obj[S.lang] ?? obj.cs : "");
  const cur = () => S.automations[S.current];

  // ---------- tiny DOM helper ----------
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === "class") el.className = v;
      else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
      else if (k.startsWith("on")) el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === "html") el.innerHTML = v;
      else if (k === "dataset") Object.assign(el.dataset, v);
      else if (v === true) el.setAttribute(k, "");
      else el.setAttribute(k, v);
    }
    for (const c of children.flat(Infinity)) {
      if (c == null || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  }
  const icon = (name, cls = "") => h("span", { class: "ms " + cls }, name);
  const $ = (sel) => document.querySelector(sel);

  // ---------- persistence & history ----------
  function save() {
    try {
      localStorage.setItem(STORE_KEY, JSON.stringify({
        automations: S.automations, current: S.current, lang: S.lang, theme: S.theme, view: S.view, startOpen: S.startOpen,
      }));
    } catch (e) { /* storage full or blocked: the editor still works */ }
  }

  function load() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (!raw) return false;
      const data = JSON.parse(raw);
      S.automations = (data.automations || []).map(M.normalizeAutomation);
      S.current = Math.min(data.current || 0, S.automations.length - 1);
      S.lang = data.lang || S.lang;
      S.theme = data.theme || null;
      S.view = data.view || "blocks";
      S.startOpen = data.startOpen !== false;
      return S.automations.length > 0;
    } catch (e) { return false; }
  }

  function snapshot() { return JSON.stringify({ a: S.automations, c: S.current }); }

  function commit() {
    const snap = snapshot();
    if (history.stack[history.index] === snap) return;
    history.stack.length = history.index + 1;
    history.stack.push(snap);
    if (history.stack.length > 150) history.stack.shift();
    history.index = history.stack.length - 1;
    save();
    updateUndo();
  }
  let commitTimer = null;
  function commitSoon() { clearTimeout(commitTimer); commitTimer = setTimeout(commit, 500); save(); }

  function restore(index) {
    clearTimeout(commitTimer);
    const data = JSON.parse(history.stack[index]);
    history.index = index;
    S.automations = data.a;
    S.current = Math.min(data.c, S.automations.length - 1);
    save();
    renderAll();
  }
  function undo() { commit(); if (history.index > 0) restore(history.index - 1); }
  function redo() { if (history.index < history.stack.length - 1) restore(history.index + 1); }
  function updateUndo() {
    $("#btn-undo").disabled = history.index <= 0;
    $("#btn-redo").disabled = history.index >= history.stack.length - 1;
  }

  /** A structural change: record, save and draw again. */
  function changed(opts = {}) {
    commit();
    if (opts.sidebar !== false) renderSidebar();
    renderMain();
  }

  // ---------- toasts, popovers, modals ----------
  function toast(text, kind = "") {
    const el = h("div", { class: "toast " + kind }, icon(kind === "err" ? "error" : "check_circle", "small"), text);
    $("#toasts").append(el);
    setTimeout(() => el.remove(), 2600);
  }

  let openPopover = null;
  let ignoreScroll = 0; // until then a scroll does not close the popover (the picker scrolls to a step itself)
  function closePopover() {
    if (openPopover) { openPopover.remove(); openPopover = null; }
    document.querySelectorAll(".block.source").forEach((el) => el.classList.remove("source"));
  }
  function popover(anchor, content) {
    closePopover();
    const el = h("div", { class: "popover" }, content);
    document.body.append(el);
    const r = anchor.getBoundingClientRect();
    const w = el.offsetWidth, hgt = el.offsetHeight;
    let left = Math.min(r.left, window.innerWidth - w - 12);
    let top = r.bottom + 6;
    if (top + hgt > window.innerHeight - 12) top = Math.max(12, r.top - hgt - 6);
    el.style.left = Math.max(12, left) + "px";
    el.style.top = top + "px";
    openPopover = el;
    return el;
  }
  document.addEventListener("mousedown", (e) => {
    if (openPopover && !openPopover.contains(e.target)) closePopover();
  });
  document.addEventListener("scroll", (e) => {
    if (Date.now() < ignoreScroll) return;
    if (openPopover && !openPopover.contains(e.target)) closePopover();
  }, true);
  window.addEventListener("resize", () => closePopover());

  function popItem(ic, title, desc, onClick, cls = "") {
    return h("button", { class: "pop-item " + cls, onclick: (e) => { e.preventDefault(); closePopover(); onClick(); } },
      icon(ic, "small"), h("div", null, h("div", { class: "t" }, title), desc ? h("div", { class: "d" }, desc) : null));
  }

  function modal(title, body, foot) {
    const back = h("div", { class: "modal-back" });
    const close = () => { back.remove(); document.removeEventListener("keydown", onKey); };
    const onKey = (e) => { if (e.key === "Escape") close(); };
    document.addEventListener("keydown", onKey);
    back.addEventListener("mousedown", (e) => { if (e.target === back) close(); });
    const box = h("div", { class: "modal" },
      h("div", { class: "modal-head" }, h("h2", null, title), h("button", { class: "icon-btn", onclick: close }, icon("close"))),
      h("div", { class: "modal-body" }, typeof body === "function" ? body(close) : body),
      foot ? h("div", { class: "modal-foot" }, typeof foot === "function" ? foot(close) : foot) : null);
    back.append(box);
    $("#overlay-root").append(back);
    return close;
  }

  // ---------- variables (chips) ----------
  function refInfo(ref, automation) {
    const lang = L();
    switch (ref.type) {
      case "input": return { kind: "builtin", icon: "input", label: tx(C.ui["Vstup"]) };
      case "clipboard": return { kind: "builtin", icon: "content_paste", label: tx(C.ui["Schránka"]) };
      case "date": return { kind: "builtin", icon: "calendar_today", label: t("currentDate") };
      case "trigger": return { kind: "builtin", icon: "bolt", label: tx(C.ui["Typ spuštění"]) };
      case "event": return { kind: "event", icon: "sensors", label: tx(M.eventValueDefs.get(ref.key)?.label) || ref.key };
      case "variable": return { kind: "variable", icon: "data_object", label: ref.name };
      case "secret": return { kind: "secret", icon: "key", label: ref.name };
      case "output": {
        const idx = automation.blocks.findIndex((b) => b.id === ref.blockId);
        if (idx < 0) return { kind: "missing", icon: "link_off", label: ["chybí krok", "missing step"][lang] };
        return { kind: "output", icon: "subdirectory_arrow_right", label: outputLabel(automation.blocks, automation.blocks[idx]) };
      }
      default: return { kind: "missing", icon: "help", label: ref.type };
    }
  }

  /** Label of a step's output, numbered when several steps give the same kind of value (like the app). */
  function outputLabel(blocks, block) {
    const def = M.blockDefs.get(block.type);
    if (!def) return "?";
    const same = blocks.filter((b) => M.blockDefs.get(b.type)?.output?.cs === def.output?.cs);
    const name = tx(def.output || def.title);
    return same.length > 1 ? name + " " + (same.indexOf(block) + 1) : name;
  }

  function chip(ref, automation) {
    const info = refInfo(ref, automation);
    return h("span", { class: "chip k-" + info.kind, contenteditable: "false", dataset: { ref: JSON.stringify(ref) } },
      icon(info.icon), info.label);
  }

  /** Lights up the step whose output the pointer is over in the picker. */
  function highlightStep(id) {
    document.querySelectorAll(".block.source").forEach((el) => el.classList.remove("source"));
    if (!id) return;
    const card = document.querySelector(`.blocks .block[data-id="${id}"]`);
    if (!card) return;
    card.classList.add("source");
    const r = card.getBoundingClientRect(), main = $("#main").getBoundingClientRect();
    if (r.bottom < main.top + 60 || r.top > main.bottom - 20) {
      ignoreScroll = Date.now() + 700;
      card.scrollIntoView({ block: "center", behavior: "smooth" });
    }
  }

  /** The values the app offers for a text field of block [index] (EditorViewModel.variablesFor). */
  function varMenu(anchor, index, listName, onPick) {
    const a = cur();
    const content = [];
    const pick = (ref) => () => { highlightStep(null); onPick(ref); };
    content.push(h("div", { class: "pop-group" }, t("basics")));
    for (const [type, desc] of [["input", "inputD"], ["trigger", "triggerD"], ["clipboard", "clipboardD"], ["date", "dateD"]]) {
      const info = refInfo({ type }, a);
      content.push(popItem(info.icon, info.label, t(desc), pick({ type })));
    }
    // Launch conditions run before any step: they get only the values above.
    if (listName === "blocks") {
      const outs = a.blocks.slice(0, index).map((b, i) => [b, i]).filter(([b]) => M.blockDefs.get(b.type)?.output);
      if (outs.length) {
        content.push(h("div", { class: "pop-group" }, t("outputs")));
        for (const [b, i] of outs) {
          const item = popItem("subdirectory_arrow_right", outputLabel(a.blocks, b), t("stepOutput") + " " + (i + 1) + ": " + tx(M.blockDefs.get(b.type).title),
            pick({ type: "output", blockId: b.id }));
          item.addEventListener("mouseenter", () => highlightStep(b.id));
          item.addEventListener("mouseleave", () => highlightStep(null));
          content.push(item);
        }
      }
      // Variables set by earlier steps, and the results a parallel loop collects.
      const variables = new Set();
      for (const b of a.blocks.slice(0, index)) {
        if (b.type === "set_variable" || b.type === "change_variable") { const n = M.richToPlain(b.params.name).trim(); if (n) variables.add(n); }
        if (b.params.parallel?.value === true) { const n = M.richToPlain(b.params.collect).trim(); if (n) variables.add(n); }
      }
      content.push(h("div", { class: "pop-group" }, t("variables")));
      for (const name of variables) content.push(popItem("data_object", name, t("variable"), pick({ type: "variable", name })));
      content.push(newNameInput(t("newVar"), (name) => onPick({ type: "variable", name })));
      // Secrets live on the phone; here the ones the automation already uses, or a new name.
      content.push(h("div", { class: "pop-group" }, t("secrets")));
      for (const name of M.collectNames(a).secrets) content.push(popItem("key", "🔑 " + name, t("secretD"), pick({ type: "secret", name })));
      content.push(newNameInput(t("newSecret"), (name) => onPick({ type: "secret", name })));
    }
    popover(anchor, content);
    openPopover.addEventListener("mouseleave", () => highlightStep(null));
  }

  function newNameInput(placeholder, onPick) {
    const input = h("input", { class: "input", placeholder });
    const go = () => { const v = input.value.trim(); if (v) { closePopover(); onPick(v); } };
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); go(); } });
    return h("div", { class: "pop-input" }, input, h("button", { class: "icon-btn sm", onclick: go }, icon("add")));
  }

  // ---------- rich text field ----------
  function readRich(el) {
    const segments = [];
    const addText = (s) => {
      if (!s) return;
      const last = segments[segments.length - 1];
      if (last && last.type === "text") last.text += s; else segments.push({ type: "text", text: s });
    };
    const walk = (node, isFirst) => {
      for (const child of node.childNodes) {
        if (child.nodeType === 3) addText(child.nodeValue.replace(/\u200b/g, ""));
        else if (child.nodeType === 1) {
          if (child.dataset && child.dataset.ref) segments.push({ type: "var", ref: JSON.parse(child.dataset.ref) });
          else if (child.tagName === "BR") { if (child !== node.lastChild || node !== el) addText("\n"); }
          else if (child.tagName === "DIV" || child.tagName === "P") { if (segments.length) addText("\n"); walk(child); }
          else walk(child);
        }
      }
    };
    walk(el, true);
    return { type: "rich", segments };
  }

  function richField(value, { placeholder, code, plain, automation, index, listName, onChange }) {
    const el = h("div", { class: "rich" + (code ? " code" : ""), contenteditable: "true", spellcheck: code ? "false" : "true", "data-placeholder": placeholder || "" });
    for (const s of value?.segments || []) el.append(s.type === "text" ? document.createTextNode(s.text) : chip(s.ref, automation));
    let range = null;
    const remember = () => {
      const sel = window.getSelection();
      if (sel.rangeCount && el.contains(sel.anchorNode)) range = sel.getRangeAt(0).cloneRange();
    };
    const emit = () => onChange(readRich(el));
    el.addEventListener("keyup", remember);
    el.addEventListener("mouseup", remember);
    el.addEventListener("input", () => { remember(); emit(); });
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter") { e.preventDefault(); document.execCommand("insertText", false, "\n"); }
      if (e.key === "Tab" && code) { e.preventDefault(); document.execCommand("insertText", false, "    "); }
    });
    el.addEventListener("paste", (e) => {
      e.preventDefault();
      document.execCommand("insertText", false, e.clipboardData.getData("text/plain"));
    });
    el.addEventListener("drop", (e) => e.preventDefault());
    const insert = (ref) => {
      el.focus();
      const sel = window.getSelection();
      if (range && el.contains(range.startContainer)) { sel.removeAllRanges(); sel.addRange(range); }
      else { const r = document.createRange(); r.selectNodeContents(el); r.collapse(false); sel.removeAllRanges(); sel.addRange(r); }
      const r = sel.getRangeAt(0);
      r.deleteContents();
      const node = chip(ref, automation);
      r.insertNode(node);
      r.setStartAfter(node); r.collapse(true);
      sel.removeAllRanges(); sel.addRange(r);
      remember();
      emit();
    };
    const wrap = h("div", { class: "rich-wrap" }, el);
    if (!plain) wrap.append(h("button", {
      class: "icon-btn", title: t("insertVar"),
      onmousedown: (e) => e.preventDefault(),
      onclick: (e) => varMenu(e.currentTarget, index, listName, insert),
    }, icon("data_object")));
    wrap.setValue = (v) => { el.textContent = ""; for (const s of v.segments) el.append(s.type === "text" ? document.createTextNode(s.text) : chip(s.ref, automation)); emit(); };
    return wrap;
  }

  // ---------- summary ----------
  function summaryNodes(block, def, automation) {
    const values = {};
    for (const p of def.params) values[p.key] = block.params[p.key] ?? p.default;
    const text = tx(def.summary);
    const out = [];
    let last = 0;
    const re = /\{(\w+)\}/g;
    let m;
    while ((m = re.exec(text))) {
      out.push(text.slice(last, m.index));
      last = m.index + m[0].length;
      const p = def.params.find((x) => x.key === m[1]);
      if (!p || M.isParamHidden(def, p, block.params) || p.noSummary) continue;
      const v = values[p.key];
      if (!v) continue;
      if (v.type === "rich") {
        const segs = v.segments.filter((s) => s.type !== "text" || s.text);
        if (!segs.length) { out.push(h("span", { class: "pill blank" }, t("empty"))); continue; }
        let budget = 48;
        for (const s of segs) {
          if (s.type === "var") out.push(chip(s.ref, automation));
          else if (budget > 0) {
            let shown = s.text.replace(/\s*\n\s*/g, " ⏎ ");
            if (shown.length > budget) shown = shown.slice(0, budget).trimEnd() + "…";
            budget -= shown.length;
            out.push(h("span", { class: "pill" }, shown));
          }
        }
      } else if (v.type === "choice") {
        if (p.kind === "automation") { if (v.value) out.push(h("span", { class: "pill" }, "#" + v.value)); continue; }
        const opt = p.options?.find((o) => o.value === v.value);
        const label = opt ? tx(opt.short) : v.value;
        if (label) out.push(h("span", { class: "pill" }, label));
      } else if (v.type === "number") {
        out.push(h("span", { class: "pill" }, fmtNum(v.value) + (p.unit ? " " + tx(p.unit) : "")));
      } else if (v.type === "bool") {
        if (v.value && p.summaryWhenOn) out.push(tx(p.summaryWhenOn));
      }
    }
    out.push(text.slice(last).trimEnd());
    return out;
  }
  const fmtNum = (n) => (Math.round(n * 1000) / 1000).toLocaleString(S.lang === "en" ? "en" : "cs");

  // ---------- block cards ----------
  function blockCard(block, index, list, structure) {
    const a = cur();
    const blocks = a[list];
    const def = M.blockDefs.get(block.type);
    const kind = M.kindOf(block.type);
    const slim = ["Else", "End", "Branch"].includes(kind) || (kind === "ElseIf" && false);
    const hue = HUES[def?.category] ?? 230;
    const expanded = S.expanded.has(block.id) && def && def.params.length > 0;
    const problem = structure?.problems[index];
    // The name the step's output has in the Kód view.
    const outLabel = list === "blocks" ? structure?.names?.[block.id] : null;

    const card = h("div", {
      class: ["block", slim && "slim", slim && def?.params.length && "with-params", block.disabled && "disabled",
        S.selected === block.id && "selected", expanded && "expanded", problem && "problem",
        list === "blocks" && S.folded.has(block.id) && M.isGroupStart(block.type) && "folded"].filter(Boolean).join(" "),
      style: { "--hue": hue }, dataset: { id: block.id },
    });

    const summary = h("div", { class: "summary" });
    const fillSummary = () => {
      summary.textContent = "";
      if (!def) { summary.append(h("span", { class: "pill blank" }, block.type)); return; }
      summary.append(...summaryNodes(block, def, a));
      if (def.output && list === "blocks") {
        summary.append(h("span", { class: "out-arrow" }, "→"), h("span", { class: "chip k-output" }, icon("subdirectory_arrow_right"), outputLabel(a.blocks, block)));
      }
    };
    fillSummary();

    const act = (ic, title, fn, cls = "") => h("button", {
      class: "icon-btn sm " + cls, title, onclick: (e) => { e.stopPropagation(); fn(e); },
    }, icon(ic));

    // Branch markers and ends belong to their group: no switching off, copying or moving on their own.
    const marker = list === "blocks" && MARKERS.has(kind);
    const groupEnd = list === "blocks" && M.isGroupStart(block.type) ? structure?.endOf[index] : null;
    const foldable = groupEnd != null;
    const folded = foldable && S.folded.has(block.id);
    const actions = kind === "End" ? null : h("div", { class: "b-actions" },
      foldable ? act(folded ? "unfold_more" : "unfold_less", t(folded ? "unfold" : "fold"), () => toggleFold(block.id)) : null,
      def?.help ? act("help", t("help"), () => showHelp(def)) : null,
      !marker ? act(block.disabled ? "visibility_off" : "visibility", block.disabled ? t("enable") : t("disable"), () => {
        block.disabled = !block.disabled; changed({ sidebar: false });
      }, block.disabled ? "active" : "") : null,
      !marker ? act("content_copy", t("duplicate"), () => duplicateBlock(list, index)) : null,
      act("more_vert", t("more"), (e) => blockMenu(e.currentTarget, list, index)),
    );
    const foldBadge = foldable ? h("button", {
      class: "fold-badge" + (folded ? "" : " hidden"), title: t("unfold"),
      onclick: (e) => { e.stopPropagation(); toggleFold(block.id); },
    }, icon("unfold_more", "tiny"), stepCount(innerSteps(blocks, index, groupEnd))) : null;

    const titleText = def ? tx(def.title) : block.type;
    const head = h("div", { class: "block-head", onclick: (e) => {
      if (e.target.closest(".rich, button, input, select")) return;
      const was = S.selected === block.id;
      S.selected = block.id;
      if (def?.params.length) { if (S.expanded.has(block.id) && was) S.expanded.delete(block.id); else S.expanded.add(block.id); }
      renderMain();
    } },
      list === "blocks" ? (marker ? h("span", { class: "handle off" }, icon("drag_indicator", "small"))
        : h("span", { class: "handle", title: "drag" }, icon("drag_indicator", "small"))) : null,
      h("div", { class: "b-icon" }, icon(def?.icon || "help")),
      h("div", { class: "b-main" },
        slim ? h("div", { class: "b-title" }, slim && def?.params.length ? [titleText, " ", ...summaryNodes(block, def, a)] : titleText)
          : [h("div", { class: "b-title" }, list === "blocks" ? h("span", { class: "step-no" }, index + 1 + ".") : null, titleText,
              outLabel ? h("code", { class: "code-name", title: t("nameInCode") }, outLabel) : null,
              def?.requirement ? h("span", { class: "req", title: tx(def.requirement) }, icon("shield", "tiny"), tx(def.requirement)) : null,
              foldBadge),
            summary]),
      actions);
    card.append(head);

    if (expanded) {
      const body = h("div", { class: "b-body" });
      if (def.description) body.append(h("div", { class: "b-desc" }, tx(def.description)));
      const fields = h("div", { style: { display: "grid", gap: "14px" } });
      const drawFields = () => {
        fields.textContent = "";
        for (const p of def.params) {
          if (M.isParamHidden(def, p, block.params)) continue;
          fields.append(paramField(block, p, index, list, () => { fillSummary(); }, () => {
            commit(); drawFields(); fillSummary(); refreshCodeLabels();
          }));
        }
      };
      drawFields();
      body.append(fields);
      card.append(body);
    }
    return card;
  }

  function refreshCodeLabels() { /* the code view is redrawn when it is opened */ }

  function paramField(block, p, index, list, onSoftChange, onHardChange) {
    const value = block.params[p.key] ?? M.clone(p.default);
    const field = h("div", { class: "field" }, h("div", { class: "lbl" }, tx(p.label)));
    const a = cur();
    switch (p.kind) {
      case "text": {
        const rich = richField(value, {
          placeholder: tx(p.placeholder), code: p.code, plain: p.plain, automation: a, index, listName: list,
          onChange: (v) => { block.params[p.key] = v; onSoftChange(); commitSoon(); },
        });
        field.append(rich);
        if (p.suggestions?.length) field.append(h("div", { class: "suggest" }, p.suggestions.map((s) =>
          h("button", { onclick: () => rich.setValue({ type: "rich", segments: [{ type: "text", text: s }] }) }, s))));
        break;
      }
      case "choice": {
        const sel = h("select", { class: "input", onchange: () => { block.params[p.key] = { type: "choice", value: sel.value }; onHardChange(); } },
          p.options.map((o) => h("option", { value: o.value, selected: o.value === value.value }, tx(o.label))));
        if (!p.options.some((o) => o.value === value.value)) sel.prepend(h("option", { value: value.value, selected: true }, value.value));
        field.append(sel);
        break;
      }
      case "number": {
        const num = h("input", { class: "input", type: "number", min: p.min, max: p.max, step: p.step, value: value.value });
        const range = h("input", { type: "range", min: p.min, max: p.max, step: p.step, value: value.value });
        const set = (v) => { const n = parseFloat(v); if (isNaN(n)) return; block.params[p.key] = { type: "number", value: n }; onSoftChange(); commitSoon(); };
        range.addEventListener("input", () => { num.value = range.value; set(range.value); });
        num.addEventListener("input", () => { range.value = num.value; set(num.value); });
        field.append(h("div", { class: "num-row" }, range, num, h("span", { class: "unit" }, tx(p.unit))));
        break;
      }
      case "toggle": {
        const input = h("input", { type: "checkbox", checked: !!value.value, onchange: () => { block.params[p.key] = { type: "bool", value: input.checked }; onHardChange(); } });
        field.textContent = "";
        field.append(h("label", { class: "switch" }, input, h("span", { class: "track" }), h("span", null, tx(p.label))));
        break;
      }
      case "automation": {
        const input = h("input", { class: "input", value: value.value, placeholder: t("autoIdHint"), oninput: () => {
          block.params[p.key] = { type: "choice", value: input.value.trim() }; onSoftChange(); commitSoon();
        } });
        field.querySelector(".lbl").append(h("span", { style: { color: "var(--text-3)", fontWeight: 400 } }, "· " + t("autoId")));
        field.append(input);
        break;
      }
    }
    return field;
  }

  const MARKERS = new Set(["Else", "ElseIf", "Branch", "End"]);

  /** Steps inside a group (its own branch markers and end don't count). */
  function innerSteps(blocks, s, e) {
    let n = 0;
    for (let k = s + 1; k < e; k++) if (!MARKERS.has(M.kindOf(blocks[k].type))) n++;
    return n;
  }

  function stepCount(n) {
    if (S.lang === "en") return n + (n === 1 ? " step" : " steps");
    return n + (n === 1 ? " krok" : n >= 2 && n <= 4 ? " kroky" : " kroků");
  }

  function toggleFold(id) {
    if (S.folded.has(id)) S.folded.delete(id); else S.folded.add(id);
    renderMain();
  }

  function blockMenu(anchor, list, index) {
    const a = cur(), blocks = a[list];
    const clicked = blocks[index];
    const marker = list === "blocks" && MARKERS.has(M.kindOf(clicked.type));
    // A branch marker offers the same "add branch" items as the start of its group.
    const startIndex = marker ? blocks.findIndex((b) => b.groupId === clicked.groupId && M.isGroupStart(b.type)) : index;
    const block = blocks[startIndex] || clicked;
    const def = M.blockDefs.get(block.type);
    const items = [];
    if (list === "blocks" && def && M.isGroupStart(block.type)) {
      const structure = M.analyze(blocks);
      index = startIndex;
      const end = structure.endOf[index];
      const insertBefore = (type) => {
        if (end == null) return;
        blocks.splice(end, 0, M.newBlock(type, block.groupId));
        changed({ sidebar: false });
      };
      const hasElse = blocks.slice(index, end).some((b) => b.groupId === block.groupId && M.kindOf(b.type) === "Else");
      if (def.kind === "IfStart" && block.type === "if") {
        items.push(popItem("alt_route", t("addElseIf"), null, () => {
          const at = blocks.findIndex((b, i) => i > index && i <= end && b.groupId === block.groupId && (M.kindOf(b.type) === "Else" || M.kindOf(b.type) === "End"));
          blocks.splice(at, 0, M.newBlock("else_if", block.groupId)); changed({ sidebar: false });
        }));
        if (!hasElse) items.push(popItem("call_split", t("addElse"), null, () => insertBefore("else")));
      }
      if (block.type === "switch") items.push(popItem("alt_route", t("addCase"), null, () => {
        const at = blocks.findIndex((b, i) => i > index && i <= end && b.groupId === block.groupId && (M.kindOf(b.type) === "Else" || M.kindOf(b.type) === "End"));
        blocks.splice(at, 0, M.newBlock("case", block.groupId)); changed({ sidebar: false });
      }));
      if (def.kind === "TryStart" && !hasElse) items.push(popItem("call_split", tx(M.blockDefs.get("catch").title), null, () => insertBefore("catch")));
      if (def.kind === "ParallelStart") items.push(popItem("call_split", t("addBranch"), null, () => insertBefore("parallel_branch")));
      if (items.length) items.push(h("div", { class: "pop-sep" }));
    }
    const own = blocks.indexOf(clicked);
    const ownDef = M.blockDefs.get(clicked.type);
    if (!marker) {
      items.push(popItem("arrow_upward", t("moveUp"), "Alt+↑", () => moveBlock(list, own, -1)));
      items.push(popItem("arrow_downward", t("moveDown"), "Alt+↓", () => moveBlock(list, own, 1)));
      items.push(popItem("content_copy", t("duplicate"), "Ctrl+D", () => duplicateBlock(list, own)));
    }
    if (ownDef?.help) items.push(popItem("help", t("help"), null, () => showHelp(ownDef)));
    if (items.length) items.push(h("div", { class: "pop-sep" }));
    items.push(popItem("delete", t("remove"), marker ? t("removeBranchD") : "Del", () => removeBlock(list, own), "danger"));
    popover(anchor, items);
  }

  // ---------- block operations ----------
  function remapIds(blocks) {
    const ids = {}, groups = {};
    const copy = M.clone(blocks).map((b) => {
      ids[b.id] = M.uuid();
      if (b.groupId) groups[b.groupId] = groups[b.groupId] || M.uuid();
      return b;
    });
    for (const b of copy) {
      b.id = ids[b.id];
      if (b.groupId) b.groupId = groups[b.groupId];
      for (const v of Object.values(b.params)) if (v && v.type === "rich") for (const s of v.segments) {
        if (s.type === "var" && s.ref.type === "output" && ids[s.ref.blockId]) s.ref.blockId = ids[s.ref.blockId];
      }
    }
    return copy;
  }

  function rangeFor(list, index) {
    const blocks = cur()[list];
    return list === "blocks" ? M.rangeOf(blocks, index) : [index, index];
  }

  function duplicateBlock(list, index) {
    const blocks = cur()[list];
    if (list === "blocks" && MARKERS.has(M.kindOf(blocks[index].type))) return;
    const [s, e] = rangeFor(list, index);
    const copy = remapIds(blocks.slice(s, e + 1));
    blocks.splice(e + 1, 0, ...copy);
    S.selected = copy[0].id;
    changed({ sidebar: false });
  }

  function removeBlock(list, index) {
    const blocks = cur()[list];
    const block = blocks[index];
    const kind = M.kindOf(block.type);
    let [s, e] = rangeFor(list, index);
    if (list === "blocks") {
      // Like the app: an end goes only with its group; a branch marker takes the steps of its branch.
      if (kind === "End") return;
      if (kind === "Else" || kind === "ElseIf" || kind === "Branch") {
        let next = index + 1;
        while (next < blocks.length && !(blocks[next].groupId === block.groupId && MARKERS.has(M.kindOf(blocks[next].type)))) next++;
        e = Math.min(next, blocks.length) - 1;
      }
    }
    let inner = 0;
    for (let k = s; k <= e; k++) if (k !== index && !MARKERS.has(M.kindOf(blocks[k].type))) inner++;
    blocks.splice(s, e - s + 1);
    S.selected = blocks[Math.min(s, blocks.length - 1)]?.id || null;
    changed({ sidebar: false });
    if (inner > 0) toast(t("removedWith", stepCount(inner)));
  }

  /** Moves a step (a group as a whole) over its neighbour; a neighbouring group is jumped over whole. */
  function moveBlock(list, index, dir) {
    const blocks = cur()[list];
    if (list === "blocks" && MARKERS.has(M.kindOf(blocks[index].type))) return;
    const [s, e] = rangeFor(list, index);
    if (dir < 0 && s === 0) return;
    if (dir > 0 && e >= blocks.length - 1) return;
    let size = 1;
    if (list === "blocks") {
      const structure = M.analyze(blocks);
      const n = dir < 0 ? s - 1 : e + 1;
      if (dir < 0 && M.kindOf(blocks[n].type) === "End" && structure.startOf[n] != null) {
        // Only when the whole group lies before us, not when we leave the group we are in.
        const st = structure.startOf[n];
        if (st < s) size = n - st + 1;
      } else if (dir > 0 && M.isGroupStart(blocks[n].type) && structure.endOf[n] != null) {
        size = structure.endOf[n] - n + 1;
      }
    }
    const part = blocks.splice(s, e - s + 1);
    blocks.splice(s + dir * size, 0, ...part);
    changed({ sidebar: false });
  }

  /** Moves blocks [s..e] so they start before [target] (index in the list before the move). */
  function moveRange(s, e, target) {
    const blocks = cur().blocks;
    if (target >= s && target <= e + 1) return;
    const part = blocks.splice(s, e - s + 1);
    const at = target > e ? target - part.length : target;
    blocks.splice(at, 0, ...part);
    changed({ sidebar: false });
  }

  function insertIndex() {
    const blocks = cur().blocks;
    const i = blocks.findIndex((b) => b.id === S.selected);
    if (i < 0) return blocks.length;
    // After a group start the new step goes inside the group, unless the group is folded: then after it.
    if (S.folded.has(blocks[i].id)) {
      const end = M.analyze(blocks).endOf[i];
      if (end != null) return end + 1;
    }
    return i + 1;
  }

  function addBlock(type, at = insertIndex()) {
    const group = M.newBlockGroup(type);
    cur().blocks.splice(at, 0, ...group);
    S.selected = group[0].id;
    if (M.blockDefs.get(type)?.params.length) S.expanded.add(group[0].id);
    changed({ sidebar: false });
    requestAnimationFrame(() => document.querySelector(`.block[data-id="${group[0].id}"]`)?.scrollIntoView({ block: "nearest", behavior: "smooth" }));
  }

  function showHelp(def) {
    modal(tx(def.title), h("div", null,
      h("p", { style: { marginTop: 0, color: "var(--text-2)" } }, tx(def.description)),
      def.help ? h("div", { class: "help-text" }, tx(def.help)) : null,
      h("p", { style: { color: "var(--text-3)", fontSize: "12px" } }, h("code", null, def.type))));
  }

  // ---------- sidebar ----------
  function renderSidebar() {
    const side = $("#sidebar");
    side.textContent = "";
    side.append(h("div", { class: "side-head" },
      h("span", { class: "side-title" }, t("automations") + " · " + S.automations.length),
      h("button", { class: "icon-btn sm", title: t("newAutomation"), onclick: newAutomation }, icon("add"))));
    const list = h("div", { class: "auto-list" });
    S.automations.forEach((a, i) => {
      const ic = M.iconDefs.get(a.icon)?.icon || "bolt";
      const parts = [];
      if (a.events.length) parts.push(a.events.map((e) => tx(M.eventDefs.get(e.type)?.title) || e.type).join(", "));
      else parts.push(a.blocks.length + " " + t("steps"));
      list.append(h("div", { class: "auto-item" + (i === S.current ? " active" : ""), onclick: () => {
        S.current = i; S.selected = null; save(); renderSidebar(); renderMain(); document.body.classList.remove("side-open");
      } },
        h("div", { class: "auto-icon" }, icon(ic, "fill")),
        h("div", { class: "meta" }, h("div", { class: "name" }, a.name || "—"), h("div", { class: "sub" }, parts.join(" · "))),
        h("button", { class: "icon-btn sm", title: t("more"), onclick: (e) => { e.stopPropagation(); autoMenu(e.currentTarget, i); } }, icon("more_horiz"))));
    });
    side.append(list);
    side.append(h("button", { class: "btn dashed", style: { width: "100%", marginTop: "10px", justifyContent: "center" }, onclick: newAutomation }, icon("add"), t("newAutomation")));
    side.append(h("div", { class: "side-foot", html: t("sideNote") }));
  }

  function autoMenu(anchor, i) {
    popover(anchor, [
      popItem("content_copy", t("duplicateAuto"), null, () => duplicateAutomation(i)),
      popItem("download", t("exportThis"), t("exportThisD"), () => download([S.automations[i]])),
      h("div", { class: "pop-sep" }),
      popItem("delete", t("deleteAuto"), null, () => deleteAutomation(i), "danger"),
    ]);
  }

  function newAutomation() {
    S.automations.push(M.newAutomation(t("newAutomation")));
    S.current = S.automations.length - 1;
    S.selected = null;
    changed();
    setTimeout(() => { const n = $(".name-input"); n?.focus(); n?.select(); }, 0);
  }

  function duplicateAutomation(i) {
    const a = M.clone(S.automations[i]);
    a.name += t("copySuffix");
    a.blocks = remapIds(a.blocks);
    a.conditions = remapIds(a.conditions);
    a.events = a.events.map((e) => ({ ...e, id: M.uuid() }));
    S.automations.splice(i + 1, 0, a);
    S.current = i + 1;
    changed();
  }

  function deleteAutomation(i) {
    if (!confirm(t("confirmDelete", S.automations[i].name))) return;
    S.automations.splice(i, 1);
    if (!S.automations.length) S.automations.push(M.newAutomation(t("newAutomation")));
    S.current = Math.min(S.current, S.automations.length - 1);
    changed();
  }

  // ---------- main ----------
  function renderMain() {
    const main = $("#main");
    const scroll = main.scrollTop;
    main.textContent = "";
    const a = cur();
    const inner = h("div", { class: "main-inner" });
    const structure = M.analyze(a.blocks);

    // header
    const ic = M.iconDefs.get(a.icon)?.icon || "bolt";
    const nameInput = h("input", { class: "name-input", value: a.name, oninput: () => {
      a.name = nameInput.value; commitSoon();
      const item = document.querySelectorAll(".auto-item .name")[S.current];
      if (item) item.textContent = a.name || "—";
    } });
    inner.append(h("div", { class: "auto-head" },
      h("button", { class: "big-icon", title: t("icon"), onclick: iconPicker }, icon(ic, "fill")),
      h("div", { style: { flex: 1, minWidth: 0 } }, nameInput,
        h("div", { class: "stats" },
          h("span", { class: "badge neutral" }, icon("stacks", "tiny"), a.blocks.length + " " + t("steps")),
          structure.valid ? h("span", { class: "badge ok" }, icon("check", "tiny"), t("structureOk"))
            : h("span", { class: "badge err" }, icon("error", "tiny"), t("structureBad")))),
      h("button", { class: "icon-btn", title: t("more"), onclick: (e) => autoMenu(e.currentTarget, S.current) }, icon("more_vert"))));

    inner.append(startPanel(a));

    // view switch
    const seg = h("div", { class: "seg" }, [["blocks", "view_agenda"], ["code", "code"], ["json", "data_object"]].map(([v, ic2]) =>
      h("button", { class: S.view === v ? "on" : "", onclick: () => { commit(); S.view = v; save(); renderMain(); } }, icon(ic2), t(v))));
    const bar = h("div", { class: "viewbar" }, seg, h("div", { class: "spacer" }));
    if (S.view === "blocks" && a.blocks.length) {
      bar.append(h("button", { class: "btn ghost small", onclick: () => {
        const all = a.blocks.filter((b) => M.blockDefs.get(b.type)?.params.length).map((b) => b.id);
        const anyClosed = all.some((id) => !S.expanded.has(id));
        all.forEach((id) => (anyClosed ? S.expanded.add(id) : S.expanded.delete(id)));
        renderMain();
      } }, icon("unfold_more", "small"), h("span", null, t(a.blocks.some((b) => M.blockDefs.get(b.type)?.params.length && !S.expanded.has(b.id)) ? "expandAll" : "collapseAll"))));
    }
    inner.append(bar);

    if (S.view === "blocks") inner.append(blocksView(a, structure));
    else if (S.view === "code") inner.append(codeView(a));
    else inner.append(jsonView(a));

    main.append(inner);
    main.scrollTop = scroll;
  }

  function startPanel(a) {
    const panel = h("div", { class: "panel" + (S.startOpen ? "" : " collapsed") });
    const hint = [];
    TRIGGER_UI.forEach(([f, , label]) => { if (a[f]) hint.push(t(label)); });
    a.events.forEach((e) => hint.push(tx(M.eventDefs.get(e.type)?.title) || e.type));
    if (a.conditions.length) hint.push(t("conditions") + " (" + a.conditions.length + ")");
    panel.append(h("div", { class: "panel-head", onclick: () => { S.startOpen = !S.startOpen; save(); panel.classList.toggle("collapsed"); } },
      icon("play_circle"), h("h3", null, t("start")), h("span", { class: "hint" }, hint.join(" · ")), icon("expand_more", "chev")));
    const body = h("div", { class: "panel-body" });

    // manual triggers
    body.append(h("div", null, h("div", { class: "sub-title" }, t("manual")),
      h("div", { class: "trigger-grid" }, TRIGGER_UI.map(([f, ic, label, desc]) =>
        h("button", { class: "trigger" + (a[f] ? " on" : ""), onclick: () => { a[f] = !a[f]; changed(); } },
          icon(ic), h("div", null, h("div", { class: "t" }, t(label)), h("div", { class: "d" }, t(desc))),
          h("span", { class: "check" }, a[f] ? icon("check") : null))))));

    // events
    const events = h("div", { class: "event-list" });
    if (!a.events.length) events.append(h("div", { style: { color: "var(--text-3)", fontSize: "13px" } }, t("noEvents")));
    a.events.forEach((e, i) => events.append(eventCard(a, e, i)));
    body.append(h("div", null,
      h("div", { class: "sub-title" }, t("events")), events,
      h("button", { class: "btn dashed small", style: { marginTop: "8px" }, onclick: (ev) => eventMenu(ev.currentTarget) }, icon("add", "small"), t("addEvent"))));

    // launch conditions
    const conds = h("div", { class: "cond-list" });
    a.conditions.forEach((c, i) => conds.append(blockCard(c, i, "conditions", null)));
    const condHead = h("div", { class: "sub-title" }, t("conditions"));
    if (a.conditions.length > 1) {
      condHead.append(h("div", { class: "seg", style: { marginLeft: "auto", textTransform: "none", letterSpacing: 0 } },
        h("button", { class: a.conditionsAny ? "" : "on", onclick: () => { a.conditionsAny = false; changed(); } }, t("condAll")),
        h("button", { class: a.conditionsAny ? "on" : "", onclick: () => { a.conditionsAny = true; changed(); } }, t("condAny"))));
    }
    body.append(h("div", null, condHead, conds,
      h("button", { class: "btn dashed small", style: { marginTop: "8px" }, onclick: () => {
        const b = M.newBlock("launch_condition");
        a.conditions.push(b); S.expanded.add(b.id); S.selected = b.id; changed();
      } }, icon("add", "small"), t("addCondition"))));
    panel.append(body);
    return panel;
  }

  function eventCard(a, e, i) {
    const def = M.eventDefs.get(e.type);
    const params = h("div", { class: "event-params" });
    const set = (k, v) => { e.params[k] = String(v); commitSoon(); };
    for (const key of Object.keys(Object.assign({}, def?.defaults, e.params))) {
      const v = e.params[key] ?? def?.defaults[key] ?? "";
      if (key === "days") {
        const days = h("div", { class: "days" });
        UI.days[L()].forEach((d, n) => {
          const num = String(n + 1);
          const btn = h("button", { class: "day" + (v.includes(num) ? " on" : ""), onclick: () => {
            const cur2 = e.params.days ?? v;
            const next = cur2.includes(num) ? cur2.replace(num, "") : [...cur2 + num].sort().join("");
            e.params.days = next; commit(); renderMain();
          } }, d);
          days.append(btn);
        });
        params.append(h("label", null, ["Dny", "Days"][L()], days));
      } else if (key === "time") {
        params.append(h("label", null, t("time"), h("input", { class: "input", type: "time", value: v, oninput: (ev) => set(key, ev.target.value) })));
      } else if (["percent", "minutes", "radius"].includes(key)) {
        params.append(h("label", null, t(key), h("input", { class: "input", type: "number", value: v, min: key === "minutes" ? 15 : 1, max: key === "percent" ? 100 : null, style: { width: "120px", minWidth: 0 }, oninput: (ev) => set(key, ev.target.value) })));
      } else if (key === "place") {
        params.append(h("label", { style: { flex: 1, minWidth: "240px" } }, t("place"), h("input", { class: "input", value: v, style: { width: "100%" }, placeholder: "50.0755, 14.4378", oninput: (ev) => set(key, ev.target.value) })));
      } else {
        params.append(h("label", null, key, h("input", { class: "input", value: v, oninput: (ev) => set(key, ev.target.value) })));
      }
    }
    return h("div", { class: "event-card" },
      h("div", { class: "event-top" },
        h("div", { class: "ic" }, icon(EVENT_GROUP_ICONS[def?.group] || "bolt")),
        h("div", { style: { flex: 1, minWidth: 0 } },
          h("div", { class: "t" }, tx(def?.title) || e.type),
          h("div", { class: "d" }, tx(def?.description))),
        h("button", { class: "icon-btn sm danger", title: t("remove"), onclick: () => { a.events.splice(i, 1); changed(); } }, icon("close"))),
      params.childNodes.length ? params : null);
  }

  function eventMenu(anchor) {
    const items = [];
    let group = null;
    for (const e of C.events) {
      if (e.group !== group) { group = e.group; items.push(h("div", { class: "pop-group" }, tx(e.groupTitle))); }
      items.push(popItem(EVENT_GROUP_ICONS[e.group] || "bolt", tx(e.title), tx(e.input) ? "→ " + tx(e.input) : null, () => {
        cur().events.push({ type: e.key, params: { ...e.defaults }, id: M.uuid() });
        changed();
      }));
    }
    popover(anchor, items);
  }

  // ---------- blocks view with drag & drop ----------
  let drag = null; // { kind: "move", s, e } | { kind: "new", type }

  function blocksView(a, structure) {
    const wrap = h("div");
    if (!structure.valid && a.blocks.length) {
      wrap.append(h("div", { class: "banner err" }, icon("warning", "small"),
        ["Některé bloky nejsou správně spárované (Pokud bez Konec apod.). Aplikace takovou automatizaci nespustí.",
          "Some blocks are not paired correctly (an If without End, …). The app will not run such an automation."][L()]));
    }
    if (!a.blocks.length) {
      const empty = h("div", { class: "empty" }, icon("add_circle"), h("h4", null, t("emptyTitle")), h("div", null, t("emptyText")));
      empty.addEventListener("dragover", (e) => { if (drag) { e.preventDefault(); empty.classList.add("drag-over"); } });
      empty.addEventListener("dragleave", () => empty.classList.remove("drag-over"));
      empty.addEventListener("drop", (e) => { e.preventDefault(); if (drag?.kind === "new") addBlock(drag.type, 0); drag = null; });
      wrap.append(empty);
      return wrap;
    }
    structure.names = Script.shownNames(a.blocks);
    const list = h("div", { class: "blocks" });
    const line = h("div", { class: "drop-line" });
    let dropAt = null;

    // Steps inside a folded group are not drawn; the group start shows how many there are.
    const hidden = new Set();
    a.blocks.forEach((b, i) => {
      if (!S.folded.has(b.id) || hidden.has(i) || structure.endOf[i] == null) return;
      for (let k = i + 1; k <= structure.endOf[i]; k++) hidden.add(k);
    });

    a.blocks.forEach((b, i) => {
      if (hidden.has(i)) return;
      const depth = structure.depth[i] || 0;
      const step = window.innerWidth < 820 ? 14 : 26;
      const row = h("div", { class: "block-row", style: { paddingLeft: depth * step + "px" }, dataset: { index: i } });
      if (depth) {
        const rails = h("div", { class: "rails" });
        for (let d = 0; d < depth; d++) rails.append(h("div", { class: "rail", style: { left: d * step + 6 + "px", background: "var(--border-strong)" } }));
        row.append(rails);
      }
      const card = blockCard(b, i, "blocks", structure);
      const handle = card.querySelector(".handle:not(.off)");
      handle?.addEventListener("mousedown", () => { row.draggable = true; });
      handle?.addEventListener("mouseup", () => { row.draggable = false; });
      row.addEventListener("dragstart", (e) => {
        const [s, en] = M.rangeOf(a.blocks, i, structure);
        drag = { kind: "move", s, e: en };
        e.dataTransfer.effectAllowed = "move";
        e.dataTransfer.setData("text/plain", b.type);
        // The drag image is taken right now, so the group is folded on the next frame:
        // the steps inside disappear and the start shows how many go with it.
        requestAnimationFrame(() => {
          row.classList.add("dragging");
          if (en > s) row.classList.add("dragging-group");
          for (let k = s + 1; k <= en; k++) list.querySelector(`.block-row[data-index="${k}"]`)?.classList.add("drag-hidden");
          card.querySelector(".fold-badge")?.classList.remove("hidden");
        });
      });
      row.addEventListener("dragend", () => {
        row.draggable = false; drag = null; line.remove();
        list.querySelectorAll(".dragging").forEach((x) => x.classList.remove("dragging", "dragging-group"));
        list.querySelectorAll(".drag-hidden").forEach((x) => x.classList.remove("drag-hidden"));
        if (!S.folded.has(b.id)) card.querySelector(".fold-badge")?.classList.add("hidden");
      });
      row.append(card);
      list.append(row);
    });

    list.addEventListener("dragover", (e) => {
      if (!drag) return;
      e.preventDefault();
      const rows = [...list.querySelectorAll(".block-row:not(.drag-hidden)")];
      let target = a.blocks.length;
      for (const r of rows) {
        const rect = r.getBoundingClientRect();
        if (e.clientY < rect.top + rect.height / 2) { target = +r.dataset.index; break; }
      }
      dropAt = target;
      const before = rows.find((r) => +r.dataset.index === target);
      const depth = target < a.blocks.length ? structure.depth[target] : 0;
      line.style.marginLeft = depth * 26 + "px";
      if (before) list.insertBefore(line, before); else list.append(line);
    });
    list.addEventListener("dragleave", (e) => { if (!list.contains(e.relatedTarget)) line.remove(); });
    list.addEventListener("drop", (e) => {
      e.preventDefault();
      line.remove();
      if (!drag || dropAt == null) return;
      const d = drag; drag = null;
      if (d.kind === "new") addBlock(d.type, dropAt);
      else moveRange(d.s, d.e, dropAt);
    });
    wrap.append(list);
    return wrap;
  }

  // ---------- code view ----------
  function editor({ value, onInput, highlight }) {
    const gutter = h("div", { class: "gutter" });
    const pre = h("pre");
    const ta = h("textarea", { spellcheck: "false", autocapitalize: "off", autocomplete: "off" });
    ta.value = value;
    const stack = h("div", { class: "code-stack" }, pre, ta);
    const area = h("div", { class: "code-area" }, gutter, h("div", { class: "code-scroll", style: { flex: 1, minWidth: 0 } }, stack));
    let errLine = null;
    const draw = () => {
      pre.innerHTML = highlight(ta.value);
      const n = ta.value.split("\n").length;
      gutter.innerHTML = Array.from({ length: n }, (_, i) => (i + 1 === errLine ? '<span class="err-line">' + (i + 1) + "</span>" : i + 1)).join("\n");
      // Size the textarea to the content so the outer box scrolls, not the textarea.
      const lines = ta.value.split("\n");
      const longest = Math.max(...lines.map((l) => l.length));
      stack.style.width = `calc(${longest}ch + 48px)`;
      stack.style.minWidth = "100%";
      ta.style.height = pre.offsetHeight + "px";
    };
    ta.addEventListener("input", () => { draw(); onInput(ta.value); });
    ta.addEventListener("keydown", (e) => {
      if (e.key === "Tab") {
        e.preventDefault();
        const { selectionStart: s, selectionEnd: en, value: v } = ta;
        if (e.shiftKey || s !== en) {
          const ls = v.lastIndexOf("\n", s - 1) + 1;
          const block = v.slice(ls, en);
          const changedText = e.shiftKey ? block.replace(/^ {1,4}/gm, "") : block.replace(/^/gm, "    ");
          ta.setRangeText(changedText, ls, en, "select");
        } else ta.setRangeText("    ", s, en, "end");
        draw(); onInput(ta.value);
      } else if (e.key === "Enter") {
        const { selectionStart: s, value: v } = ta;
        const ls = v.lastIndexOf("\n", s - 1) + 1;
        const indent = v.slice(ls).match(/^[ ]*/)[0];
        const lineText = v.slice(ls, s).trim();
        const extra = /^(@off\s+)?([A-Za-z_]\w*\s*=\s*)?(if|while|for_each|repeat|try|switch|parallel|lock|time_limit|else|else_if|catch|case|parallel_branch)\b/.test(lineText) ? "    " : "";
        e.preventDefault();
        ta.setRangeText("\n" + indent + extra, s, ta.selectionEnd, "end");
        draw(); onInput(ta.value);
      }
    });
    requestAnimationFrame(draw);
    return { area, ta, setError: (l) => { errLine = l; draw(); }, draw };
  }

  /** The Kód view: the automation in the language of the Skript block, exactly as the app writes it. */
  function codeView(a) {
    const written = Script.write(a.blocks, S.lang);
    const status = h("div", { class: "status ok" });
    const showStatus = (blocks) => {
      const valid = M.analyze(blocks).valid;
      status.className = "status " + (valid ? "ok" : "err");
      status.replaceChildren(icon(valid ? "check_circle" : "error", "small"),
        (valid ? t("codeOk") : t("structureBad")) + " · " + stepCount(blocks.length));
    };
    showStatus(a.blocks);
    let ed;
    const apply = (text) => {
      const prev = cur();
      // The blocks the text was written from fill in what the text does not show (hidden settings, ids).
      const parsed = Script.parse(text, { blocks: prev.blocks });
      prev.blocks = parsed.blocks;
      showStatus(parsed.blocks);
      commitSoon();
      renderSidebar();
    };
    let timer;
    ed = editor({ value: written, highlight: Script.highlight, onInput: (v) => { clearTimeout(timer); timer = setTimeout(() => apply(v), 300); } });
    const shell = h("div", { class: "code-shell" },
      h("div", { class: "code-toolbar" }, status, h("div", { class: "spacer" }),
        h("button", { class: "btn ghost small", onclick: () => { navigator.clipboard.writeText(ed.ta.value); toast(t("copied")); } }, icon("content_copy", "small"), t("copy")),
        h("button", { class: "btn ghost small", onclick: () => { commit(); renderMain(); } }, icon("format_align_left", "small"), t("format")),
        h("button", { class: "btn ghost small", onclick: () => downloadText(fileBase(a) + ".txt", ed.ta.value, "text/plain") }, icon("download", "small"), ".txt")),
      ed.area);
    return h("div", null, shell, h("div", { class: "code-help", html: t("codeHelp") }));
  }

  function jsonView(a) {
    const text = JSON.stringify(exportedOne(a), null, 2);
    const status = h("div", { class: "status ok" }, icon("check_circle", "small"), t("jsonOk"));
    let ed, timer;
    const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;");
    const hl = (s) => esc(s).replace(/("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\b\d+(\.\d+)?\b/g, (m, str, colon, kw) =>
      str ? (colon ? '<span class="tk-key">' + str + "</span>" + colon : '<span class="tk-str">' + str + "</span>") : '<span class="tk-num">' + m + "</span>") + "\n";
    const apply = (v) => {
      try {
        const data = JSON.parse(v);
        const norm = M.normalizeAutomation(data);
        Object.assign(cur(), norm);
        status.className = "status ok"; status.replaceChildren(icon("check_circle", "small"), t("jsonOk"));
        commitSoon(); renderSidebar();
      } catch (err) {
        status.className = "status err"; status.replaceChildren(icon("error", "small"), t("invalidJson") + ": " + err.message);
      }
    };
    ed = editor({ value: text, highlight: hl, onInput: (v) => { clearTimeout(timer); timer = setTimeout(() => apply(v), 300); } });
    return h("div", { class: "code-shell" },
      h("div", { class: "code-toolbar" }, status, h("div", { class: "spacer" }),
        h("button", { class: "btn ghost small", onclick: () => { navigator.clipboard.writeText(ed.ta.value); toast(t("copied")); } }, icon("content_copy", "small"), t("copy"))),
      ed.area);
  }

  // ---------- library ----------
  function renderLibrary() {
    const lib = $("#library");
    lib.textContent = "";
    const q = S.libQuery.trim().toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
    const fold = (s) => (s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "");
    const search = h("input", { class: "input", placeholder: t("searchBlocks"), value: S.libQuery, oninput: () => { S.libQuery = search.value; renderLibraryList(); } });
    const cats = h("div", { class: "cats" },
      h("button", { class: S.libCat ? "" : "on", onclick: () => { S.libCat = null; renderLibrary(); } }, t("all")),
      C.categories.map((c) => h("button", { class: S.libCat === c.key ? "on" : "", onclick: () => { S.libCat = c.key; renderLibrary(); } }, tx(c.title))));
    lib.append(h("div", { class: "lib-head" },
      h("div", { class: "row" }, h("span", { class: "side-title" }, t("library")), h("span", { class: "badge neutral" }, C.blocks.filter((b) => !b.hiddenInLibrary).length)),
      h("div", { class: "search" }, icon("search", "small"), search), cats));
    const listEl = h("div", { class: "lib-list" });
    lib.append(listEl, h("div", { class: "lib-hint" }, icon("touch_app", "small"), t("libHint")));

    function renderLibraryList() {
      const qq = fold(S.libQuery.trim());
      listEl.textContent = "";
      let count = 0;
      for (const c of C.categories) {
        if (S.libCat && S.libCat !== c.key) continue;
        const items = C.blocks.filter((b) => b.category === c.key && !b.hiddenInLibrary && (!qq ||
          [b.type, b.title.cs, b.title.en, b.description.cs, b.description.en].some((s) => fold(s).includes(qq))));
        if (!items.length) continue;
        const hue = HUES[c.key];
        listEl.append(h("div", { class: "lib-group", style: { "--hue": hue } }, h("span", { class: "dot" }), tx(c.title)));
        for (const b of items) {
          count++;
          const item = h("div", { class: "lib-item", draggable: "true", style: { "--hue": hue }, title: b.type, onclick: () => { addBlock(b.type); document.body.classList.remove("lib-open"); } },
            h("div", { class: "b-icon" }, icon(b.icon)),
            h("div", { style: { minWidth: 0 } }, h("div", { class: "t" }, tx(b.title), b.requirement ? icon("shield", "tiny") : null), h("div", { class: "d" }, tx(b.description))),
            h("button", { class: "icon-btn sm add", tabindex: -1 }, icon("add")));
          item.addEventListener("dragstart", (e) => { drag = { kind: "new", type: b.type }; e.dataTransfer.effectAllowed = "copy"; e.dataTransfer.setData("text/plain", b.type); });
          item.addEventListener("dragend", () => { drag = null; document.querySelector(".drop-line")?.remove(); });
          listEl.append(item);
        }
      }
      if (!count) listEl.append(h("div", { class: "empty", style: { margin: "20px 6px", padding: "30px" } }, icon("search_off"), h("div", null, t("noResults"))));
    }
    renderLibraryList();
  }

  // ---------- icon picker ----------
  function iconPicker() {
    const a = cur();
    let query = "";
    const grid = h("div", { class: "icon-grid" });
    const draw = () => {
      grid.textContent = "";
      const q = query.toLowerCase();
      for (const i of C.icons) {
        if (q && !(i.key + " " + i.words).toLowerCase().includes(q)) continue;
        grid.append(h("button", { class: a.icon === i.key ? "on" : "", title: i.key, onclick: () => { a.icon = i.key; close(); changed(); } }, icon(i.icon, "fill")));
      }
    };
    const input = h("input", { class: "input", placeholder: t("searchIcons"), oninput: () => { query = input.value; draw(); } });
    const close = modal(t("icon"), h("div", null, h("div", { class: "search" }, icon("search", "small"), input), grid));
    draw();
    setTimeout(() => input.focus(), 0);
  }

  // ---------- import / export ----------
  const exportedOne = (a) => JSON.parse(M.exportFile([a])).automations[0];
  const fileBase = (a) => (a.name || "automatizace").replace(/[\\/:*?"<>|\x00-\x1f]/g, "_").trim().slice(0, 60) || "automatizace";

  function downloadText(name, text, type = "application/json") {
    const url = URL.createObjectURL(new Blob([text], { type }));
    const link = h("a", { href: url, download: name });
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function download(automations) {
    const name = automations.length === 1 ? fileBase(automations[0]) + ".automation.json" : "automatizace.json";
    downloadText(name, M.exportFile(automations));
  }

  function exportMenu(anchor) {
    popover(anchor, [
      popItem("description", t("exportThis"), t("exportThisD"), () => download([cur()])),
      popItem("folder_zip", t("exportAll"), t("exportAllD") + " (" + S.automations.length + ")", () => download(S.automations)),
      h("div", { class: "pop-sep" }),
      popItem("content_copy", t("exportCopy"), null, () => { navigator.clipboard.writeText(M.exportFile([cur()])); toast(t("copied")); }),
      popItem("code", t("exportCode"), ".txt", () => downloadText(fileBase(cur()) + ".txt", Script.write(cur().blocks, S.lang), "text/plain")),
    ]);
  }

  function importAutomations(list) {
    for (const a of list) {
      a.blocks = remapIds(a.blocks);
      a.conditions = remapIds(a.conditions);
      a.events = a.events.map((e) => ({ ...e, id: M.uuid() }));
    }
    S.automations.push(...list);
    S.current = S.automations.length - list.length;
    S.selected = null;
    changed();
    toast(t("imported") + list.length);
  }

  /** Reads an export file, a single automation, or the text form. */
  function importFromText(text) {
    const trimmed = text.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      try { return M.importText(text); } catch (e) { throw new Error(e.message === "format" ? t("importFormat") : t("importFail")); }
    }
    // A script copied from the app ("Zobrazit jako skript").
    const blocks = Script.parse(text).blocks;
    if (!blocks.length) throw new Error(t("importFail"));
    return [M.normalizeAutomation({ name: "Import", blocks })];
  }

  async function importFiles(files) {
    const all = [];
    for (const f of files) {
      try { all.push(...importFromText(await f.text())); } catch (e) { toast(f.name + ": " + e.message, "err"); }
    }
    if (all.length) importAutomations(all);
  }

  function importDialog() {
    const area = h("textarea", { class: "input", rows: 8, placeholder: t("importPaste"), style: { fontFamily: "JetBrains Mono, monospace", fontSize: "12.5px", marginTop: "14px" } });
    const drop = h("div", { class: "dropzone", onclick: () => $("#file-input").click() }, icon("upload_file"), h("div", { style: { marginTop: "8px" } }, t("importDrop")));
    drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("over"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("over"));
    drop.addEventListener("drop", (e) => { e.preventDefault(); e.stopPropagation(); drop.classList.remove("over"); close(); importFiles(e.dataTransfer.files); });
    const close = modal(t("importTitle"), h("div", null, drop, area), (closeIt) => [
      h("button", { class: "btn", onclick: closeIt }, t("cancel")),
      h("button", { class: "btn primary", onclick: () => {
        try { const list = importFromText(area.value); closeIt(); importAutomations(list); } catch (e) { toast(e.message, "err"); }
      } }, icon("download_done", "small"), t("importBtn")),
    ]);
  }

  function examplesDialog() {
    const grid = h("div", { class: "example-grid" });
    for (const ex of C.examples) {
      const ic = M.iconDefs.get(ex.icon)?.icon || "bolt";
      grid.append(h("button", { class: "example", onclick: () => {
        const a = M.normalizeAutomation(M.clone({ ...ex, name: tx(ex.name) }));
        delete a.description;
        importAutomations([a]);
        close();
      } },
        h("div", { class: "auto-icon" }, icon(ic, "fill")),
        h("div", null, h("div", { class: "t" }, tx(ex.name)), h("div", { class: "d" }, tx(ex.description)))));
    }
    const close = modal(t("examplesTitle"), h("div", null, h("p", { style: { marginTop: 0, color: "var(--text-3)" } }, t("examplesHint")), grid));
  }

  // ---------- chrome ----------
  function applyTheme() {
    if (S.theme) document.documentElement.dataset.theme = S.theme; else delete document.documentElement.dataset.theme;
    const dark = S.theme ? S.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
    $("#btn-theme .ms").textContent = dark ? "light_mode" : "dark_mode";
  }

  function renderChrome() {
    document.documentElement.lang = S.lang;
    document.querySelectorAll("[data-i18n]").forEach((el) => { el.textContent = t(el.dataset.i18n); });
    document.querySelectorAll("[data-i18n-title]").forEach((el) => { el.title = t(el.dataset.i18nTitle); });
    $("#lang-label").textContent = S.lang.toUpperCase();
    applyTheme();
    updateUndo();
  }

  function renderAll() { renderChrome(); renderSidebar(); renderMain(); renderLibrary(); }

  function selectedIndex() {
    const a = cur();
    for (const list of ["blocks", "conditions"]) {
      const i = a[list].findIndex((b) => b.id === S.selected);
      if (i >= 0) return [list, i];
    }
    return null;
  }

  function bind() {
    $("#btn-import").onclick = importDialog;
    $("#btn-export").onclick = (e) => exportMenu(e.currentTarget);
    $("#btn-examples").onclick = examplesDialog;
    $("#btn-undo").onclick = undo;
    $("#btn-redo").onclick = redo;
    $("#btn-lang").onclick = () => { S.lang = S.lang === "cs" ? "en" : "cs"; save(); renderAll(); };
    $("#btn-theme").onclick = () => {
      const dark = S.theme ? S.theme === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
      S.theme = dark ? "light" : "dark"; save(); applyTheme();
    };
    $("#toggle-sidebar").onclick = () => document.body.classList.toggle("side-open");
    $("#toggle-library").onclick = () => document.body.classList.toggle("lib-open");
    $("#scrim").onclick = () => document.body.classList.remove("side-open", "lib-open");
    $("#file-input").onchange = (e) => { importFiles(e.target.files); e.target.value = ""; };
    matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

    // Dropping an export file anywhere imports it.
    let overlay = null;
    const hasFiles = (e) => [...(e.dataTransfer?.types || [])].includes("Files");
    window.addEventListener("dragover", (e) => {
      if (!hasFiles(e)) return;
      e.preventDefault();
      if (!overlay) { overlay = h("div", { class: "file-drop-overlay" }, t("dropFile")); document.body.append(overlay); }
    });
    window.addEventListener("dragleave", (e) => { if (!e.relatedTarget && overlay) { overlay.remove(); overlay = null; } });
    window.addEventListener("drop", (e) => {
      if (overlay) { overlay.remove(); overlay = null; }
      if (!hasFiles(e)) return;
      e.preventDefault();
      importFiles(e.dataTransfer.files);
    });

    document.addEventListener("keydown", (e) => {
      const typing = e.target.closest?.("input, textarea, select, [contenteditable='true']");
      const mod = e.ctrlKey || e.metaKey;
      if (mod && !typing && e.key.toLowerCase() === "z") { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
      if (mod && !typing && e.key.toLowerCase() === "y") { e.preventDefault(); redo(); return; }
      if (e.key === "Escape") { closePopover(); return; }
      if (typing || S.view !== "blocks") return;
      const sel = selectedIndex();
      if (!sel) return;
      const [list, i] = sel;
      if (e.key === "Delete") { e.preventDefault(); removeBlock(list, i); }
      else if (mod && e.key.toLowerCase() === "d") { e.preventDefault(); duplicateBlock(list, i); }
      else if (e.altKey && e.key === "ArrowUp") { e.preventDefault(); moveBlock(list, i, -1); }
      else if (e.altKey && e.key === "ArrowDown") { e.preventDefault(); moveBlock(list, i, 1); }
      else if (!e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
        // Over the cards on screen, so steps inside a folded group are skipped.
        const cards = [...document.querySelectorAll(list === "blocks" ? ".blocks .block" : ".cond-list .block")];
        const pos = cards.findIndex((c) => c.dataset.id === S.selected);
        const n = Math.max(0, Math.min(cards.length - 1, pos + (e.key === "ArrowUp" ? -1 : 1)));
        S.selected = cards[n].dataset.id; e.preventDefault(); renderMain();
        document.querySelector(`.block[data-id="${S.selected}"]`)?.scrollIntoView({ block: "nearest" });
      } else if (e.key === "Enter") {
        S.expanded.has(S.selected) ? S.expanded.delete(S.selected) : S.expanded.add(S.selected); renderMain();
      }
    });
    window.addEventListener("beforeunload", () => { clearTimeout(commitTimer); save(); });
  }

  // ---------- start ----------
  S.lang = /^(cs|sk)/i.test(navigator.language || "") ? "cs" : "en";
  if (!load()) {
    const ex = C.examples.find((e) => e.name.cs === "Vyčistit odkaz") || C.examples[0];
    const a = M.normalizeAutomation(M.clone({ ...ex, name: tx(ex.name) }));
    delete a.description;
    a.blocks = remapIds(a.blocks);
    S.automations = [a];
    S.current = 0;
  }
  bind();
  commit();
  renderAll();
})();
