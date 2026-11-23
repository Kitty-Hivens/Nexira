package hivens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import hivens.look.type.api.TextRole
import hivens.look.type.api.Type
import hivens.look.type.api.TypeAnswers
import hivens.look.type.api.TypeFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The bridge fills Material's type scale from the type axis: each group in its role's face, its size,
 * line height and tracking untouched, so no look moves a layout by its metrics. An interface in a script
 * the faces lack is drawn in the face the look has for it, in a bridge inside the bridge as well.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NxTypeTest {

    private val display = FontFamily.Cursive
    private val heading = FontFamily.Serif
    private val body = FontFamily.SansSerif
    private val script = FontFamily.Monospace

    /** A look with a face of its own for headings, a weight and a style, and one for any CJK string. */
    private val look = TypeAnswers.build {
        face = { role ->
            when (role) {
                TextRole.Display -> TypeFace.of(display)
                TextRole.Heading -> TypeFace.of(heading, FontWeight.Black, FontStyle.Italic)
                else -> TypeFace.of(body)
            }
        }
        forText = { text -> if (text.any { it.code > 0x2E80 }) TypeFace.of(script) else null }
    }

    private fun styles(t: Typography): List<Pair<TextRole, TextStyle>> = listOf(
        TextRole.Display to t.displayLarge, TextRole.Display to t.displayMedium, TextRole.Display to t.displaySmall,
        TextRole.Heading to t.headlineLarge, TextRole.Heading to t.headlineMedium, TextRole.Heading to t.headlineSmall,
        TextRole.Title to t.titleLarge, TextRole.Title to t.titleMedium, TextRole.Title to t.titleSmall,
        TextRole.Body to t.bodyLarge, TextRole.Body to t.bodyMedium, TextRole.Body to t.bodySmall,
        TextRole.Label to t.labelLarge, TextRole.Label to t.labelMedium, TextRole.Label to t.labelSmall,
    )

    @Test
    fun `each group is drawn in its role's face, and no size, line height or tracking moves`() {
        var drawn: Typography? = null
        compose {
            CompositionLocalProvider(Type.local provides look) { NxTheme { drawn = MaterialTheme.typography } }
        }
        val material = styles(Typography())
        styles(assertNotNull(drawn)).forEachIndexed { i, (role, style) ->
            val was = material[i].second
            assertEquals(was.fontSize, style.fontSize, "$role")
            assertEquals(was.lineHeight, style.lineHeight, "$role")
            assertEquals(was.letterSpacing, style.letterSpacing, "$role")
            val family = when (role) {
                TextRole.Display -> display
                TextRole.Heading -> heading
                else -> body
            }
            assertSame(family, style.fontFamily, "$role")
            assertEquals(if (role == TextRole.Heading) FontWeight.Black else was.fontWeight, style.fontWeight, "$role")
            assertEquals(if (role == TextRole.Heading) FontStyle.Italic else was.fontStyle, style.fontStyle, "$role")
        }
    }

    @Test
    fun `an interface in a script the faces lack is drawn in the look's face for it, in a bridge inside the bridge too`() {
        val seen = HashMap<String, FontFamily?>()
        compose {
            CompositionLocalProvider(Type.local provides look) {
                NxTheme(interfaceText = "\u65e5\u672c\u8a9e") {
                    seen["outer"] = MaterialTheme.typography.headlineSmall.fontFamily
                    NxTheme { seen["inner"] = MaterialTheme.typography.headlineSmall.fontFamily }
                }
                NxTheme(interfaceText = "Latin") {
                    NxTheme { seen["latin"] = MaterialTheme.typography.headlineSmall.fontFamily }
                }
            }
        }
        assertSame(script, seen["outer"])
        assertSame(script, seen["inner"], "a preview of a theme keeps the interface's script")
        assertSame(heading, seen["latin"], "and keeps each role's own face where the script needs none")
    }

    private fun compose(content: @Composable () -> Unit) {
        val scene = ImageComposeScene(8, 8, Density(1f), content = content)
        try {
            scene.render(0L).close()
        } finally {
            scene.close()
        }
    }
}
