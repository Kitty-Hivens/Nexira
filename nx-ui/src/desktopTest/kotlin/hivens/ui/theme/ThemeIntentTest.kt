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
 * separate planes, text is readable on every one of them, and every colour nx-ui
 * hands out reaches its target on the plane it is fitted to. The pipeline this
 * replaced had tests that held the mechanism to its own numbers, and passed while
 * every preset drew the same ground.
 */
class ThemeIntentTest {

    private fun schemes(): List<Pair<String, Scheme>> = Themes.all.flatMap { theme ->
        listOfNotNull("${theme.id}/dark" to theme.dark, theme.light?.let { "${theme.id}/light" to it })
    }

    @Test
    fun `an authored ladder is monotonic and its neighbours read as separate planes`() {
        for ((name, scheme) in schemes()) {
            val l = scheme.steps.map { it.lstar() }
            val rising = l.last() > l.first()
            l.zipWithNext { a, b ->
                assertTrue(if (rising) b > a else b < a, "$name: the ladder turns back on itself at $a -> $b")
                val gap = abs(b - a)
                val floor = if (scheme.isDark) DARK_STEP else LIGHT_STEP
                assertTrue(gap >= floor, "$name: neighbouring steps are ${"%.2f".format(gap)} L* apart, under $floor")
            }
        }
    }

    @Test
    fun `the authored inks are readable on every authored step`() {
        for ((name, scheme) in schemes()) {
            for ((i, step) in scheme.steps.withIndex()) {
                for ((role, ink) in listOf("main" to scheme.inks[0], "quiet" to scheme.inks[1])) {
                    val r = contrast(ink, step)
                    assertTrue(r >= TEXT, "$name step $i: $role ink at ${"%.2f".format(r)} is under $TEXT")
                }
            }
        }
    }

    @Test
    fun `fitted inks stay readable past both ends of the ladder`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            for (i in -2..8) {
                val ground = c.step(i)
                assertTrue(contrast(c.inkMain(ground), ground) >= TEXT, "$name step $i: main ink fails")
                assertTrue(contrast(c.inkQuiet(ground), ground) >= TEXT, "$name step $i: quiet ink fails")
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
    fun `every colour handed out reaches its target on every step`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            val asks = listOf("lead" to c.lead) +
                Status.entries.map { it.name to c.status(it) } +
                c.distinct(6).mapIndexed { i, col -> "category $i" to col }
            for (i in 0..scheme.steps.size) {
                val ground = c.step(i)
                for ((what, colour) in asks) {
                    val mark = fit(colour, ground, SchemeColours.MARK)
                    val text = fit(colour, ground, SchemeColours.TEXT)
                    assertTrue(contrast(mark, ground) >= SchemeColours.MARK - EPS, "$name step $i: $what as a mark fails")
                    assertTrue(contrast(text, ground) >= SchemeColours.TEXT - EPS, "$name step $i: $what as text fails")
                }
            }
        }
    }

    @Test
    fun `content on any fill the theme hands out is readable`() {
        for ((name, scheme) in schemes()) {
            val c = SchemeColours(scheme)
            val fills = listOf(c.lead) + Status.entries.map { c.status(it) } + c.distinct(8)
            for (fill in fills) {
                val r = contrast(c.on(fill), fill)
                assertTrue(r >= TEXT, "$name: ink on ${fill} reaches only ${"%.2f".format(r)}")
            }
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
        assertEquals(Themes.Matrix.dark, Themes.Matrix.scheme(dark = false))
        assertEquals(Themes.Celestia.light, Themes.Celestia.scheme(dark = false))
    }

    private companion object {
        const val TEXT = 4.5
        const val EPS = 0.05

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
