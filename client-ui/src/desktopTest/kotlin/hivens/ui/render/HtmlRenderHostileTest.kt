package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Descriptions are written by third parties, so the renderer has to survive
 * markup nobody would write on purpose. Each of these froze, blanked or hung the
 * thread that draws before the guards below existed, and a hang is worse than a
 * crash: the launcher does not report anything, it simply stops.
 *
 * The assertion is wall-clock. It is a blunt instrument and deliberately loose --
 * what it is separating is milliseconds from minutes.
 */
class HtmlRenderHostileTest {

    private fun layOut(markdown: String): Long {
        val started = System.nanoTime()
        val scene = ImageComposeScene(600, 400, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    MarkdownHtml(markdown = markdown, onLink = {})
                }
            }
        }
        try {
            var t = 0L
            repeat(12) { scene.render(t); t += 16_000_000L; Thread.sleep(15) }
        } finally {
            scene.close()
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `a deeply quoted description lays out in a moment, not a minute`() {
        // Thirty-six characters. With the quote bar laid out as a sibling stretched
        // to the row height, each level forced an intrinsic measurement of the
        // level inside it: sixteen levels took over a minute for one line of text.
        val ms = layOut("> ".repeat(16) + "deep")

        assertTrue(ms < BUDGET_MS, "sixteen nested quotes took ${ms}ms")
    }

    @Test
    fun `nesting past anything a person would write is read as text, not followed`() {
        val ms = layOut("> ".repeat(400) + "deeper")

        assertTrue(ms < BUDGET_MS, "four hundred nested quotes took ${ms}ms")
    }

    @Test
    fun `a list nested past the parser's patience still shows something`() {
        // The markdown parser overflows its own stack on this. The body must
        // degrade to text rather than stay blank with nothing said.
        val ms = layOut((0..1200).joinToString("\n") { " ".repeat(it * 2) + "- x" })

        assertTrue(ms < BUDGET_MS, "a deeply indented list took ${ms}ms")
    }

    @Test
    fun `a wall of nested tables does not multiply`() {
        val open = "<table><tr><td>".repeat(30)
        val close = "</td></tr></table>".repeat(30)
        val ms = layOut(open + "cell" + close)

        assertTrue(ms < BUDGET_MS, "thirty nested tables took ${ms}ms")
    }

    @Test
    fun `a run of empty paragraphs does not open a hole down the page`() {
        val ms = layOut("A" + "\n\n<p></p>".repeat(200) + "\n\nB")

        assertTrue(ms < BUDGET_MS, "two hundred empty paragraphs took ${ms}ms")
    }

    private companion object {
        /**
         * Generous on purpose: an off-screen scene on a loaded machine is not a
         * benchmark. The failures this guards against are measured in tens of
         * seconds and upwards.
         */
        const val BUDGET_MS = 20_000L
    }
}
