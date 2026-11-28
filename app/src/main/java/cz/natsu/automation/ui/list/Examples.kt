package cz.natsu.automation.ui.list

import cz.natsu.automation.data.AutomationEntity
import cz.natsu.automation.data.AutomationRepository
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.ParamValue
import cz.natsu.automation.data.Segment
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.data.richText
import cz.natsu.automation.data.richVar
import cz.natsu.automation.engine.Blocks
import cz.natsu.automation.engine.NotificationBlock
import cz.natsu.automation.i18n.tr
import java.util.UUID

/** Ready-made automations that show what the app can do. */
object Examples {
    // Lookbehind keeps the separator, so tracking parameters that follow each other are all removed.
    private const val TRACKING = "(?<=[?&])(utm_[a-z_]+|fbclid|gclid|si|igsh|ref|ref_src)=[^&#]*&?"

    private fun block(type: String, groupId: String? = null, vararg params: Pair<String, ParamValue>): Block {
        val def = Blocks.get(type)!!
        return def.newBlock(groupId).let { it.copy(params = it.params + params) }
    }

    /** One ready-made automation offered in the "Ukázky" dialog. */
    class Example(
        val name: String,
        val description: String,
        val icon: String,
        /** Builds fresh blocks (new ids) every time it is created. */
        val build: () -> Built,
    )

    class Built(
        val blocks: List<Block>,
        val triggers: (AutomationEntity) -> AutomationEntity = { it },
    )

    private val descriptions = mapOf(
        "VELKÁ PÍSMENA" to "Označený nebo sdílený text převede na velká písmena.",
        "Vyčistit schránku" to "Dlaždice v rychlém nastavení: uklidí mezery a zalomení v textu ve schránce.",
        "Vyčistit odkaz" to "Ze sdíleného odkazu odstraní sledovací parametry (utm_, fbclid…) a zkopíruje ho.",
        "Vyčistit a sdílet odkaz" to "Najde v textu odkaz, rozbalí zkrácený odkaz, vyčistí ho a znovu nabídne Sdílet.",
        "Přeložit do češtiny" to "Otevře označený nebo sdílený text v Google Překladači.",
        "Obrázky z API" to "API: stáhne JSON se seznamem obrázků, zobrazí je a vybraný zkopíruje.",
        "Nadpisy z API (smyčka)" to "API + smyčka: načte příspěvky jako JSON, projde je a poskládá seznam nadpisů.",
        "Odeslat data (POST JSON)" to "API: zeptá se na text, sestaví z něj JSON, pošle ho POSTem a ukáže odpověď serveru.",
        "Hlídat novinky v API" to "Zkusit + Uložit hodnotu: zjistí nejnovější příspěvek z API a ohlásí ho, " +
            "jen když je jiný než minule. Při chybě sítě neskončí chybou. Hodí se s událostí Čas / interval.",
        "Skript: součet košíku" to "Skript: seznam, smyčka, podmínka a výpočet v pár řádcích kódu místo deseti bloků.",
        "Vyber podle: denní doba" to "Vyber podle: zeptá se na denní dobu a podle odpovědi spustí jednu z větví.",
        "Souběžně: dvě API najednou" to "Souběžně: pošle dva požadavky najednou a skriptem z nich složí jednu větu.",
        "Souběžné opakování: pět API najednou" to "Pro každou položku souběžně: stáhne pět příspěvků najednou a složí je " +
            "do seznamu ve správném pořadí.",
        "Souběžný součet z API" to "Změnit proměnnou: pět požadavků najednou a každé kolo přičte své číslo do " +
            "společného součtu, aniž by si ho kola přepsala.",
        "Časovač s pauzou" to "Oznámení s odpočtem a tlačítky Pauza, Pokračovat a Zrušit. Tlačítka pouští tuhle " +
            "automatizaci znovu a ta podle Vstupu pozná, co má udělat.",
        "Oznámení do souboru" to "Každé příchozí oznámení zapíše jako řádek do CSV nebo položku do JSON. Poprvé ji " +
            "spusť ručně: zeptá se na soubor (nový nebo existující) a uloží si ho blokem Uložit hodnotu.",
        "Podmínka výrazem: baterie" to "Pokud / Jinak pokud zapsané výrazem s „and / or“ nad baterií a typem připojení.",
        "Najít místo na mapě (intent)" to "Poslat intent: zeptá se na místo a otevře ho v mapách (akce VIEW, data geo:).",
        "Počkat na Wi-Fi" to "Počkat dokud + Zkusit: čeká až 30 s na Wi-Fi, jinak ohlásí, že se nedočkal.",
        "Nahrát obrázek (multipart)" to "API: vybereš obrázek, zmenší se a pošle se i s popiskem jako formulář (multipart).",
        "Skript: kreslení do obrázku" to "Funkce img_… v bloku Skript: zmenšená ikona s lištou průběhu, kruhový " +
            "průběh přes celý obrázek a ikona zbarvená zespodu podle procent. Lištu ukáže i v oznámení.",
        News.UPDATE_EXAMPLE to "Jednou denně po otevření aplikace zjistí na GitHubu nejnovější vydání. Je-li novější, " +
            "stáhne APK s průběhem na kartě na úvodu (tlačítko Zrušit) a pak nabídne Nainstalovat. Jiný repozitář nastavíš v prvním kroku; bez sítě nebo vydání tiše skončí.",
        "Ikona volného místa" to "Obrázek v automatizaci: ikonu disku obarví podle volného místa (zelená, oranžová, " +
            "červená), napíše pod ni procenta a ukáže ji v oznámení. Místo bloku Úložiště telefonu můžeš číst hodnotu z API.",
        "Widget z HTML: baterie" to "Widget na ploše z HTML: stav baterie velkým číslem s lištou a časem posledního " +
            "překreslení. HTML se vykreslí do obrázku přesně ve velikosti widgetu, klepnutí ho obnoví. " +
            "Přidej ho na plochu: dlouhý stisk plochy → Widgety → Automation.",
        "Widget se seznamem" to "Widget na ploše z bloků: nadpis s tlačítkem, posouvací seznam z bloku Opakovat a " +
            "počet položek v nastavení bloku. Klepnutí na položku widget překreslí a ukáže, co bylo vybráno. " +
            "Přidej ho na plochu: dlouhý stisk plochy → Widgety → Automation.",
        NotepadExample.NAME to "Okno s HTML + šifrování: hesla a poznámky v JSON souboru, název, kategorie i obsah " +
            "zašifrované heslem. Poprvé se zeptá, kam soubor uložit, a nabídne odemykání otiskem prstu.",
    )

    private fun example(name: String, icon: String, build: () -> Built) =
        Example(name, descriptions[name] ?: "", icon, build)

    /** Names of the examples that already exist (by automation name). */
    suspend fun existingNames(repository: AutomationRepository): Set<String> =
        repository.getAll().map { it.name }.toSet()

    /** Creates the chosen examples. Returns how many were added. */
    suspend fun create(repository: AutomationRepository, chosen: List<Example>): Int {
        for (example in chosen) {
            val built = example.build()
            val id = repository.create(tr(example.name), built.blocks)
            repository.get(id)?.let { repository.save(built.triggers(it.copy(icon = example.icon))) }
        }
        return chosen.size
    }

    /** The update from GitHub, written as a script (texts of the card in the language of the app). */
    private fun updateScript(): String {
        fun q(text: String) = "\"" + tr(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return """
            # @name "${News.UPDATE_EXAMPLE}"
            # @icon download
            # @event this_app_opened
            # @condition launch_condition({source: "${cz.natsu.automation.data.LastRun.HOURS_SINCE_RUN}", op: "greater_eq", right: "20"})
            releases = "https://api.github.com/repos/Natsu13/Automation/releases/latest"
            error = try
                release = http_request({url: releases, only_body: true, fail: false})
                latest = json_get(release, "tag_name", "value", "")
            end
            current = app_version()
            if newer_version(latest, current)
                urls = json_get(release, "assets[*].browser_download_url", "value")
                apk = ""
                for url in urls
                    if ends(url, ".apk")
                        apk = url
                    end
                end
                if apk != ""
                    home_card({id: "update", title: ${q("Stahuji verzi ")} & latest, progress: "percent", percent: "0", icon: "download", button1: ${q("Zrušit")}, action1: "stop", closable: false})
                    file = download_file({url: apk, show_card: true, card: "update"})
                    home_card({id: "update", title: ${q("Verze ")} & latest & ${q(" je stažená")}, text: ${q("Teď máš ")} & current, icon: "download", button1: ${q("Později")}, action1: "close", button2: ${q("Nainstalovat")}, action2: "wait"})
                    answer = home_card_wait("update")
                    if answer == ${q("Nainstalovat")}
                        home_card({id: "update", title: ${q("Instaluji verzi ")} & latest, progress: "busy", icon: "download", closable: false})
                        install_apk(file)
                    end
                end
            end
        """.trimIndent()
    }

    val all: List<Example> = listOf(
        example(News.UPDATE_EXAMPLE, "download") {
            val read = cz.natsu.automation.engine.ScriptReader.read(updateScript())
            Built(read.blocks) {
                it.copy(
                    eventsJson = cz.natsu.automation.data.EventJson.encode(read.events),
                    conditionsJson = cz.natsu.automation.data.BlockJson.encode(read.conditions),
                    conditionsAny = read.conditionsAny,
                )
            }
        },

        // 1) Selected text -> UPPER CASE (replaces the selection)
        example("VELKÁ PÍSMENA", "text") {
            val upper = block("change_case", null, "source" to richVar(VarRef.Input), "mode" to ParamValue.Choice("upper"))
            Built(
                listOf(upper, block("return", null, "text" to richVar(VarRef.BlockOutput(upper.id))))
            ) { it.copy(triggerProcessText = true, triggerShare = true) }
        },

        // 2) Clean the clipboard text (tile)
        example("Vyčistit schránku", "paste") {
            val clean = block("clean_text", null, "source" to richVar(VarRef.Clipboard), "mode" to ParamValue.Choice("all"))
            Built(
                listOf(
                    clean,
                    block("set_clipboard", null, "text" to richVar(VarRef.BlockOutput(clean.id))),
                    block("toast", null, "text" to richText("Schránka vyčištěna ✨")),
                )
            ) { it.copy(triggerTile = true) }
        },

        // 3) Links: strip tracking parameters and copy, otherwise say it is not a link
        example("Vyčistit odkaz", "link") {
            val group = UUID.randomUUID().toString()
            val stripped = block(
                "replace", null,
                "source" to richVar(VarRef.Input),
                "find" to richText(TRACKING),
                "replacement" to richText(""),
                "regex" to ParamValue.Bool(true),
            )
            val tidy = block(
                "replace", null,
                "source" to richVar(VarRef.BlockOutput(stripped.id)),
                "find" to richText("[?&]+(?=#|$)"),
                "replacement" to richText(""),
                "regex" to ParamValue.Bool(true),
            )
            Built(
                listOf(
                    block("if", group, "left" to richVar(VarRef.Input), "op" to ParamValue.Choice("starts"), "right" to richText("http")),
                    stripped,
                    tidy,
                    block("set_clipboard", null, "text" to richVar(VarRef.BlockOutput(tidy.id))),
                    block("toast", null, "text" to richText("Odkaz bez sledování zkopírován")),
                    block("else", group),
                    block(
                        "toast", null,
                        "text" to ParamValue.Rich(listOf(Segment.Text("Tohle není odkaz: "), Segment.Var(VarRef.Input)))
                    ),
                    block("end_if", group),
                )
            ) { it.copy(triggerShare = true, triggerProcessText = true) }
        },

        // 4) Shared text -> find the link -> expand short link -> strip tracking -> open the share sheet again
        example("Vyčistit a sdílet odkaz", "link") {
            val group = UUID.randomUUID().toString()
            val found = block(
                "extract", null,
                "source" to richVar(VarRef.Input),
                "what" to ParamValue.Choice("url"),
                "which" to ParamValue.Choice("first"),
            )
            val expanded = block(
                "web", null,
                "url" to richVar(VarRef.BlockOutput(found.id)),
                "what" to ParamValue.Choice("final_url"),
            )
            val stripped = block(
                "replace", null,
                "source" to richVar(VarRef.BlockOutput(expanded.id)),
                "find" to richText(TRACKING),
                "replacement" to richText(""),
                "regex" to ParamValue.Bool(true),
            )
            val tidy = block(
                "replace", null,
                "source" to richVar(VarRef.BlockOutput(stripped.id)),
                "find" to richText("[?&]+(?=#|$)"),
                "replacement" to richText(""),
                "regex" to ParamValue.Bool(true),
            )
            Built(
                listOf(
                    found,
                    block("if", group, "left" to richVar(VarRef.BlockOutput(found.id)), "op" to ParamValue.Choice("empty")),
                    block("toast", null, "text" to richText("V textu není žádný odkaz")),
                    block("else", group),
                    expanded,
                    stripped,
                    tidy,
                    block(
                        "share", null,
                        "text" to richVar(VarRef.BlockOutput(tidy.id)),
                        "title" to richText("Sdílet vyčištěný odkaz"),
                    ),
                    block("end_if", group),
                )
            ) { it.copy(triggerShare = true, triggerProcessText = true) }
        },

        // 5) Translate selected / shared text in Google Translate
        example("Přeložit do češtiny", "translate") {
            val encoded = block("convert", null, "source" to richVar(VarRef.Input), "mode" to ParamValue.Choice("url_encode"))
            Built(
                listOf(
                    encoded,
                    block(
                        "open_url", null,
                        "url" to ParamValue.Rich(
                            listOf(
                                Segment.Text("https://translate.google.com/?sl=auto&tl=cs&text="),
                                Segment.Var(VarRef.BlockOutput(encoded.id)),
                            )
                        )
                    ),
                )
            ) { it.copy(triggerShare = true, triggerProcessText = true) }
        },

        // 6) API: download JSON, take the list of image URLs, show them and let one be picked
        example("Obrázky z API", "code") {
            val request = block(
                "http_request", null,
                "url" to richText("https://dog.ceo/api/breeds/image/random/4"),
            )
            val urls = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body.message"),
            )
            val shown = block(
                "show_images", null,
                "title" to richText("Vyber pejska"),
                "images" to richVar(VarRef.BlockOutput(urls.id)),
                "pick" to ParamValue.Bool(true),
            )
            Built(
                listOf(
                    request, urls, shown,
                    block("set_clipboard", null, "text" to richVar(VarRef.BlockOutput(shown.id))),
                    block("toast", null, "text" to richText("Adresa obrázku zkopírována")),
                )
            )
        },


        // 7) API + loop: titles of posts
        example("Nadpisy z API (smyčka)", "code") {
            val request = block(
                "http_request", null,
                "url" to richText("https://jsonplaceholder.typicode.com/posts?_limit=5"),
            )
            val titles = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body[*].title"),
            )
            val group = UUID.randomUUID().toString()
            val each = block("for_each", group, "list" to richVar(VarRef.BlockOutput(titles.id)))
            Built(
                listOf(
                    request, titles,
                    block("set_variable", null, "name" to richText("nadpisy"), "value" to richText("")),
                    each,
                    block(
                        "set_variable", null,
                        "name" to richText("nadpisy"),
                        "value" to ParamValue.Rich(
                            listOf(
                                Segment.Var(VarRef.Named("nadpisy")),
                                Segment.Text("• "),
                                Segment.Var(VarRef.BlockOutput(each.id)),
                                Segment.Text("\n"),
                            )
                        ),
                    ),
                    block("end_while", group),
                    block(
                        "show_text", null,
                        "title" to richText("Nadpisy příspěvků"),
                        "text" to richVar(VarRef.Named("nadpisy")),
                    ),
                )
            )
        },

        // 8) API: send JSON with POST and show what the server got
        example("Odeslat data (POST JSON)", "code") {
            val ask = block(
                "ask_text", null,
                "title" to richText("Co poslat na server?"),
                "initial" to richText("Ahoj z Automation"),
            )
            val json = block(
                "json_build", null,
                "items" to ParamValue.Rich(
                    listOf(
                        Segment.Text("zprava: "),
                        Segment.Var(VarRef.BlockOutput(ask.id)),
                        Segment.Text("\nodeslano: "),
                        Segment.Var(VarRef.CurrentDate),
                        Segment.Text("\nverze: 2"),
                    )
                ),
            )
            val request = block(
                "http_request", null,
                "method" to ParamValue.Choice("POST"),
                "url" to richText("https://httpbin.org/post"),
                "body_type" to ParamValue.Choice("json"),
                "body" to richVar(VarRef.BlockOutput(json.id)),
            )
            val status = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("status"),
            )
            val echoed = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body.json"),
                "what" to ParamValue.Choice("pretty"),
            )
            Built(
                listOf(
                    ask, json, request, status, echoed,
                    block(
                        "show_text", null,
                        "title" to ParamValue.Rich(listOf(Segment.Text("Server odpověděl "), Segment.Var(VarRef.BlockOutput(status.id)))),
                        "text" to richVar(VarRef.BlockOutput(echoed.id)),
                    ),
                )
            )
        },

        // 10) Try + stored value: report only what is new since the last run
        example("Hlídat novinky v API", "code") {
            val attempt = UUID.randomUUID().toString()
            val changed = UUID.randomUUID().toString()
            val tryBlock = block("try", attempt, "attempts" to ParamValue.Number(2f), "delay" to ParamValue.Number(3f))
            val request = block(
                "http_request", null,
                "url" to richText("https://jsonplaceholder.typicode.com/posts?_sort=id&_order=desc&_limit=1"),
            )
            val newest = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body[0].id"),
            )
            val title = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body[0].title"),
            )
            val last = block("store_get", null, "key" to richText("posledni_prispevek"))
            Built(
                listOf(
                    tryBlock,
                    request, newest, title, last,
                    block(
                        "if", changed,
                        "left" to richVar(VarRef.BlockOutput(newest.id)),
                        "op" to ParamValue.Choice("not_equals"),
                        "right" to richVar(VarRef.BlockOutput(last.id)),
                    ),
                    block(
                        "toast", null,
                        "text" to ParamValue.Rich(listOf(Segment.Text("Nový příspěvek: "), Segment.Var(VarRef.BlockOutput(title.id)))),
                    ),
                    block(
                        "store_set", null,
                        "key" to richText("posledni_prispevek"),
                        "value" to richVar(VarRef.BlockOutput(newest.id)),
                    ),
                    block("else", changed),
                    block("toast", null, "text" to richText("Nic nového")),
                    block("end_if", changed),
                    block("catch", attempt),
                    block(
                        "toast", null,
                        "text" to ParamValue.Rich(listOf(Segment.Text("API nedostupné: "), Segment.Var(VarRef.BlockOutput(tryBlock.id)))),
                    ),
                    block("end_try", attempt),
                )
            )
        },

        // 9) API: upload a picked image with a caption (multipart)
        example("Nahrát obrázek (multipart)", "code") {
            val picked = block("pick_file", null, "kind" to ParamValue.Choice("image/*"))
            // Servers often refuse big photos – make it smaller first.
            val smaller = block(
                "image_edit", null,
                "source" to richVar(VarRef.BlockOutput(picked.id)),
                "op" to ParamValue.Choice("resize"),
                "max_size" to ParamValue.Number(1280f),
            )
            val caption = block(
                "ask_text", null,
                "title" to richText("Popisek obrázku"),
                "initial" to richText("Fotka z Automation"),
            )
            val request = block(
                "http_request", null,
                "method" to ParamValue.Choice("POST"),
                "url" to richText("https://httpbin.org/post"),
                "body_type" to ParamValue.Choice("multipart"),
                "fail" to ParamValue.Bool(false),
                "fields" to ParamValue.Rich(
                    listOf(
                        Segment.Text("popisek="),
                        Segment.Var(VarRef.BlockOutput(caption.id)),
                        Segment.Text("\nobrazek=@"),
                        Segment.Var(VarRef.BlockOutput(smaller.id)),
                    )
                ),
            )
            val status = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("status"),
            )
            val files = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body.files"),
                "what" to ParamValue.Choice("keys"),
            )
            val sentCaption = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("body.form.popisek"),
            )
            Built(
                listOf(
                    picked, smaller, caption, request, status, files, sentCaption,
                    block(
                        "show_text", null,
                        "title" to ParamValue.Rich(listOf(Segment.Text("Server odpověděl "), Segment.Var(VarRef.BlockOutput(status.id)))),
                        "text" to ParamValue.Rich(
                            listOf(
                                Segment.Text("Server přijal soubory: "),
                                Segment.Var(VarRef.BlockOutput(files.id)),
                                Segment.Text("\nPopisek: "),
                                Segment.Var(VarRef.BlockOutput(sentCaption.id)),
                            )
                        ),
                    ),
                )
            )
        },

        // ---------- the newer blocks ----------

        // Script: a few lines of code instead of a row of blocks
        example("Skript: součet košíku", "code") {
            val script = block(
                "script", null,
                "code" to richText(
                    "# " + tr("Seznam objektů, smyčka, výpočet a složení textu") + "\n" +
                        "items = [{name: \"" + tr("Chleba") + "\", price: 42}, {name: \"" + tr("Sýr") + "\", price: 89.9}, " +
                        "{name: \"" + tr("Víno") + "\", price: 219}]\n" +
                        "total = 0\n" +
                        "for item in items\n" +
                        "    total += item.price\n" +
                        "end\n" +
                        "if total > 300\n" +
                        "    total = total * 0.9   # " + tr("sleva 10 %") + "\n" +
                        "end\n" +
                        "return \"" + tr("Položek") + ": \" & len(items) & \", " + tr("celkem") + " \" & round(total, 2)"
                ),
            )
            Built(
                listOf(
                    block("comment", null, "text" to richText(tr("Celý výpočet je v jednom bloku Skript. Otevři ho a uprav čísla."))),
                    script,
                    block("show_text", null, "title" to richText(tr("Košík")), "text" to richVar(VarRef.BlockOutput(script.id))),
                )
            )
        },

        // Choose by: one value, several branches
        example("Vyber podle: denní doba", "light") {
            val group = UUID.randomUUID().toString()
            val morning = tr("Ráno")
            val noon = tr("Poledne")
            val afternoon = tr("Odpoledne")
            val evening = tr("Večer")
            val picked = block(
                "choose", null,
                "title" to richText(tr("Jaká je denní doba?")),
                "options" to richText(listOf(morning, noon, afternoon, evening).joinToString("\n")),
            )
            Built(
                listOf(
                    picked,
                    block("switch", group, "value" to richVar(VarRef.BlockOutput(picked.id))),
                    block("case", group, "value" to richText(morning)),
                    block("toast", null, "text" to richText(tr("Dobré ráno ☕"))),
                    block("case", group, "value" to richText("$noon\n$afternoon")),
                    block("toast", null, "text" to richText(tr("Hezký den ☀️"))),
                    block("else", group),
                    block("toast", null, "text" to richText(tr("Dobrý večer 🌙"))),
                    block("end_switch", group),
                )
            )
        },

        // In parallel: two requests at once, then a script that joins them
        example("Souběžně: dvě API najednou", "sync") {
            val group = UUID.randomUUID().toString()
            val post = block(
                "http_request", null,
                "url" to richText("https://jsonplaceholder.typicode.com/posts/1"),
                "only_body" to ParamValue.Bool(true),
            )
            val user = block(
                "http_request", null,
                "url" to richText("https://jsonplaceholder.typicode.com/users/1"),
                "only_body" to ParamValue.Bool(true),
            )
            val joined = block(
                "script", null,
                "code" to ParamValue.Rich(
                    listOf(
                        Segment.Text("post = "), Segment.Var(VarRef.BlockOutput(post.id)),
                        Segment.Text("\nuser = "), Segment.Var(VarRef.BlockOutput(user.id)),
                        Segment.Text("\nreturn user.name & \": \" & post.title"),
                    )
                ),
            )
            Built(
                listOf(
                    block("parallel", group),
                    post,
                    block("parallel_branch", group),
                    user,
                    block("end_parallel", group),
                    joined,
                    block("show_text", null, "title" to richText(tr("Obě odpovědi")), "text" to richVar(VarRef.BlockOutput(joined.id))),
                )
            )
        },

        // A loop whose rounds run at the same time; the results come back in the order of the items
        example("Souběžné opakování: pět API najednou", "repeat") {
            val group = UUID.randomUUID().toString()
            val each = block(
                "for_each", group,
                "list" to richText("1\n2\n3\n4\n5"),
                "parallel" to ParamValue.Bool(true),
                "collect" to richText("radky"),
            )
            val request = block(
                "http_request", null,
                "url" to ParamValue.Rich(
                    listOf(Segment.Text("https://jsonplaceholder.typicode.com/posts/"), Segment.Var(VarRef.BlockOutput(each.id)))
                ),
                "only_body" to ParamValue.Bool(true),
            )
            val title = block("json_get", null, "source" to richVar(VarRef.BlockOutput(request.id)), "path" to richText("title"))
            val position = block("loop_index")
            val line = block(
                "text", null,
                "text" to ParamValue.Rich(
                    listOf(
                        Segment.Var(VarRef.BlockOutput(position.id)), Segment.Text(". "),
                        Segment.Var(VarRef.BlockOutput(title.id)),
                    )
                ),
            )
            val joined = block("script", null, "code" to richText("return join(radky, \"\\n\")"))
            Built(
                listOf(
                    each, request, title, position, line,
                    block("end_while", group),
                    joined,
                    block("show_text", null, "title" to richText(tr("Nadpisy příspěvků")), "text" to richVar(VarRef.BlockOutput(joined.id))),
                )
            )
        },

        // Rounds running at the same time add up into one variable
        example("Souběžný součet z API", "repeat") {
            val group = UUID.randomUUID().toString()
            val each = block(
                "for_each", group,
                "list" to richText("1\n2\n3\n4\n5"),
                "parallel" to ParamValue.Bool(true),
            )
            val request = block(
                "http_request", null,
                "url" to ParamValue.Rich(
                    listOf(Segment.Text("https://jsonplaceholder.typicode.com/posts/"), Segment.Var(VarRef.BlockOutput(each.id)))
                ),
                "only_body" to ParamValue.Bool(true),
            )
            val number = block("json_get", null, "source" to richVar(VarRef.BlockOutput(request.id)), "path" to richText("id"))
            Built(
                listOf(
                    each, request, number,
                    block(
                        "change_variable", null,
                        "name" to richText("soucet"),
                        "value" to richVar(VarRef.BlockOutput(number.id)),
                    ),
                    block("end_while", group),
                    block(
                        "show_text", null,
                        "title" to richText(tr("Součet")),
                        "text" to ParamValue.Rich(listOf(Segment.Var(VarRef.Named("soucet")))),
                    ),
                )
            )
        },

        // A countdown in a notification; its buttons run this automation again with another input
        example("Časovač s pauzou", "timer") {
            val group = UUID.randomUUID().toString()
            val name = "casovac"
            // The id of the automation is known only after it is stored: see the end.
            val self = ParamValue.Choice("")
            fun button(index: Int, label: String, input: String) = arrayOf(
                "b${index}_action" to ParamValue.Choice("run"),
                "b${index}_label" to richText(tr(label)),
                "b${index}_automation" to self,
                "b${index}_input" to richText(input),
            )
            fun running(countdown: ParamValue) = block(
                "notification", null,
                "title" to richText(tr("Časovač")), "text" to richText(tr("Běží…")), "name" to richText(name),
                "countdown" to countdown, "end_automation" to self, "end_input" to richText("konec"),
                *button(1, "Pauza", "pauza"), *button(2, "Zrušit", "zrusit"),
            )
            fun whenInput(type: String, value: String) = block(
                type, group, "left" to richVar(VarRef.Input), "op" to ParamValue.Choice("equals"), "right" to richText(value)
            )
            val remaining = block("notification_remaining", null, "name" to richText(name))
            val stored = block("store_get", null, "key" to richText("casovac_zbyva"), "fallback" to richText("60"))
            Built(
                listOf(
                    whenInput("if", "pauza"),
                    remaining,
                    block("store_set", null, "key" to richText("casovac_zbyva"), "value" to richVar(VarRef.BlockOutput(remaining.id))),
                    block(
                        "notification", null,
                        "title" to richText(tr("Časovač")),
                        "text" to ParamValue.Rich(
                            listOf(Segment.Text(tr("Pozastaveno, zbývá sekund: ")), Segment.Var(VarRef.BlockOutput(remaining.id)))
                        ),
                        "name" to richText(name), "quiet" to ParamValue.Bool(true),
                        *button(1, "Pokračovat", "pokracovat"), *button(2, "Zrušit", "zrusit"),
                    ),
                    whenInput("else_if", "pokracovat"),
                    stored,
                    running(richVar(VarRef.BlockOutput(stored.id))),
                    whenInput("else_if", "zrusit"),
                    block("notification_cancel", null, "name" to richText(name)),
                    block("toast", null, "text" to richText(tr("Časovač zrušen"))),
                    whenInput("else_if", "konec"),
                    block(
                        "notification", null,
                        "title" to richText(tr("Čas vypršel")), "text" to richText(tr("Časovač doběhl.")),
                        "b1_action" to ParamValue.Choice("dismiss"), "b1_label" to richText("OK"),
                    ),
                    block("else", group),
                    running(richText("5:00")),
                    block("end_if", group),
                )
            ) { stored ->
                // Now the buttons and the end of the countdown can point at this very automation.
                val id = ParamValue.Choice(stored.id.toString())
                stored.copy(
                    blocksJson = cz.natsu.automation.data.BlockJson.encode(stored.blocks.map { block ->
                        block.copy(params = block.params.mapValues { (key, value) -> if (key.endsWith("automation")) id else value })
                    })
                )
            }
        },

        // Every notification becomes a record in a file; the file is asked for once and kept as a stored value
        example("Oznámení do souboru", "file") {
            val group = UUID.randomUUID().toString()
            val key = "soubor_oznameni"
            val stored = block("store_get", null, "key" to richText(key))
            val picked = block(cz.natsu.automation.engine.FileBlocks.PICK, null, "name" to richText("oznameni"))
            fun value(name: String, ref: VarRef) = listOf(Segment.Text("$name="), Segment.Var(ref), Segment.Text("\n"))
            Built(
                listOf(
                    stored,
                    block("if", group, "left" to richVar(VarRef.BlockOutput(stored.id)), "op" to ParamValue.Choice("empty")),
                    picked,
                    block("store_set", null, "key" to richText(key), "value" to richVar(VarRef.BlockOutput(picked.id))),
                    block("toast", null, "text" to richText(tr("Soubor je vybraný, oznámení se do něj budou zapisovat"))),
                    // Started by hand later on there is no notification to write.
                    block("else_if", group, "left" to richVar(VarRef.Event("notif_package")), "op" to ParamValue.Choice("not_empty")),
                    block(
                        "add_record", null,
                        "file" to richVar(VarRef.BlockOutput(stored.id)),
                        "values" to ParamValue.Rich(
                            value("cas", VarRef.CurrentDate) + value("aplikace", VarRef.Event("notif_app")) +
                                value("nadpis", VarRef.Event("notif_title")) + value("text", VarRef.Event("notif_text"))
                        ),
                    ),
                    block("end_if", group),
                )
            ) {
                it.copy(
                    eventsJson = cz.natsu.automation.data.EventJson.encode(
                        listOf(cz.natsu.automation.data.EventTrigger(cz.natsu.automation.data.EventType.Notification.key))
                    )
                )
            }
        },

        // A condition written as an expression, with "otherwise if"
        example("Podmínka výrazem: baterie", "battery") {
            val group = UUID.randomUUID().toString()
            val level = block("battery", null, "info" to ParamValue.Choice("level"))
            val net = block("network", null, "info" to ParamValue.Choice("type"))
            fun expression(vararg parts: Any) = ParamValue.Rich(parts.map {
                if (it is Block) Segment.Var(VarRef.BlockOutput(it.id)) else Segment.Text(it.toString())
            })
            Built(
                listOf(
                    level,
                    net,
                    block("if", group, "mode" to ParamValue.Choice("expert"), "expression" to expression(level, " < 30 and ", net, " != \"wifi\"")),
                    block("toast", null, "text" to richText(tr("Málo baterie a jsi na datech – šetři."))),
                    block("else_if", group, "mode" to ParamValue.Choice("expert"), "expression" to expression(level, " < 30 or ", level, " > 95")),
                    block("toast", null, "text" to expression(tr("Baterie je na kraji: "), level, " %")),
                    block("else", group),
                    block("toast", null, "text" to expression(tr("Baterie v pohodě: "), level, " %")),
                    block("end_if", group),
                )
            )
        },

        // Another app: an intent with an action and data
        example("Najít místo na mapě (intent)", "map") {
            val place = block("ask_text", null, "title" to richText(tr("Jaké místo hledáš?")), "initial" to richText("Praha"))
            Built(
                listOf(
                    place,
                    block(
                        "send_intent", null,
                        "target" to ParamValue.Choice("activity"),
                        "action" to richText("android.intent.action.VIEW"),
                        "data" to ParamValue.Rich(listOf(Segment.Text("geo:0,0?q="), Segment.Var(VarRef.BlockOutput(place.id)))),
                    ),
                )
            )
        },

        // An image stored in the automation: tinted by state, with a text under it, shown in a notification
        example("Ikona volného místa", "storage") {
            val group = UUID.randomUUID().toString()
            val free = block("storage", null, "info" to ParamValue.Choice("free_percent"))
            fun expression(vararg parts: Any) = ParamValue.Rich(parts.map {
                if (it is Block) Segment.Var(VarRef.BlockOutput(it.id)) else Segment.Text(it.toString())
            })
            fun color(value: String) = block("set_variable", null, "name" to richText("barva"), "value" to richText(value))
            val colorRef = richVar(VarRef.Named("barva"))
            val icon = ExampleImages.STORAGE_ICON
            val tinted = block(
                "image_edit", null,
                "source" to richText(icon),
                "op" to ParamValue.Choice("tint"),
                "color" to colorRef,
            )
            val labelled = block(
                "image_text", null,
                "source" to richVar(VarRef.BlockOutput(tinted.id)),
                "text" to expression(free, " %"),
                "position" to ParamValue.Choice("below"),
                "color" to colorRef,
                "size" to ParamValue.Number(35f),
            )
            Built(
                listOf(
                    free,
                    block("if", group, "mode" to ParamValue.Choice("expert"), "expression" to expression(free, " < 10")),
                    color("#E53935"),
                    block("else_if", group, "mode" to ParamValue.Choice("expert"), "expression" to expression(free, " < 25")),
                    color("#FB8C00"),
                    block("else", group),
                    color("#43A047"),
                    block("end_if", group),
                    tinted,
                    labelled,
                    block(
                        NotificationBlock.TYPE, null,
                        "title" to richText(tr("Volné místo")),
                        "text" to expression(tr("V telefonu zbývá "), free, tr(" % místa.")),
                        "quiet" to ParamValue.Bool(true),
                        "name" to richText("volne_misto"),
                        "image" to richVar(VarRef.BlockOutput(labelled.id)),
                        "small_icon" to ParamValue.Choice("same"),
                        "b1_action" to ParamValue.Choice("none"),
                    ),
                )
            )
        },

        // Drawing into an image in the Script block: three ways to show progress
        example("Skript: kreslení do obrázku", "brush") {
            val free = block("storage", null, "info" to ParamValue.Choice("free_percent"))
            val icon = block("image", null, "source" to richText(ExampleImages.STORAGE_ICON))
            fun code(vararg parts: Any) = ParamValue.Rich(parts.map {
                if (it is Block) Segment.Var(VarRef.BlockOutput(it.id)) else Segment.Text(it.toString())
            })
            val script = block(
                "script", null,
                "code" to code(
                    "free = ", free, "\n",
                    "icon = ", icon, "\n",
                    "# " + tr("1) zmenšená ikona a pod ní lišta průběhu") + "\n",
                    "bar = img_draw(img_new(192, 192), icon, 46, 0, 100)\n",
                    "bar = img_bar(bar, 12, 116, 168, 64, free, \"#43A047\", \"#55FFFFFF\", 16)\n",
                    "bar = img_text(bar, free & \" %\", 96, 148, 36, \"white\")\n",
                    "# " + tr("2) poloprůhledný čtverec s kruhovým průběhem přes celý obrázek") + "\n",
                    "ring = img_rect(icon, 0, 0, 192, 192, \"#AA000000\", 32)\n",
                    "ring = img_arc(ring, 96, 96, 72, free, \"#FB8C00\", 16, \"#44FFFFFF\")\n",
                    "ring = img_text(ring, free & \" %\", 96, 96, 40, \"white\")\n",
                    "# " + tr("3) ikona zespodu v barvě podle procent, zbytek bílý") + "\n",
                    "fill = img_fill(icon, free, \"#43A047\", \"up\", \"white\")\n",
                    "return [bar, ring, fill]",
                ),
            )
            Built(
                listOf(
                    free,
                    icon,
                    script,
                    block("show_images", null, "title" to richText(tr("Tři způsoby průběhu")), "images" to richVar(VarRef.BlockOutput(script.id))),
                    block(
                        NotificationBlock.TYPE, null,
                        "title" to richText(tr("Volné místo")),
                        "text" to code(tr("V telefonu zbývá "), free, tr(" % místa.")),
                        "quiet" to ParamValue.Bool(true),
                        "name" to richText("kresleni_obrazku"),
                        "image" to richVar(VarRef.Named("bar")),
                        "b1_action" to ParamValue.Choice("none"),
                    ),
                )
            )
        },

        example(NotepadExample.NAME, "lock") {
            Built(cz.natsu.automation.engine.ScriptReader.read(NotepadExample.script()).blocks)
        },

        // Wait until + try: wait for Wi-Fi, give up with an error that is caught
        example("Počkat na Wi-Fi", "wifi") {
            val group = UUID.randomUUID().toString()
            val attempt = block("try", group)
            Built(
                listOf(
                    attempt,
                    block(
                        "wait_until", null,
                        "left" to richVar(VarRef.Event("network")),
                        "op" to ParamValue.Choice("equals"),
                        "right" to richText("wifi"),
                        "timeout" to ParamValue.Number(30f),
                        "fail" to ParamValue.Bool(true),
                    ),
                    block("toast", null, "text" to richText(tr("Wi-Fi je připojená ✔"))),
                    block("catch", group),
                    block("toast", null, "text" to richText(tr("Do 30 s se Wi-Fi nepřipojila."))),
                    block("end_try", group),
                )
            )
        },
        // A home screen widget built from blocks; the list items come from a "Repeat" loop
        example("Widget se seznamem", "checklist") {
            val build = UUID.randomUUID().toString()
            val header = UUID.randomUUID().toString()
            val list = UUID.randomUUID().toString()
            val loop = UUID.randomUUID().toString()
            val item = UUID.randomUUID().toString()
            val repeat = block("repeat", loop, "count" to richText("30"))
            val round = Segment.Var(VarRef.BlockOutput(repeat.id))
            Built(
                listOf(
                    block("widget_build", build),
                    block(
                        "widget_box", header,
                        "direction" to ParamValue.Choice("row"), "size" to ParamValue.Choice("match"),
                        "align" to ParamValue.Choice("center"),
                    ),
                    block(
                        "widget_text", null,
                        "text" to richText(tr("Seznam")), "bold" to ParamValue.Bool(true), "text_size" to ParamValue.Number(18f),
                    ),
                    block("widget_space", null, "space" to ParamValue.Number(0f), "fill" to ParamValue.Bool(true)),
                    block(
                        "widget_button", null,
                        "text" to richText(tr("Obnovit")), "tap" to ParamValue.Choice("refresh"), "tap_input" to richText(""),
                    ),
                    block("end_widget_box", header),
                    block(
                        "widget_text", null,
                        "text" to ParamValue.Rich(listOf(Segment.Text(tr("Vybráno: ")), Segment.Var(VarRef.Input))),
                        "text_size" to ParamValue.Number(12f),
                    ),
                    block("widget_space", null, "space" to ParamValue.Number(6f)),
                    block("widget_list", list, "spacing" to ParamValue.Number(6f)),
                    repeat,
                    block(
                        "widget_box", item,
                        "direction" to ParamValue.Choice("row"), "size" to ParamValue.Choice("match"),
                        "background" to richText("#5C6BC0"), "padding" to ParamValue.Number(10f),
                        "tap" to ParamValue.Choice("refresh"),
                        "tap_input" to ParamValue.Rich(listOf(Segment.Text(tr("položka ")), round)),
                    ),
                    block(
                        "widget_text", null,
                        "text" to ParamValue.Rich(listOf(Segment.Text(tr("Položka ")), round)), "color" to richText("white"),
                    ),
                    block("end_widget_box", item),
                    block("end_while", loop),
                    block("end_widget_list", list),
                    block("end_widget_build", build),
                )
            ) { it.copy(triggerWidget = true) }
        },
        // A home screen widget drawn from HTML: the battery level, refreshed on a tap
        example("Widget z HTML: baterie", "battery") {
            val level = block("battery", null, "info" to ParamValue.Choice("level"))
            val battery = Segment.Var(VarRef.BlockOutput(level.id))
            Built(
                listOf(
                    level,
                    block(
                        "widget_set", null,
                        "html" to ParamValue.Rich(
                            listOf(
                                Segment.Text("<html><body style=\"margin:0;font-family:sans-serif\"><div style=\"box-sizing:border-box;height:100vh;padding:16px;border-radius:24px;background:linear-gradient(135deg,#3949ab,#8e24aa);color:#fff;display:flex;flex-direction:column;justify-content:space-between\"><div style=\"font-size:13px;opacity:.8\" id=\"time\"></div><div><div style=\"font-size:40px;font-weight:bold\">"),
                                battery,
                                Segment.Text(" %</div><div style=\"font-size:13px;opacity:.8\">BATTERY_WORD</div></div><div style=\"height:8px;border-radius:4px;background:rgba(255,255,255,.25)\"><div style=\"height:100%;border-radius:4px;background:#fff;width:".replace("BATTERY_WORD", tr("baterie"))),
                                battery,
                                Segment.Text("%\"></div></div></div><script>document.getElementById(\"time\").textContent = new Date().toLocaleTimeString([], {hour: \"2-digit\", minute: \"2-digit\"})</script></body></html>"),
                            )
                        ),
                        "tap" to ParamValue.Choice("refresh"),
                    ),
                )
            ) { it.copy(triggerWidget = true) }
        },
    )


}
