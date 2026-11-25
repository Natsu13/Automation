package cz.natsu.automation.engine

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Web
import cz.natsu.automation.data.Block
import cz.natsu.automation.data.VarRef
import cz.natsu.automation.i18n.tr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An open "Okno s HTML": the page talks to the automation through `automation.call(action, data)`,
 * which runs the steps inside the block and answers with their result. The UI shows the page
 * and hands its calls over here; this side does not know anything about WebViews.
 */
class HtmlWindow(
    val title: String,
    val html: String,
    /** The page may load things from the internet (otherwise it is cut off from it). */
    val internet: Boolean,
    /** No screenshots or screen recording of the window, and it is blank among recent apps. */
    val secure: Boolean,
    /** Slides up from the bottom to half of the screen instead of covering it. */
    val sheet: Boolean = false,
    /** The bar with the title and Close also when there is no title. */
    val bar: Boolean = false,
) {
    class Call(val id: String, val action: String, val data: String)

    /** Calls of the page, handled one after another. */
    val calls = Channel<Call>(Channel.UNLIMITED)

    /** Completed with the value the page closes the window with ("" = closed by the user). */
    val closed = CompletableDeferred<String?>()

    /** Runs JavaScript in the page; set by the UI once the page has loaded. Until then scripts wait. */
    private var page: ((String) -> Unit)? = null
    private val waiting = mutableListOf<String>()

    @Synchronized
    fun attach(run: ((String) -> Unit)?) {
        page = run
        if (run != null) {
            waiting.forEach(run)
            waiting.clear()
        }
    }

    @Synchronized
    private fun eval(js: String) {
        page?.invoke(js) ?: waiting.add(js)
    }

    /** Called from the page (any thread). */
    fun call(id: String, action: String, data: String) {
        calls.trySend(Call(id, action, data))
    }

    fun close(value: String?) {
        closed.complete(value)
    }

    fun reply(id: String, ok: Boolean, value: String) =
        eval("window.automation && automation._reply(${js(id)}, $ok, ${js(value)});")

    /** "Poslat do okna": the page gets it in automation.on(event, …). */
    fun send(event: String, data: String) =
        eval("window.automation && automation._emit(${js(event)}, ${js(data)});")

    companion object {
        /** Name of the object the page talks to the app through. */
        const val BRIDGE = "AutomationBridge"

        /** A JavaScript string literal. */
        fun js(text: String): String = JsonPrimitive(text).toString()
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

        /**
         * Put into the page before its own scripts: `automation.call(action, data)` → Promise,
         * `automation.close(value)`, `automation.on(event, fn)`. Values that are JSON come as objects.
         */
        val SHIM = """
            <script>
            window.automation = (function () {
              var seq = 0, pending = {}, handlers = {};
              function enc(v) { return typeof v === 'string' ? v : v === undefined ? '' : JSON.stringify(v); }
              function dec(t) { try { return JSON.parse(t); } catch (e) { return t; } }
              return {
                call: function (action, data) {
                  return new Promise(function (resolve, reject) {
                    var id = String(++seq);
                    pending[id] = { resolve: resolve, reject: reject };
                    $BRIDGE.call(id, String(action || ''), enc(data));
                  });
                },
                close: function (value) { $BRIDGE.close(enc(value)); },
                on: function (event, fn) { (handlers[event] = handlers[event] || []).push(fn); },
                _reply: function (id, ok, value) {
                  var p = pending[id];
                  if (!p) return;
                  delete pending[id];
                  if (ok) p.resolve(dec(value)); else p.reject(new Error(value));
                },
                _emit: function (event, value) {
                  (handlers[event] || []).concat(handlers['*'] || []).forEach(function (fn) {
                    try { fn(dec(value), event); } catch (e) { console.error(e); }
                  });
                }
              };
            })();
            </script>
        """.trimIndent()

        /** [html] with the shim put in as early as possible (into <head>, else in front). */
        fun withShim(html: String): String {
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (head != null) return html.substring(0, head.range.last + 1) + "\n" + SHIM + html.substring(head.range.last + 1)
            val doctype = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE).find(html)
            if (doctype != null) return html.substring(0, doctype.range.last + 1) + "\n" + SHIM + html.substring(doctype.range.last + 1)
            return SHIM + "\n" + html
        }

        /** What the steps inside get as the block's value: `{"action": "…", "data": …}` (data as JSON when it is JSON). */
        fun request(action: String, data: String): String = JsonObject(
            mapOf("action" to JsonPrimitive(action), "data" to (JsonTools.parseOrNull(data) ?: JsonPrimitive(data)))
        ).toString()
    }
}

object HtmlBlocks {
    const val WINDOW = "html_window"
    const val END_WINDOW = "end_html_window"
    const val SEND = "html_send"
    const val SHEET = "sheet"

    /** How a window with a page is shown: over the whole screen, or sliding up from the bottom. */
    fun displayParam() = ParamDef.Choice(
        "display", "Zobrazení", listOf(
            ChoiceOption("full", "Přes celou obrazovku", "celé"),
            ChoiceOption(SHEET, "Zespodu do půlky (jde roztáhnout)", "zespodu"),
        )
    ).apply { hiddenInSummaryWhen = { true } }

    /**
     * Shows the window of [block] and runs [handle] for every call of the page, one after another;
     * an exception in it rejects the page's promise. Returns the value the page closed with.
     */
    suspend fun open(ctx: RunContext, block: Block, handle: suspend (request: String) -> String): String {
        val ui = ctx.ui ?: throw BlockException(tr("okno s HTML jde ukázat jen v okně"))
        val html = ctx.text(block, "html")
        if (html.isBlank()) throw BlockException(tr("chybí HTML stránky"))
        val window = HtmlWindow(
            ctx.text(block, "title"), html, ctx.bool(block, "internet"), ctx.bool(block, "secure"),
            sheet = ctx.choice(block, "display") == SHEET, bar = ctx.bool(block, "bar"),
        )
        val outer = ctx.htmlWindow
        ctx.htmlWindow = window
        try {
            return coroutineScope {
                val worker = launch {
                    for (call in window.calls) {
                        val answer = try {
                            true to handle(HtmlWindow.request(call.action, call.data))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: RunCancelledException) {
                            false to tr("zrušeno")
                        } catch (e: Exception) {
                            false to ctx.mask(e.message ?: e.javaClass.simpleName).orEmpty()
                        }
                        window.reply(call.id, answer.first, answer.second)
                    }
                }
                try {
                    ui.showHtml(window) ?: throw RunCancelledException()
                } finally {
                    worker.cancel()
                    window.calls.close()
                }
            }
        } finally {
            ctx.htmlWindow = outer
        }
    }

    val all = listOf(
        BlockDef(
            type = WINDOW,
            needsWindow = true,
            summary = "Okno s HTML {title}",
            title = "Okno s HTML",
            description = "Ukáže tvou vlastní stránku (HTML, CSS, JavaScript). Stránka volá automation.call(\"akce\", data) " +
                    "a kroky uvnitř bloku na to odpoví: hodnota bloku je {action, data}, odpovědí je výsledek posledního kroku " +
                    "nebo Vrátit výsledek. Chyba v krocích stránce volání odmítne. automation.close(hodnota) okno zavře.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Web,
            kind = BlockKind.Scope,
            params = listOf(
                ParamDef.Text("html", "HTML stránky", code = true, placeholder = "<button onclick=\"automation.call('ahoj')\">Ahoj</button>")
                    .apply { hiddenInSummaryWhen = { true } },
                ParamDef.Text("title", "Nadpis", placeholder = "nepovinný"),
                ParamDef.Toggle("internet", "Povolit internet", summaryWhenOn = "s internetem"),
                ParamDef.Toggle("secure", "Zakázat snímky obrazovky", summaryWhenOn = "(chráněné)"),
                displayParam(),
                ParamDef.Toggle("bar", "Vždy ukázat lištu se Zavřít (i bez nadpisu)"),
            ),
            outputName = "Volání stránky",
        ),

        BlockDef(
            type = END_WINDOW,
            summary = "Konec okna s HTML",
            title = "Konec okna s HTML",
            description = "",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Web,
            kind = BlockKind.End,
            showInLibrary = false,
        ),

        BlockDef(
            type = SEND,
            summary = "Poslat do okna {event}",
            title = "Poslat do okna",
            description = "Pošle otevřenému oknu s HTML zprávu, aniž by se stránka ptala – stránka ji dostane " +
                    "v automation.on(\"událost\", data => …). Hodí se na průběh, upozornění nebo zamčení po nečinnosti.",
            category = BlockCategory.Dialogs,
            icon = Icons.Rounded.Send,
            params = listOf(
                ParamDef.Text("event", "Událost", default = "zprava", plain = true),
                ParamDef.Text("data", "Data", defaultRef = VarRef.Input),
            ),
        ) { block ->
            val window = htmlWindow ?: throw BlockException(tr("žádné okno s HTML není otevřené (blok patří dovnitř Okna s HTML)"))
            window.send(text(block, "event").trim(), text(block, "data"))
            BlockResult.None
        },
    )
}
