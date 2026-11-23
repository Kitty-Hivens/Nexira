package hivens.ui.theme

import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackAnswers
import hivens.look.feedback.api.FeedbackTarget
import hivens.ui.nx.StateLayer
import hivens.ui.nx.StateLayerAlphas
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * nx answers the feedback axis with the state layers nx-ui drew before the axis: a control's at
 * 8, 10 and 12 percent of its content colour at its own outline, a plane's at 6 and 12 percent of the
 * lead, and disabled content at 45 percent. A plane drawn by NxSurface takes its feedback from the axis.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NxFeedbackTest {

    @Test
    fun `the answers are made once, and each target takes the state layer nx drew for it`() {
        assertSame(nxFeedbackAnswers(), nxFeedbackAnswers())
        val nx = nxFeedbackAnswers()
        assertEquals(StateLayer(RectangleShape, Color.Red, StateLayerAlphas.CONTROL), nx.indication(FeedbackTarget.Control, RectangleShape, Color.Red))
        assertEquals(StateLayer(null, null, StateLayerAlphas.CONTROL), nx.indication(FeedbackTarget.Control, null, null))
        assertEquals(StateLayer(null, Color.Blue, StateLayerAlphas.PLANE), nx.indication(FeedbackTarget.Plane, null, Color.Blue))
        assertEquals(0.45f, nx.disabledAlpha)
        assertNotEquals(FeedbackAnswers.BASE.disabledAlpha, nx.disabledAlpha, "the base is not nx")
    }

    @Test
    fun `a plane's scale has no focus of its own, and press wins over hover`() {
        assertEquals(0.06f, StateLayerAlphas.PLANE.of(pressed = false, hovered = true, focused = true))
        assertEquals(0.12f, StateLayerAlphas.PLANE.of(pressed = true, hovered = true, focused = false))
        assertEquals(0f, StateLayerAlphas.PLANE.of(pressed = false, hovered = false, focused = true))
        assertEquals(0.10f, StateLayerAlphas.CONTROL.of(pressed = false, hovered = true, focused = true))
    }

    @Test
    fun `a card under the pointer is its body with the lead at 6 percent over it, and at 12 under a press`() {
        val source = MutableInteractionSource()
        var lead = Color.Unspecified
        val frames = render(source, listOf({ }, { source.tryEmit(HoverInteraction.Enter()) }, { source.tryEmit(PressInteraction.Press(Offset.Zero)) })) {
            NxThemed(NeutralTheme) {
                lead = NxColor.lead()
                NxSurface(SurfaceKind.Card, Modifier.size(40.dp), shape = RectangleShape, interactionSource = source) { Box(Modifier.size(1.dp)) }
            }
        }
        val (rest, hovered, pressed) = frames
        for (channel in 0..2) {
            val leadChannel = channelOf(lead, channel)
            assertNear(rest[channel] * 0.94 + leadChannel * 0.06, hovered[channel], "hovered, channel $channel")
            assertNear(rest[channel] * 0.88 + leadChannel * 0.12, pressed[channel], "pressed, channel $channel")
        }
    }

    @Test
    fun `a component reads the look's feedback, and outside any look the base`() {
        var inside: Float? = null
        var outside: Float? = null
        val scene = ImageComposeScene(4, 4, Density(1f)) {
            NxThemed(NeutralTheme) { inside = Feedback.disabledAlpha }
            outside = Feedback.disabledAlpha
        }
        try {
            scene.render(0L).close()
        } finally {
            scene.close()
        }
        assertEquals(0.45f, inside)
        assertEquals(FeedbackAnswers.BASE.disabledAlpha, outside)
    }

    private fun assertNear(expected: Double, actual: Int, what: String) =
        assertTrue(abs(expected.roundToInt() - actual) <= 1, "$what: expected about $expected, got $actual")

    /** [color]'s channel [index] in the order the pixels below are read, 0 to 255. */
    private fun channelOf(color: Color, index: Int): Double = when (index) {
        // Skia's native order on desktop is BGRA.
        0 -> color.blue
        1 -> color.green
        else -> color.red
    } * 255.0

    /** The first three channels of the middle pixel of [content] after each of [steps]. */
    private fun render(source: MutableInteractionSource, steps: List<() -> Unit>, content: @Composable () -> Unit): List<List<Int>> {
        val scene = ImageComposeScene(40, 40, Density(1f), content = content)
        try {
            scene.render(0L).close()
            return steps.mapIndexed { index, step ->
                step()
                scene.render((index + 1) * 16_000_000L).use { image ->
                    val bytes = requireNotNull(Bitmap.makeFromImage(image).readPixels())
                    val at = (20 * 40 + 20) * 4
                    (0..2).map { bytes[at + it].toInt() and 0xFF }
                }
            }
        } finally {
            scene.close()
        }
    }
}
