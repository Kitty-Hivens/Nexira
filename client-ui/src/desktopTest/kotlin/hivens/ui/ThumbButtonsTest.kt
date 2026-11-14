package hivens.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.layers.Layer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mouse's back and forward buttons. Back is back, as Escape and a phone's gesture
 * are, so it closes what the window has open before it moves the history, and neither
 * button moves the page behind a modal.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ThumbButtonsTest {

    private fun history() = NavBackStack(Screen.Library).also { it.navigate(Screen.PackDetail("abc")) }

    @Test
    fun `back closes what the window has open before it moves the history`() {
        val layers = WindowLayers()
        val nav = history()
        var closed = 0
        val sheet = layers.dismiss.push(null) { closed++ }

        thumbBack(layers, nav)
        assertEquals(1, closed)
        assertEquals(Screen.PackDetail("abc"), nav.current, "the page stays under what was open")

        sheet.close()
        thumbBack(layers, nav)
        assertEquals(Screen.Library, nav.current, "with nothing open it is history back")
    }

    @Test
    fun `under a modal neither button moves the page behind it`() {
        val layers = WindowLayers()
        val nav = history()
        var modal by mutableStateOf(true)
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = modal, modal = true) {}
            }
        }
        try {
            scene.settle(4)
            thumbBack(layers, nav)
            assertEquals(Screen.PackDetail("abc"), nav.current, "back does not move the page behind the modal")

            nav.back()
            thumbForward(layers, nav)
            assertEquals(Screen.Library, nav.current, "nor does forward")

            modal = false
            Snapshot.sendApplyNotifications()
            scene.settle(4)
            thumbForward(layers, nav)
            assertEquals(Screen.PackDetail("abc"), nav.current)
        } finally {
            scene.close()
        }
    }
}
