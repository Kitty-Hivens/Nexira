package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the caret is after the search box is cleared with its own button. */
@OptIn(ExperimentalComposeUiApi::class)
class SearchFieldFocusTest {

    private class Probe {
        var value = "sodium"
        var hasFocus = false
    }

    private var clock = 0L

    private fun ImageComposeScene.frames(count: Int = 30) = repeat(count) {
        clock += 50_000_000L
        render(clock)
    }

    private fun ImageComposeScene.press(at: Offset) {
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        frames(1)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        frames(1)
    }

    /** The clear button pressed, then gone with the text: the keys after it went nowhere. */
    @Test
    fun `clearing the search leaves the caret in the field`() = SwingUtilities.invokeAndWait {
        val probe = Probe()
        val scene = ImageComposeScene(WIDTH, 80, density = Density(1f)) {
            NxTheme(dark = true) {
                var value by remember { mutableStateOf(probe.value) }
                Box(Modifier.onFocusChanged { probe.hasFocus = it.hasFocus }) {
                    SearchField(
                        value = value,
                        onValueChange = { value = it; probe.value = it },
                        placeholder = "",
                        modifier = Modifier.width(WIDTH.dp),
                    )
                }
            }
        }
        try {
            scene.frames()
            scene.press(Offset(100f, 26f))
            assertTrue(probe.hasFocus, "a press in the field gives it the caret")

            scene.press(Offset(WIDTH - 22f, 26f))
            scene.frames()

            assertEquals("", probe.value, "the button cleared the field")
            assertTrue(probe.hasFocus, "the caret is back in the field, not lost with the button")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 400
    }
}
