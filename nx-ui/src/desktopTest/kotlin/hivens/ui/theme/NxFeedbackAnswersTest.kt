package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import hivens.look.feedback.api.FeedbackAnswers
import hivens.look.feedback.api.FeedbackTarget
import hivens.ui.nx.StateLayer
import hivens.ui.nx.StateLayerAlphas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * nx answers the feedback axis with the state layers nx-ui drew before the axis: a control's at
 * 8, 10 and 12 percent of its content colour at its own outline, a plane's at 6 and 12 percent of the
 * lead, and disabled content at 45 percent.
 */
class NxFeedbackAnswersTest {

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
}
