package hivens.ui.nx

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract of the state-layer alpha: press over hover over idle, at the M3
 * scale. Pure logic -- no renderer, no golden image, so it survives any UI
 * redesign (it pins the primitive's behaviour, not the app's appearance).
 */
class StateLayerAlphaTest {
    @Test
    fun press_beats_hover_beats_idle() {
        assertEquals(0.12f, stateLayerAlpha(pressed = true, hovered = true))
        assertEquals(0.12f, stateLayerAlpha(pressed = true, hovered = false))
        assertEquals(0.08f, stateLayerAlpha(pressed = false, hovered = true))
        assertEquals(0f, stateLayerAlpha(pressed = false, hovered = false))
    }
}
