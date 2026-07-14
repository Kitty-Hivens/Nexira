package hivens.ui.render

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Covers the non-composable half of the in-launcher HTML renderer: the md -> HTML
 * step (org.jetbrains:markdown) and that jsoup parses the result + dirty embedded
 * HTML without losing content. The Compose paint is verified by compilation +
 * manual run (a unit harness for it would need compose-ui-test, overkill here).
 */
class HtmlRenderTest {

    @Test
    fun `markdown with embedded html converts to renderable html`() {
        val body = """
            # Cobblemon

            **Bold** and *italic* and [a link](https://example.com).

            <p align="center"><img src="https://img.shields.io/x.svg" alt="badge"></p>

            - one
            - two
        """.trimIndent()

        val html = markdownToHtml(body)
        assertTrue(html.isNotBlank(), "md -> html produced output")

        val doc = Jsoup.parse(html).body()
        assertTrue(doc.selectFirst("h1") != null, "heading rendered")
        assertTrue(doc.selectFirst("a[href]") != null, "link preserved")
        assertTrue(doc.selectFirst("img[src]") != null, "embedded <img> preserved")
        assertTrue(doc.select("li").size >= 2, "list items present")
    }

    @Test
    fun `dirty html survives parsing and keeps its content reachable`() {
        // Unsupported CSS (flex) + an unknown tag must not break parsing; the renderer
        // degrades to content, so the content must stay present in the parsed tree.
        val html = markdownToHtml(
            "<div style=\"display:flex\"><span style=\"color:#f00\">red</span> <unknowntag>kept</unknowntag></div>",
        )
        val text = Jsoup.parse(html).body().text()
        assertTrue(text.contains("red"), "styled content kept")
        assertTrue(text.contains("kept"), "unknown-tag content kept")
    }
}
