package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a theme promises, held for every theme that ships, in every mode it has.
 *
 * These assert the intent rather than the arithmetic: the author's ladder reads as
 * separate planes, the colours nx-ui makes beside a theme stay apart, and what is drawn
 * is what the author wrote. The pipeline this replaced had tests that held the mechanism
 * to its own numbers, and passed while every preset drew the same ground.
 *
 * nx-ui draws a theme's inks as authored and corrects nothing, so the reading held here
 * is a floor for the authored inks on the authored steps, measured in APCA. It judges no
 * taste: a theme that passes is not thereby good. A theme's colours drawn as text are not
 * checked, since which colour a component sets its words in is the component's choice,
 * and whether that reads is the theme author's to answer for.
 */
class ThemeIntentTest {

    /** Every theme that ships, and themes made from wallpapers of every kind of colour. */
    private fun schemes(): List<Pair<String, Scheme>> = (Themes.all + wallpaperThemes()).flatMap { theme ->
        listOfNotNull("${theme.id}/dark" to theme.dark, theme.light?.let { "${theme.id}/light" to it })
    }

    @Test
    fun `an authored ladder is monotonic and its neighbours read as separate planes`() {
        for ((name, scheme) in schemes()) {
            val l = scheme.steps.map { it.lstar() }
            val rising = l.last() > l.first()
            val _ = l.zipWithNext { a, b ->
                assertTrue(if (rising) b > a else b < a, "$name: the ladder turns back on itself at $a -> $b")
                val gap = abs(b - a)
                val floor = if (scheme.isDark) DARK_STEP else LIGHT_STEP
                assertTrue(gap >= floor, "$name: neighbouring steps are ${"%.2f".format(gap)} L* apart, under $floor")
            }
        }
    }

    @Test
    fun `the authored inks clear the reading floor on every authored step`() {
        for ((name, scheme) in schemes()) {
            for ((i, ground) in scheme.steps.withIndex()) {
                val main = abs(apcaLc(scheme.inks[0], ground))
                val quiet = abs(apcaLc(scheme.inks[1], ground))
                assertTrue(main >= MAIN_LC, "$name step $i: the main ink reaches Lc ${"%.1f".format(main)}, under $MAIN_LC")
                assertTrue(quiet >= QUIET_LC, "$name step $i: the quiet ink reaches Lc ${"%.1f".format(quiet)}, under $QUIET_LC")
            }
        }
    }

    @Test
    fun `the inks are drawn as authored`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            assertEquals(scheme.inks[0], c.inkMain, "$name: the main ink moved")
            assertEquals(scheme.inks[1], c.inkQuiet, "$name: the quiet ink moved")
        }
    }

    @Test
    fun `content on a fill is drawn in one of the theme's own colours`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            val fills = listOf(c.lead) + Status.entries.map { c.status(it) } + c.distinct(8)
            for (fill in fills) {
                assertTrue(c.on(fill) in listOf(scheme.inks[0], scheme.steps[0]), "$name: the ink on $fill is not the theme's")
            }
        }
    }

    @Test
    fun `the ladder continues in its own direction past the end`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            val authored = scheme.steps.map { it.lstar() }
            val rising = authored.last() > authored.first()
            val beyond = c.step(scheme.steps.size).lstar()
            val before = c.step(-1).lstar()
            assertTrue(if (rising) beyond >= authored.last() else beyond <= authored.last(), "$name: the step past the end turns back")
            assertTrue(if (rising) before <= authored.first() else before >= authored.first(), "$name: the step before the page turns back")
        }
    }

    @Test
    fun `a category's members read as different colours in every theme`() {
        for ((name, scheme) in schemes()) {
            val set = SchemeColours(scheme).distinct(6)
            for (i in set.indices) for (j in i + 1 until set.size) {
                val d = deltaE(set[i], set[j])
                assertTrue(d >= DISTINCT, "$name: members $i and $j are ${"%.1f".format(d)} dE apart")
            }
        }
    }

    @Test
    fun `a status is the theme's own colour when the theme has one near the convention`() {
        // Celestia names a red, an amber, a green and a blue among its colours. Asking
        // for a status must hand back those, not something made beside them.
        val dark = SchemeColours(Themes.Celestia.dark)
        assertEquals(Color(0xFFCF6679), dark.status(Status.Error))
        assertEquals(Color(0xFFE0B341), dark.status(Status.Warning))
        assertEquals(Color(0xFF4CAF50), dark.status(Status.Success))
        assertEquals(Color(0xFF6A84FF), dark.status(Status.Info))
    }

    @Test
    fun `the page is the author's ground and nothing moves it`() {
        // The defect this replaces: a preset declared #0A0E27 and drew #160F10.
        for (theme in Themes.all) {
            assertEquals(theme.dark.steps[0], SchemeColours(theme.dark).step(0), "${theme.id}: the ground moved")
        }
    }

    @Test
    fun `a theme without a light scheme stays in its own`() {
        assertEquals(Themes.Abyssal.dark, Themes.Abyssal.scheme(dark = false))
        assertEquals(Themes.Celestia.light, Themes.Celestia.scheme(dark = false))
    }

    @Test
    fun `a wallpaper theme draws with the wallpaper's colours in their order`() {
        val theme = themeFromWallpaper(listOf(0xFFE0457B.toInt(), 0xFF2E86C1.toInt()), "w")!!
        for (scheme in listOf(theme.dark, theme.light!!)) {
            val hues = scheme.colors.map { it.hct().hue }
            assertTrue(hueDistance(hues[0], Color(0xFFE0457B).hct().hue) < 3.0, "the first colour lost its hue")
            assertTrue(hueDistance(hues[1], Color(0xFF2E86C1).hct().hue) < 3.0, "the second colour lost its hue")
            // The ladder belongs to the first colour, and stays Celestia's in tone. Read
            // in the middle, since white at the end of a light ladder has no hue to read.
            val middle = scheme.steps[2].hct()
            assertTrue(hueDistance(middle.hue, Color(0xFFE0457B).hct().hue) < 10.0, "the ladder is not in the wallpaper's hue")
        }
        val celestia = Themes.Celestia.dark.steps.map { it.lstar() }
        theme.dark.steps.map { it.lstar() }.zip(celestia).forEach { (a, b) -> assertEquals(b, a, 0.6) }
    }

    @Test
    fun `a wallpaper without colour gives a neutral ladder and the default colours`() {
        val theme = themeFromWallpaper(listOf(0xFF808080.toInt()), "w")!!
        assertEquals(Themes.Celestia.dark.colors, theme.dark.colors)
        assertTrue(theme.dark.steps.all { it.red == it.green && it.green == it.blue }, "a grey wallpaper tinted the ladder")
        assertEquals(null, themeFromWallpaper(emptyList(), "w"))
    }

    private fun wallpaperThemes(): List<Theme> = listOf(
        listOf(0xFFE0457B, 0xFF2E86C1, 0xFFF4D03F),
        listOf(0xFF3B0A0A),
        listOf(0xFF0B3D2E, 0xFF9ACD32),
        listOf(0xFFFFF59D),
        listOf(0xFF7A7A7A),
        listOf(0xFF1A237E, 0xFF00E5FF, 0xFFFF6D00, 0xFF6A1B9A, 0xFF2E7D32),
    ).mapIndexedNotNull { i, argb -> themeFromWallpaper(argb.map { it.toInt() }, "wallpaper $i")?.copy(id = "wallpaper-$i") }

    private companion object {
        /** APCA's floor for body text, which the main ink carries. */
        const val MAIN_LC = 75.0

        /**
         * The quiet ink carries secondary text: set just under the quietest pairing among
         * the shipped themes judged right, so a new theme is held to what they already do.
         */
        const val QUIET_LC = 40.0

        /**
         * Two flat fields need roughly 3 L* to be told apart by fill alone on a light
         * ground. This sits just under that. A dark ladder lives where L* is
         * compressed, and its tightest authored pair is 1.9, so its floor is lower.
         */
        const val LIGHT_STEP = 2.5
        const val DARK_STEP = 1.5

        /** Below this two swatches side by side read as one colour. */
        const val DISTINCT = 15.0
    }
}
