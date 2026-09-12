package hivens.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * One face, one family object, however many strings ask for it.
 *
 * A font family is the cache key in Compose's font resolver, so a factory that
 * builds a fresh one per call makes a fresh cache entry per call. [familyForText]
 * is asked inside `Text` parameters, and the player cards recompose with the
 * playback position five times a second, so a per-call family turned one 7.8 MB
 * face into an unbounded set of registered typefaces: heap on Linux, and a native
 * font object per entry on macOS, which is a platform that does not forgive that
 * for long.
 *
 * Identity rather than equality on purpose. Equality would pass for two distinct
 * objects that happen to compare equal, and the resolver's cache is only spared
 * the second entry if the key is the same instance or a correctly equal one; the
 * cheap guarantee to hold is the first.
 */
class FontFamilyIdentityTest {

    /**
     * Reads a composable value from inside the theme.
     *
     * The result is boxed in a list rather than held in a nullable variable,
     * because null is a legitimate answer here: the first version of this helper
     * asserted the value was non-null on its way out and failed the one case that
     * was working correctly.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun <T> underTheme(read: @androidx.compose.runtime.Composable () -> T): T {
        val captured = mutableListOf<T>()
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                if (captured.isEmpty()) captured += read()
                Box(androidx.compose.ui.Modifier)
            }
        }
        scene.render()
        scene.close()
        check(captured.isNotEmpty()) { "the composition never ran" }
        return captured.single()
    }

    @Test
    fun `two strings needing the cjk face get the same family instance`() {
        val (first, second) = underTheme {
            familyForText("追憶のサクラメント") to familyForText("ひぐらしのなく頃に")
        }
        assertNotNull(first, "a string the ui face cannot cover must be given the bundled face")
        assertSame(first, second, "a family per call is a font-resolver cache entry per call")
    }

    @Test
    fun `the same string asked twice does not build a second family`() {
        val (first, second) = underTheme {
            familyForText("日本語") to familyForText("日本語")
        }
        assertSame(first, second)
    }

    @Test
    fun `a string the ui face covers is left to the style`() {
        val latin = underTheme { familyForText("Higurashi no Naku Koro ni") }
        assertNull(latin, "null means the type scale's own face, which already covers it")
        val cyrillic = underTheme { familyForText("Обновление сервера") }
        assertNull(cyrillic)
    }

    @Test
    fun `the mono family is one instance too`() {
        val (first, second) = underTheme { LocalMonoFamily.current to LocalMonoFamily.current }
        assertSame(first, second)
        assertNotNull(first as FontFamily?)
    }
}
