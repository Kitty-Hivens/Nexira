package hivens.ui.render

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HtmlWidthsTest {

    private fun rows(html: String): List<List<Element>> =
        Jsoup.parse("<table>$html</table>").select("tr").map { it.select("td, th") }

    private fun columns(html: String) = tableColumns(rows(html))

    /** The share each column gets when nothing is fixed. */
    private fun shares(html: String): List<Float> = columns(html).let { c -> c.map { it.share / c.sumOf { x -> x.share.toDouble() }.toFloat() } }

    private fun assertShares(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size, "column count in $actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 0.001f, "shares $actual") }
    }

    @Test
    fun `columns that declare nothing share the row evenly`() {
        assertShares(listOf(0.5f, 0.5f), shares("<tr><td>a</td><td>b</td></tr>"))
    }

    @Test
    fun `percentages become the shares they name`() {
        assertShares(listOf(0.6f, 0.4f), shares("""<tr><td width="60%">a</td><td width="40%">b</td></tr>"""))
    }

    /** Aquamirae's credit row, which declares fifteen and forty and nothing else. */
    @Test
    fun `percentages that fall short of the row are scaled to fill it`() {
        assertShares(listOf(15f / 55f, 40f / 55f), shares("""<tr><td width="15%">a</td><td width="40%">b</td></tr>"""))
    }

    @Test
    fun `an undeclared column takes what the declared ones left`() {
        assertShares(listOf(0.7f, 0.3f), shares("""<tr><td width="70%">a</td><td>b</td></tr>"""))
    }

    /** `width="100%"` beside an undeclared cell is "take the rest", not "leave it nothing". */
    @Test
    fun `an undeclared column keeps a floor when the others claim everything`() {
        assertShares(listOf(100f / 115f, 15f / 115f), shares("""<tr><td width="100%">a</td><td>b</td></tr>"""))
        assertShares(listOf(99f / 114f, 15f / 114f), shares("""<tr><td width="99%">a</td><td>b</td></tr>"""))
    }

    /** A header that declares the widths is what every row below lines up under. */
    @Test
    fun `widths declared on the header row apply to every row`() {
        val rows = rows("""<tr><th width="25%">Key</th><th width="75%">Action</th></tr><tr><td>R</td><td>Reload</td></tr>""")
        val cols = tableColumns(rows)
        assertEquals(rowSlots(rows[0], cols), rowSlots(rows[1], cols))
        assertEquals(listOf(25f, 75f), cols.map { it.share })
    }

    @Test
    fun `the style wins over the attribute`() {
        assertShares(listOf(0.25f, 0.75f), shares("""<tr><td width="50%" style="width: 25%">a</td><td style="width:75%">b</td></tr>"""))
    }

    @Test
    fun `pixel widths are proportions when every column is in pixels`() {
        assertShares(listOf(0.25f, 0.75f), shares("""<tr><td width="100">a</td><td width="300px">b</td></tr>"""))
    }

    /** An icon column and a text column: the icon keeps its pixels, the text takes the rest. */
    @Test
    fun `a pixel column beside an undeclared one is fixed`() {
        val cols = columns("""<tr><td width="32">icon</td><td>long text</td></tr>""")
        assertEquals(ColumnSpan(share = 0f, fixedPx = 32f), cols[0])
        assertEquals(ColumnSpan(share = 1f, fixedPx = 0f), cols[1])
    }

    @Test
    fun `a colspan covers the columns under it`() {
        val rows = rows("""<tr><td width="20%">a</td><td width="30%">b</td><td>c</td></tr><tr><td colspan="2">ab</td><td>c</td></tr>""")
        val slots = rowSlots(rows[1], tableColumns(rows))
        assertEquals(2, slots.size)
        assertEquals(50f, slots[0].share, 0.001f)
    }

    @Test
    fun `a short row is padded with an empty slot so it still lines up`() {
        val rows = rows("<tr><td>a</td><td>b</td><td>c</td></tr><tr><td>a</td></tr>")
        val slots = rowSlots(rows[1], tableColumns(rows))
        assertEquals(listOf(1f, 2f), slots.map { it.share })
    }

    @Test
    fun `nonsense widths are ignored rather than trusted`() {
        assertShares(listOf(0.5f, 0.5f), shares("""<tr><td width="-20%">a</td><td width="wide">b</td></tr>"""))
        // Parses, as infinity. A row with an infinite share hands every cell a pixel.
        assertShares(listOf(0.5f, 0.5f), shares("""<tr><td width="1e40">a</td><td width="1e40">b</td></tr>"""))
    }

    @Test
    fun `declaredWidth reads percentages, pixels and bare numbers, bounded`() {
        fun w(attrs: String) = declaredWidth(Jsoup.parse("<img $attrs>").selectFirst("img")!!)
        assertEquals(CssWidth.Percent(80f), w("width=80%"))
        assertEquals(CssWidth.Percent(45f), w("""style="display: inline;" width="45%""""))
        assertEquals(CssWidth.Percent(100f), w("""width="250%""""))
        assertEquals(CssWidth.Px(250f), w("""width="250""""))
        assertEquals(CssWidth.Px(120f), w("""style="width: 120px""""))
        assertNull(w("""width="""""))
        assertNull(w("""width="auto""""))
        assertNull(w("""width="1e40%""""))
    }
}
