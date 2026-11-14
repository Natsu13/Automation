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

    /** Creates the examples that don't exist yet (matched by name). Returns how many were added. */
    suspend fun create(repository: AutomationRepository): Int {
        val existing = repository.getAll().map { it.name }.toSet()
        var added = 0

        suspend fun add(
            name: String,
            icon: String,
            blocks: List<Block>,
            triggers: (AutomationEntity) -> AutomationEntity,
        ) {
            if (name in existing) return
            val id = repository.create(name, blocks)
            repository.get(id)?.let { repository.save(triggers(it.copy(icon = icon))) }
            added++
        }

        // 1) Selected text -> UPPER CASE (replaces the selection)
        run {
            val upper = block("change_case", null, "source" to richVar(VarRef.Input), "mode" to ParamValue.Choice("upper"))
            add(
                "VELKÁ PÍSMENA", "text",
                listOf(upper, block("return", null, "text" to richVar(VarRef.BlockOutput(upper.id))))
            ) { it.copy(triggerProcessText = true, triggerShare = true) }
        }

        // 2) Clean the clipboard text (tile)
        run {
            val clean = block("clean_text", null, "source" to richVar(VarRef.Clipboard), "mode" to ParamValue.Choice("all"))
            add(
                "Vyčistit schránku", "paste",
                listOf(
                    clean,
                    block("set_clipboard", null, "text" to richVar(VarRef.BlockOutput(clean.id))),
                    block("toast", null, "text" to richText("Schránka vyčištěna ✨")),
                )
            ) { it.copy(triggerTile = true) }
        }

        // 3) Links: strip tracking parameters and copy, otherwise say it is not a link
        run {
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
            add(
                "Vyčistit odkaz", "link",
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
        }

        // 4) Shared text -> find the link -> expand short link -> strip tracking -> open the share sheet again
        run {
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
            add(
                "Vyčistit a sdílet odkaz", "link",
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
        }

        // 5) Translate selected / shared text in Google Translate
        run {
            val encoded = block("convert", null, "source" to richVar(VarRef.Input), "mode" to ParamValue.Choice("url_encode"))
            add(
                "Přeložit do češtiny", "translate",
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
        }

        // 6) API: download JSON, take the list of image URLs, show them and let one be picked
        run {
            val request = block(
                "http_request", null,
                "url" to richText("https://dog.ceo/api/breeds/image/random/4"),
            )
            val urls = block(
                "json_get", null,
                "source" to richVar(VarRef.BlockOutput(request.id)),
                "path" to richText("message"),
            )
            val shown = block(
                "show_images", null,
                "title" to richText("Vyber pejska"),
                "images" to richVar(VarRef.BlockOutput(urls.id)),
                "pick" to ParamValue.Bool(true),
            )
            add(
                "Obrázky z API", "code",
                listOf(
                    request, urls, shown,
                    block("set_clipboard", null, "text" to richVar(VarRef.BlockOutput(shown.id))),
                    block("toast", null, "text" to richText("Adresa obrázku zkopírována")),
                )
            ) { it }
        }

        return added
    }
}
