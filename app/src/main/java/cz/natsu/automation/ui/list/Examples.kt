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
        "Nahrát obrázek (multipart)" to "API: vybereš obrázek, zmenší se a pošle se i s popiskem jako formulář (multipart).",
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
            val id = repository.create(example.name, built.blocks)
            repository.get(id)?.let { repository.save(built.triggers(it.copy(icon = example.icon))) }
        }
        return chosen.size
    }

    val all: List<Example> = listOf(
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
    )
}
