package hivens.ui.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlWidthsTest {

    private fun cells(row: String): List<Element> =
        Jsoup.parse("<table><tr>$row</tr></table>").select("td, th")

    private fun shares(row: String): List<Float> = cellWeights(cells(row)).let { w -> w.map { it / w.sum() } }

    private fun assertShares(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size, "cell count")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.001f, "shares $actual") }
    }

    @Test
    fun `cells that declare nothing share the row evenly`() {
        assertShares(listOf(0.5f, 0.5f), shares("<td>a</td><td>b</td>"))
    }

    @Test
    fun `percentages become the shares they name`() {
        assertShares(listOf(0.6f, 0.4f), shares("""<td width="60%">a</td><td width="40%">b</td>"""))
    }

    /** Aquamirae's credit row, which declares fifteen and forty and nothing else. */
    @Test
    fun `percentages that fall short of the row are scaled to fill it`() {
        assertShares(listOf(15f / 55f, 40f / 55f), shares("""<td width="15%">a</td><td width="40%">b</td>"""))
    }

    @Test
    fun `an undeclared cell takes what the declared ones left`() {
        assertShares(listOf(0.7f, 0.3f), shares("""<td width="70%">a</td><td>b</td>"""))
    }

    @Test
    fun `the style wins over the attribute`() {
        assertShares(listOf(0.25f, 0.75f), shares("""<td width="50%" style="width: 25%">a</td><td style="width:75%">b</td>"""))
    }

    @Test
    fun `pixel widths are proportions when no cell uses a percentage`() {
        assertShares(listOf(0.25f, 0.75f), shares("""<td width="100">a</td><td width="300px">b</td>"""))
    }

    @Test
    fun `a colspan counts as that many undeclared columns`() {
        assertShares(listOf(2f / 3f, 1f / 3f), shares("""<td colspan="2">a</td><td>b</td>"""))
    }

    @Test
    fun `nonsense widths are ignored rather than trusted`() {
        assertShares(listOf(0.5f, 0.5f), shares("""<td width="-20%">a</td><td width="wide">b</td>"""))
    }

    @Test
    fun `declaredWidth reads percentages, pixels and bare numbers`() {
        fun w(attrs: String) = declaredWidth(Jsoup.parse("<img $attrs>").selectFirst("img")!!)
        assertEquals(CssWidth.Percent(80f), w("width=80%"))
        assertEquals(CssWidth.Percent(45f), w("""style="display: inline;" width="45%""""))
        assertEquals(CssWidth.Px(250f), w("""width="250""""))
        assertEquals(CssWidth.Px(120f), w("""style="width: 120px""""))
        assertNull(w("""width="""""))
        assertNull(w("""width="auto""""))
    }
}
