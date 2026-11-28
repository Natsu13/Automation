package cz.natsu.automation

import cz.natsu.automation.ui.editor.CodeKind
import cz.natsu.automation.ui.editor.CodeLanguage
import cz.natsu.automation.ui.editor.CodeTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeToolsTest {
    @Test
    fun formatsMinifiedHtml() {
        val html = "<html><head><style>.a{color:red;margin:0}</style></head><body><div class=\"card\"><b>Ahoj</b> světe" +
                "<div>x</div></div><script>var a=1;if(a){go(a);}</script></body></html>"
        assertEquals(
            """
            <html>
              <head>
                <style>
                  .a {
                    color:red;
                    margin:0
                  }
                </style>
              </head>
              <body>
                <div class="card">
                  <b>Ahoj</b> světe
                  <div>
                    x
                  </div>
                </div>
                <script>
                  var a=1;
                  if(a){
                    go(a);
                  }
                </script>
              </body>
            </html>
            """.trimIndent(),
            CodeTools.format(html, CodeLanguage.Html),
        )
    }

    @Test
    fun formattingTwiceChangesNothing() {
        val html = "<div><p>a <i>b</i></p><ul><li>1</li><li>2</li></ul></div>"
        val once = CodeTools.format(html, CodeLanguage.Html)
        assertEquals(once, CodeTools.format(once, CodeLanguage.Html))
    }

    @Test
    fun keepsStringsInJavaScript() {
        val js = "var s = \"a;b{c}\"; for (var i = 0; i < 3; i++) { f(i); }"
        assertEquals(
            """
            var s = "a;b{c}";
             for (var i = 0; i < 3; i++) {
              f(i);
            }
            """.trimIndent().lines().joinToString("\n") { it.trim().let { l -> if (l == "f(i);") "  $l" else l } },
            CodeTools.format(js, CodeLanguage.Js),
        )
    }

    @Test
    fun indentsTheScriptLanguage() {
        val script = "pokud a > 1\nx = 2\njinak\npro i v seznam\nvrat i\nkonec\nkonec\nvrat x"
        assertEquals(
            "pokud a > 1\n    x = 2\njinak\n    pro i v seznam\n        vrat i\n    konec\nkonec\nvrat x",
            CodeTools.format(script, CodeLanguage.Script),
        )
    }

    @Test
    fun coloursTagsAttributesAndScript() {
        val html = "<div class=\"a\"><script>var x = 'y';</script></div>"
        val kinds = CodeTools.tokens(html, CodeLanguage.Html).map { html.substring(it.first) to it.second }
        assertTrue(("<div" to CodeKind.Tag) in kinds)
        assertTrue(("class" to CodeKind.Attribute) in kinds)
        assertTrue(("\"a\"" to CodeKind.Str) in kinds)
        assertTrue(("var" to CodeKind.Keyword) in kinds)
        assertTrue(("'y'" to CodeKind.Str) in kinds)
    }

    @Test
    fun marksTheTappablePartsOfAWidget() {
        val html = "<div class=\"a\" data-tap=\"dalsi\" data-run=\"Auto\" data-input=\"x\" id=\"b\">"
        val kinds = CodeTools.tokens(html, CodeLanguage.Html).map { html.substring(it.first) to it.second }
        for (part in listOf("data-tap", "\"dalsi\"", "data-run", "\"Auto\"", "data-input", "\"x\"")) {
            assertTrue(part, (part to CodeKind.Action) in kinds)
        }
        assertTrue(("class" to CodeKind.Attribute) in kinds)
        assertTrue(("\"b\"" to CodeKind.Str) in kinds)
    }

    @Test
    fun aClosingStyleTagEndsTheCss() {
        val html = "<style>.a{color:red}</style></head><body><div class=\"x\">y</div>"
        val kinds = CodeTools.tokens(html, CodeLanguage.Html).map { html.substring(it.first) to it.second }
        assertTrue(("class" to CodeKind.Attribute) in kinds)
        assertTrue(kinds.none { it.first.length > 20 })
    }

    @Test
    fun enterKeepsTheIndent() {
        assertEquals("        ", CodeTools.indentAfter("pokud a\n    pro i v x", 19, CodeLanguage.Script))
        assertEquals("  ", CodeTools.indentAfter("<div>", 5, CodeLanguage.Html))
        assertEquals("", CodeTools.indentAfter("<b>x</b>", 8, CodeLanguage.Html))
    }
}
