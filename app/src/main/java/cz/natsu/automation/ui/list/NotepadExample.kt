package cz.natsu.automation.ui.list

import cz.natsu.automation.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Zabezpečený zápisník": a notebook of passwords and notes in a JSON file, every title,
 * category and text encrypted. Built from small blocks: file, password → key, fingerprint,
 * the window with its own HTML, encrypt / decrypt.
 */
object NotepadExample {
    const val NAME = "Zabezpečený zápisník"

    private fun q(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\t", "\\t") + "\""

    private fun t(text: String) = q(tr(text))

    fun script(): String = """
        # @name "$NAME"
        # @icon lock
        # ${tr("Soubor se zápisníkem: poprvé se zeptá, kam ho uložit")}
        cesta = store_get("zapisnik_soubor")
        soubor = cesta
        if soubor == ""
            novy_soubor = pick_save_file("json", "zapisnik")
            soubor = novy_soubor
            store_set("set", "zapisnik_soubor", soubor)
        end
        obsah = read_file(soubor)
        db = obsah
        if obsah == ""
            db = "{\"v\": 1, \"check\": \"\", \"items\": []}"
        end
        check = json_get(db, "check", "value", "")
        # ${tr("Odemknout: otiskem prstu, jinak heslem")}
        klic = ""
        otisk = store_get("zapisnik_otisk")
        if otisk != ""
            chyba_otisku = try
                odemceno = bio_unlock(otisk, ${t("Odemknout zápisník")})
                klic = odemceno
            end
        end
        if klic == ""
            heslo = ask_text(${t("Heslo k zápisníku")}, "", false, true)
            novy_klic = derive_key(heslo, check)
            if check == ""
                overeni = encrypt("ok", novy_klic)
                nova_db = json_set(db, "set", "check", overeni)
                db = nova_db
                write_file(soubor, db, "overwrite")
            else
                chyba_hesla = try
                    decrypt(check, novy_klic)
                catch
                    fail(${t("Špatné heslo")})
                end
            end
            klic = novy_klic
            if otisk == ""
                chci_otisk = choose(${t("Odemykat příště otiskem prstu?")}, ${t("Ano\nNe")})
                if chci_otisk == ${t("Ano")}
                    chyba_zamku = try
                        zamceny = bio_lock(klic, "zapisnik", ${t("Zapamatovat otiskem prstu")})
                        store_set("set", "zapisnik_otisk", zamceny)
                    catch
                        toast(chyba_zamku)
                    end
                end
            end
        end
        # ${tr("Okno: stránka volá akce list, show, save, delete, password, copy, forget")}
        pozadavek = html_window(${q(html())}, "", false, true)
            akce = json_get(pozadavek, "action", "value")
            if akce == "list"
                seznam = script
                    vypis = []
                    for zaznam in json(db).items
                        vypis = push(vypis, {id: zaznam.id, title: decrypt(zaznam.title, klic), category: decrypt(zaznam.category, klic), updated: zaznam.updated})
                    end
                    return vypis
                end
                return seznam
            else if akce == "show"
                text_zaznamu = script
                    hledane = json(pozadavek).data.id
                    for zaznam in json(db).items
                        if zaznam.id == hledane
                            return decrypt(zaznam.body, klic)
                        end
                    end
                    return ""
                end
                return text_zaznamu
            else if akce == "save"
                ulozene_id = script
                    udaje = json(pozadavek).data
                    novy = {id: udaje.id, title: encrypt(udaje.title, klic), category: encrypt(udaje.category, klic), body: encrypt(udaje.body, klic), updated: date()}
                    vse = json(db)
                    if udaje.id == ""
                        novy.id = str(now())
                        vse.items = push(vse.items, novy)
                    else
                        upravene = []
                        for zaznam in vse.items
                            if zaznam.id == udaje.id
                                upravene = push(upravene, novy)
                            else
                                upravene = push(upravene, zaznam)
                            end
                        end
                        vse.items = upravene
                    end
                    db = tojson(vse)
                    return novy.id
                end
                write_file(soubor, db, "overwrite")
                return ulozene_id
            else if akce == "delete"
                script
                    mazane = json(pozadavek).data.id
                    vse = json(db)
                    zbyle = []
                    for zaznam in vse.items
                        if zaznam.id != mazane
                            zbyle = push(zbyle, zaznam)
                        end
                    end
                    vse.items = zbyle
                    db = tojson(vse)
                end
                write_file(soubor, db, "overwrite")
                return "ok"
            else if akce == "password"
                nove_heslo = random_password(20)
                return nove_heslo
            else if akce == "copy"
                kopie = json_get(pozadavek, "data.text", "value")
                set_clipboard(kopie, true, 30)
                return "ok"
            else if akce == "forget"
                store_set("remove", "zapisnik_otisk")
                bio_forget("zapisnik")
                return "ok"
            end
        end
    """.trimIndent()

    /** Texts of the page, in the language of the app. */
    private fun texts(): String = JsonObject(
        listOf(
            "search" to "Hledat", "new" to "Nový", "all" to "Vše", "empty" to "Zatím tu nic není – přidej první záznam.",
            "title" to "Název", "category" to "Kategorie", "body" to "Obsah (heslo, poznámka…)", "save" to "Uložit",
            "cancel" to "Zrušit", "show" to "Ukázat", "hide" to "Skrýt", "copy" to "Kopírovat", "copied" to "Zkopírováno, za 30 s se smaže",
            "edit" to "Upravit", "delete" to "Smazat", "confirmDelete" to "Opravdu smazat tento záznam?", "generate" to "Vygenerovat heslo",
            "lock" to "Zamknout", "forget" to "Zapomenout otisk", "forgotten" to "Příště se zeptá na heslo",
            "noTitle" to "Bez názvu", "changed" to "Změněno", "needTitle" to "Vyplň název",
        ).associate { (key, text) -> key to JsonPrimitive(tr(text)) }
    ).toString()

    private fun html(): String = PAGE.replace("/*TEXTS*/{}", texts())

    private val PAGE = """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root { --bg:#fdfbff; --fg:#1b1b1f; --muted:#6b6b76; --card:#f1eff7; --accent:#5b4bd6; --on-accent:#fff; --danger:#c62828; --line:#e3e1ea; }
@media (prefers-color-scheme: dark) {
  :root { --bg:#1b1b1f; --fg:#ececf1; --muted:#a3a3ad; --card:#2a2a31; --accent:#b5a8ff; --on-accent:#1b1b1f; --danger:#ff8a80; --line:#3a3a42; }
}
* { box-sizing: border-box; }
body { margin: 0; font: 15px/1.45 system-ui, sans-serif; background: var(--bg); color: var(--fg); }
header { position: sticky; top: 0; background: var(--bg); padding: 12px 14px 6px; border-bottom: 1px solid var(--line); z-index: 1; }
.row { display: flex; gap: 8px; align-items: center; }
input, textarea, select { font: inherit; color: inherit; background: var(--card); border: 1px solid var(--line); border-radius: 12px; padding: 10px 12px; width: 100%; }
textarea { min-height: 140px; resize: vertical; font-family: ui-monospace, monospace; }
button { font: inherit; border: 0; border-radius: 20px; padding: 9px 14px; background: var(--card); color: var(--fg); white-space: nowrap; }
button.primary { background: var(--accent); color: var(--on-accent); }
button.danger { color: var(--danger); }
.chips { display: flex; gap: 6px; overflow-x: auto; padding: 8px 0 4px; }
.chip { padding: 5px 12px; border-radius: 16px; border: 1px solid var(--line); background: none; font-size: 13px; }
.chip.on { background: var(--accent); color: var(--on-accent); border-color: var(--accent); }
main { padding: 8px 14px 40px; }
.item { background: var(--card); border-radius: 16px; padding: 12px 14px; margin: 8px 0; }
.item .name { font-weight: 600; }
.meta { color: var(--muted); font-size: 12px; }
.secret { margin: 10px 0; padding: 10px; border-radius: 10px; background: var(--bg); white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, monospace; }
.actions { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.empty { color: var(--muted); text-align: center; margin-top: 48px; }
form { display: grid; gap: 10px; }
#toast { position: fixed; left: 50%; bottom: 18px; transform: translateX(-50%); background: var(--fg); color: var(--bg); padding: 8px 14px; border-radius: 16px; font-size: 13px; opacity: 0; transition: opacity .2s; pointer-events: none; }
#toast.on { opacity: .92; }
.hidden { display: none !important; }
</style>
</head><body>
<header>
  <div class="row">
    <input id="q" type="search" autocomplete="off">
    <button id="add" class="primary"></button>
    <button id="lock" title="">🔒</button>
  </div>
  <div id="cats" class="chips"></div>
</header>
<main id="list"></main>
<main id="editor" class="hidden">
  <form id="form">
    <input id="f-title" autocomplete="off">
    <input id="f-category" list="cat-list" autocomplete="off">
    <datalist id="cat-list"></datalist>
    <textarea id="f-body" autocomplete="off" spellcheck="false"></textarea>
    <div class="actions">
      <button type="button" id="gen"></button>
      <span style="flex:1"></span>
      <button type="button" id="back"></button>
      <button type="submit" class="primary" id="save"></button>
    </div>
  </form>
  <div class="actions" style="margin-top:24px"><button type="button" id="forget" class="danger"></button></div>
</main>
<div id="toast"></div>
<script>
const T = /*TEXTS*/{};
const ${'$'} = (id) => document.getElementById(id);
let items = [], category = '', editing = null;

function toast(text) {
  const t = ${'$'}('toast');
  t.textContent = text;
  t.classList.add('on');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => t.classList.remove('on'), 2200);
}
function fail(e) { toast(e && e.message ? e.message : String(e)); }
function el(tag, props, ...kids) {
  const node = Object.assign(document.createElement(tag), props || {});
  kids.forEach((k) => node.append(k));
  return node;
}

async function load() {
  items = await automation.call('list');
  if (!Array.isArray(items)) items = [];
  render();
}

function render() {
  const cats = [...new Set(items.map((i) => i.category).filter(Boolean))].sort();
  if (category && !cats.includes(category)) category = '';
  ${'$'}('cats').replaceChildren(...['', ...cats].map((c) =>
    el('button', { className: 'chip' + (c === category ? ' on' : ''), textContent: c || T.all, onclick: () => { category = c; render(); } })));
  ${'$'}('cat-list').replaceChildren(...cats.map((c) => el('option', { value: c })));
  const q = ${'$'}('q').value.trim().toLowerCase();
  const shown = items
    .filter((i) => !category || i.category === category)
    .filter((i) => !q || (i.title + ' ' + i.category).toLowerCase().includes(q))
    .sort((a, b) => String(a.title).localeCompare(String(b.title)));
  const list = ${'$'}('list');
  if (!shown.length) { list.replaceChildren(el('p', { className: 'empty', textContent: T.empty })); return; }
  list.replaceChildren(...shown.map(card));
}

function card(item) {
  const secret = el('div', { className: 'secret hidden' });
  let text = null;
  const show = el('button', { textContent: T.show });
  show.onclick = async () => {
    if (text === null) {
      try { text = String(await automation.call('show', { id: item.id })); } catch (e) { return fail(e); }
      secret.textContent = text;
    }
    const hidden = secret.classList.toggle('hidden');
    show.textContent = hidden ? T.show : T.hide;
  };
  const copy = el('button', { textContent: T.copy, onclick: async () => {
    try {
      if (text === null) text = String(await automation.call('show', { id: item.id }));
      await automation.call('copy', { text });
      toast(T.copied);
    } catch (e) { fail(e); }
  } });
  const edit = el('button', { textContent: T.edit, onclick: async () => {
    try { openEditor(item, String(await automation.call('show', { id: item.id }))); } catch (e) { fail(e); }
  } });
  const del = el('button', { className: 'danger', textContent: T.delete, onclick: async () => {
    if (!confirm(T.confirmDelete)) return;
    try { await automation.call('delete', { id: item.id }); await load(); } catch (e) { fail(e); }
  } });
  return el('div', { className: 'item' },
    el('div', { className: 'name', textContent: item.title || T.noTitle }),
    el('div', { className: 'meta', textContent: [item.category, item.updated && (T.changed + ' ' + item.updated)].filter(Boolean).join(' · ') }),
    secret,
    el('div', { className: 'actions' }, show, copy, edit, del));
}

function openEditor(item, body) {
  editing = item ? item.id : '';
  ${'$'}('f-title').value = item ? item.title : '';
  ${'$'}('f-category').value = item ? item.category : category;
  ${'$'}('f-body').value = body || '';
  ${'$'}('list').classList.add('hidden');
  document.querySelector('header').classList.add('hidden');
  ${'$'}('editor').classList.remove('hidden');
  ${'$'}('f-title').focus();
}
function closeEditor() {
  editing = null;
  ${'$'}('f-body').value = '';
  ${'$'}('editor').classList.add('hidden');
  ${'$'}('list').classList.remove('hidden');
  document.querySelector('header').classList.remove('hidden');
}

${'$'}('q').placeholder = T.search;
${'$'}('add').textContent = '+ ' + T.new;
${'$'}('lock').title = T.lock;
${'$'}('f-title').placeholder = T.title;
${'$'}('f-category').placeholder = T.category;
${'$'}('f-body').placeholder = T.body;
${'$'}('gen').textContent = T.generate;
${'$'}('back').textContent = T.cancel;
${'$'}('save').textContent = T.save;
${'$'}('forget').textContent = T.forget;

${'$'}('q').oninput = render;
${'$'}('add').onclick = () => openEditor(null, '');
${'$'}('back').onclick = closeEditor;
${'$'}('lock').onclick = () => automation.close('locked');
${'$'}('gen').onclick = async () => {
  try {
    const password = String(await automation.call('password'));
    const body = ${'$'}('f-body');
    body.value = body.value ? body.value.replace(/\s*${'$'}/, '\n') + password : password;
  } catch (e) { fail(e); }
};
${'$'}('forget').onclick = async () => {
  try { await automation.call('forget'); toast(T.forgotten); } catch (e) { fail(e); }
};
${'$'}('form').onsubmit = async (event) => {
  event.preventDefault();
  const title = ${'$'}('f-title').value.trim();
  if (!title) return toast(T.needTitle);
  try {
    await automation.call('save', { id: editing || '', title, category: ${'$'}('f-category').value.trim(), body: ${'$'}('f-body').value });
    closeEditor();
    await load();
  } catch (e) { fail(e); }
};

// Locks itself after 5 minutes without a touch.
let idle;
function awake() { clearTimeout(idle); idle = setTimeout(() => automation.close('locked'), 5 * 60 * 1000); }
['pointerdown', 'keydown', 'input'].forEach((e) => document.addEventListener(e, awake, true));
awake();

load().catch(fail);
</script>
</body></html>
    """.trimIndent()
}
