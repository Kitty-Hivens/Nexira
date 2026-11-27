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
import hivens.history.api.History
import hivens.ui.navigation.LauncherConfigs
import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mouse's back and forward buttons. Back is back, as Escape and a phone's gesture
 * are, so it closes what the window has open before it moves the history, and neither
 * button moves the page behind a modal.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ThumbButtonsTest {

    /** A history the buttons move, as the history module's back and forward do. */
    private class Nav(var now: History) {
        val back: () -> Unit = { now = now.back() }
        val forward: () -> Unit = { now = now.forward() }

        fun show(show: Show) {
            now = now.show(show)
        }
    }

    private fun history() = Nav(History(LauncherConfigs.Library)).also { it.show(LauncherConfigs.pack("abc")) }

    private val pack = LauncherConfigs.pack("abc").let { HistoryEntry(it.config, it.values) }

    @Test
    fun `back closes what the window has open before it moves the history`() {
        val layers = WindowLayers()
        val nav = history()
        var closed = 0
        val sheet = layers.dismiss.push(null) { closed++ }

        thumbBack(layers, nav.back)
        assertEquals(1, closed)
        assertEquals(pack, nav.now.current, "the page stays under what was open")

        sheet.close()
        thumbBack(layers, nav.back)
        assertEquals(HistoryEntry(LauncherConfigs.Library), nav.now.current, "with nothing open it is history back")
    }

    // Nothing a thumb press does reaches the window itself: at the root the history
    // stays where it is, and the composition is told that back had nowhere to go.
    @Test
    fun `back at the root moves nothing and is counted as a back at the root`() {
        val layers = WindowLayers()
        val nav = Nav(History(LauncherConfigs.Home))
        thumbBack(layers, nav.back)
        assertEquals(listOf(HistoryEntry(LauncherConfigs.Home)), nav.now.entries)
        assertEquals(1, nav.now.backsAtRoot)

        nav.show(LauncherConfigs.section(LauncherConfigs.Library))
        thumbBack(layers, nav.back)
        assertEquals(HistoryEntry(LauncherConfigs.Home), nav.now.current, "back from a rail section comes home")
        assertEquals(1, nav.now.backsAtRoot)
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
            thumbBack(layers, nav.back)
            assertEquals(pack, nav.now.current, "back does not move the page behind the modal")

            nav.back()
            thumbForward(layers, nav.forward)
            assertEquals(HistoryEntry(LauncherConfigs.Library), nav.now.current, "nor does forward")

            modal = false
            Snapshot.sendApplyNotifications()
            scene.settle(4)
            thumbForward(layers, nav.forward)
            assertEquals(pack, nav.now.current)
        } finally {
            scene.close()
        }
    }
}
