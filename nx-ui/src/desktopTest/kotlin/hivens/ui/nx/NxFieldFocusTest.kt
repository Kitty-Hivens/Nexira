package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/** A field takes the caret from a press anywhere on it, its inset included. */
@OptIn(ExperimentalComposeUiApi::class)
class NxFieldFocusTest {

    private class Probe {
        var hasFocus = false
    }

    @Test
    fun `a press on the field's inset gives it the caret`() = SwingUtilities.invokeAndWait {
        val probe = Probe()
        val scene = ImageComposeScene(300, 60, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.onFocusChanged { probe.hasFocus = it.hasFocus }) {
                    NxField(value = "", onValueChange = {}, placeholder = "", modifier = Modifier.width(300.dp))
                }
            }
        }
        try {
            scene.render()
            // Four pixels in from the left edge, halfway down: inside the field's
            // ten of horizontal inset, clear of the rounded corners.
            val at = Offset(4f, 15f)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.render()
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.render()

            assertTrue(probe.hasFocus, "the inset is part of the field")
        } finally {
            scene.close()
        }
    }
}
