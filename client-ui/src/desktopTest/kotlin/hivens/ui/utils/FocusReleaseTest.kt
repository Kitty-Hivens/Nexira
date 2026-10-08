package hivens.ui.utils

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a press in the shell lets go of the keyboard focus, and when it must not. */
@OptIn(ExperimentalComposeUiApi::class)
class FocusReleaseTest {

    private class Probe {
        var focused = false
    }

    /** A 400x200 shell with a 100x100 focusable element in its corner, focused. */
    private fun scene(probe: Probe): ImageComposeScene = ImageComposeScene(400, 200, density = Density(1f)) {
        val requester = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().releaseFocusOnPress()) {
            Box(Modifier.size(100.dp).focusRequester(requester).onFocusChanged { probe.focused = it.isFocused }.focusable())
        }
        LaunchedEffect(Unit) { requester.requestFocus() }
    }

    private fun ImageComposeScene.press(at: Offset, button: PointerButton = PointerButton.Primary) {
        val buttons = if (button == PointerButton.Primary) PointerButtons(isPrimaryPressed = true) else PointerButtons(isSecondaryPressed = true)
        sendPointerEvent(PointerEventType.Press, at, buttons = buttons, button = button)
        render()
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button)
        render()
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    @Test
    fun `a press on the focused element keeps the focus, and one outside it lets go`() = onEdt {
        val probe = Probe()
        val scene = scene(probe)
        try {
            scene.render()
            scene.render()
            assertTrue(probe.focused, "the element took the focus")

            scene.press(Offset(50f, 50f))
            assertTrue(probe.focused, "a click inside what holds the focus does not take it away")

            scene.press(Offset(300f, 150f), PointerButton.Secondary)
            assertTrue(probe.focused, "a right press is the focused element's own business")

            scene.press(Offset(300f, 150f))
            assertFalse(probe.focused, "a click elsewhere lets go")
        } finally {
            scene.close()
        }
    }
}
