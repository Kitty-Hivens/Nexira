package hivens.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeAnswers
import hivens.look.stroke.api.StrokeRole
import hivens.ui.nx.NxPlaceholderOutline
import hivens.ui.nx.NxRule
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * nx-ui draws its edges, rules, marks and placeholder outlines through the stroke axis, and under nx it
 * draws them in exactly the pixels it drew before the axis: a rule is Material's divider, a placeholder
 * outline the dashed round rectangle the editor drew. The answers are made once, and a component reads
 * the look around it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NxStrokeTest {

    @Test
    fun `the answers are made once, so a role is the same value wherever it is read`() {
        assertSame(nxStrokeAnswers(), nxStrokeAnswers())
        assertNotEquals(StrokeAnswers.BASE.edge, nxStrokeAnswers().edge, "the base is not nx")
    }

    @Test
    fun `a component drawn in an nx theme reads nx's strokes, and outside it the look around it`() {
        val seen = LinkedHashMap<String, Any?>()
        val own = StrokeAnswers.build { divider = StrokeRole.solid(3.dp) }
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            NxThemed(NeutralTheme) { seen["themed"] = Stroke.divider }
            CompositionLocalProvider(Stroke.local provides own) { seen["own"] = Stroke.divider }
            seen["outside"] = Stroke.divider
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        assertSame(nxStrokeAnswers().divider, seen["themed"])
        assertSame(own.divider, seen["own"])
        assertSame(StrokeAnswers.BASE.divider, seen["outside"], "outside any look the base answers")
    }

    @Test
    fun `under nx a rule is the divider Material drew, at any density`() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            val material = render(density) { HorizontalDivider(color = Color.Red, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
            val rule = render(density) { NxRule(Color.Red, Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
            assertContentEquals(material, rule, "a rule at density $density")
        }
    }

    @Test
    fun `under nx a placeholder outline is the dashed outline the editor drew before the axis`() {
        for (density in listOf(1f, 1.25f, 2f)) {
            val before = render(density) {
                Box(Modifier.size(120.dp, 68.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            color        = Color.Blue.copy(alpha = 0.6f),
                            style        = DrawStyle(
                                width      = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f),
                            ),
                            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                        )
                    }
                }
            }
            val after = render(density) {
                Box(Modifier.size(120.dp, 68.dp)) {
                    NxPlaceholderOutline(Color.Blue.copy(alpha = 0.6f), RoundedCornerShape(12.dp), Modifier.fillMaxSize())
                }
            }
            assertContentEquals(before, after, "a placeholder at density $density")
        }
    }

    /** [content] in an nx theme, off screen at [density], as its RGBA bytes. */
    private fun render(density: Float, content: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = 200, height = 100, density = Density(density)) {
            NxThemed(NeutralTheme) { Column(Modifier.width(160.dp)) { content() } }
        }
        try {
            scene.render(0L).use { image -> return requireNotNull(Bitmap.makeFromImage(image).readPixels()) }
        } finally {
            scene.close()
        }
    }
}
