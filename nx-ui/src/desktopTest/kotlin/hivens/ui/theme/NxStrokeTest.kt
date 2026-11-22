package hivens.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeAnswers
import hivens.look.stroke.api.StrokeRole
import hivens.look.stroke.api.drawStroke
import hivens.ui.nx.NxPlaceholderOutline
import hivens.ui.nx.NxRule
import hivens.ui.nx.NxSettingGroup
import hivens.ui.nx.NxSettingRow
import hivens.ui.nx.NxTabRow
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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

    @Test
    fun `under nx a tab's mark is the filled bar it was before the axis, at any density`() {
        for (density in listOf(1f, 1.25f, 1.5f, 2f)) {
            val bar = render(density) { Box(Modifier.size(26.dp, 2.dp).background(Color.Red)) }
            val mark = nxStrokeAnswers().mark
            val drawn = render(density) {
                Box(Modifier.size(26.dp, 2.dp).drawBehind { drawStroke(mark, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), SolidColor(Color.Red)) })
            }
            assertContentEquals(bar, drawn, "a mark at density $density")
        }
    }

    @Test
    fun `no stroke a look answers moves a rule, a settings group or a tab row`() {
        val heavy = StrokeAnswers.build {
            divider = StrokeRole.solid(6.dp)
            mark = StrokeRole.solid(7.dp)
        }
        val fine = StrokeAnswers.build {}
        val sizes = listOf(nxStrokeAnswers(), heavy, fine).map { answers -> layoutOf(answers) }
        assertEquals(sizes.first(), sizes[1], "a heavier stroke moved the layout")
        assertEquals(sizes.first(), sizes[2], "a hairline moved the layout")
    }

    /** The sizes a rule, a settings group and a tab row lay out at with [answers] for the stroke axis. */
    private fun layoutOf(answers: StrokeAnswers): List<IntSize> {
        val sizes = MutableList(3) { IntSize.Zero }
        val scene = ImageComposeScene(width = 400, height = 600, density = Density(1.25f)) {
            NxThemed(NeutralTheme) {
                CompositionLocalProvider(Stroke.local provides answers) {
                    Column(Modifier.width(300.dp)) {
                        NxRule(Color.Red, Modifier.onGloballyPositioned { sizes[0] = it.size })
                        NxSettingGroup("Group", Modifier.onGloballyPositioned { sizes[1] = it.size }) {
                            NxSettingRow("One")
                            NxSettingRow("Two")
                            NxSettingRow("Three")
                        }
                        NxTabRow(listOf("A", "B"), selected = 0, onSelect = {}, modifier = Modifier.onGloballyPositioned { sizes[2] = it.size })
                    }
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L).close() }
        } finally {
            scene.close()
        }
        return sizes
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
