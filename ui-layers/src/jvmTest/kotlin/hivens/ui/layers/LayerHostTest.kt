package hivens.ui.layers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The host in a real composition: where layers land in the order, and where input
 * reaches under a modal.
 */
class LayerHostTest {

    @Test
    fun `a layer counts as shown from its visible edge and not from when it was composed`() {
        val layers = WindowLayers()
        var aVisible by mutableStateOf(false)
        val names = HashMap<LayerResident, String>()
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = aVisible) { Named("a", names) }
                Layer(visible = true) { Named("b", names) }
            }
        }.use { scene ->
            scene.settle()
            assertEquals(listOf("b"), layers.residents.map(names::getValue))

            aVisible = true
            scene.settle()
            assertEquals(listOf("b", "a"), layers.residents.map(names::getValue), "composed first, shown last, on top")
        }
    }

    @Test
    fun `a layer opened from inside another is its child and stays above it when the parent comes back`() {
        val layers = WindowLayers()
        var sheetVisible by mutableStateOf(true)
        var otherVisible by mutableStateOf(false)
        val names = HashMap<LayerResident, String>()
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = sheetVisible) {
                    Named("sheet", names)
                    Layer(visible = true) { Named("menu", names) }
                }
                Layer(visible = otherVisible) { Named("other", names) }
            }
        }.use { scene ->
            scene.settle()
            assertEquals(listOf("sheet", "menu"), layers.residents.map(names::getValue))

            otherVisible = true
            scene.settle()
            assertEquals(listOf("sheet", "menu", "other"), layers.residents.map(names::getValue))

            sheetVisible = false
            scene.settle()
            sheetVisible = true
            scene.settle()
            assertEquals(listOf("other", "sheet", "menu"), layers.residents.map(names::getValue))
        }
    }

    @Test
    fun `a pinned layer stays on top of one shown after it`() {
        val layers = WindowLayers()
        var laterVisible by mutableStateOf(false)
        val names = HashMap<LayerResident, String>()
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = true, pinnedTop = true) { Named("debug", names) }
                Layer(visible = laterVisible, modal = true) { Named("dialog", names) }
            }
        }.use { scene ->
            scene.settle()
            laterVisible = true
            scene.settle()
            assertEquals(listOf("dialog", "debug"), layers.residents.map(names::getValue))
        }
    }

    @Test
    fun `a modal blocks the window and older layers but not one that stays usable`() {
        val layers = WindowLayers()
        var modalVisible by mutableStateOf(false)
        var base = 0
        var menu = 0
        var pill = 0
        var dialog = 0
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Target(x = 0, y = 0) { base++ }
                Layer(visible = true, staysUsable = true) { Target(x = 100, y = 0) { pill++ } }
                Layer(visible = true) { Target(x = 200, y = 0) { menu++ } }
                Layer(visible = modalVisible, modal = true) { Target(x = 0, y = 200) { dialog++ } }
            }
        }.use { scene ->
            scene.settle()
            scene.click(25f, 25f)
            scene.click(125f, 25f)
            scene.click(225f, 25f)
            assertEquals(listOf(1, 1, 1), listOf(base, pill, menu), "nothing is blocked without a modal")

            modalVisible = true
            scene.settle()
            scene.click(25f, 25f)
            scene.click(225f, 25f)
            assertEquals(1, base, "the window's own content is under the modal")
            assertEquals(1, menu, "an older layer is under the modal")

            scene.click(125f, 25f)
            assertEquals(2, pill, "a layer that stays usable keeps working under the modal")
            scene.click(25f, 225f)
            assertEquals(1, dialog, "the modal itself takes input")
        }
    }

    @Test
    fun `a press outside a modal asks it to close unless something usable took the press`() {
        val layers = WindowLayers()
        var modalVisible by mutableStateOf(true)
        var dismissed = 0
        var pill = 0
        var dialog = 0
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = true, staysUsable = true) { Target(x = 100, y = 0) { pill++ } }
                Layer(visible = modalVisible, modal = true, onDismissRequest = { dismissed++ }) {
                    Target(x = 0, y = 200) { dialog++ }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.click(25f, 225f)
            assertEquals(0, dismissed, "a press on the modal is the modal's")
            assertEquals(1, dialog)

            scene.click(125f, 25f)
            assertEquals(1, pill)
            assertEquals(0, dismissed, "the pill's Cancel does not close the dialog it sits under")

            scene.click(250f, 250f)
            assertEquals(1, dismissed, "a press on nothing outside the modal closes it")
        }
    }

    // A modal confirmation opened from a sheet after a menu: the confirmation is the
    // newest thing on screen, and the menu must neither draw over it nor take input.
    @Test
    fun `a modal opened from inside a layer stands above a later sibling of its parent`() {
        val layers = WindowLayers()
        var menuVisible by mutableStateOf(false)
        var confirmVisible by mutableStateOf(false)
        val names = HashMap<LayerResident, String>()
        var menu = 0
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = true) {
                    Named("sheet", names)
                    Layer(visible = confirmVisible, modal = true) { Named("confirm", names) }
                }
                Layer(visible = menuVisible) {
                    Named("menu", names)
                    Target(x = 200, y = 0) { menu++ }
                }
            }
        }.use { scene ->
            scene.settle()
            menuVisible = true
            scene.settle()
            confirmVisible = true
            scene.settle()

            assertEquals(listOf("menu", "sheet", "confirm"), layers.residents.map(names::getValue))
            scene.click(225f, 25f)
            assertEquals(0, menu, "the menu is under the confirmation")
        }
    }

    // A menu opened from the pill under a dialog stays the pill's: above it, and
    // usable with it.
    @Test
    fun `a layer opened from a usable layer stays above it and usable under a modal`() {
        val layers = WindowLayers()
        var dialogVisible by mutableStateOf(false)
        val names = HashMap<LayerResident, String>()
        var choice = 0
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = true, staysUsable = true) {
                    Named("pill", names)
                    Layer(visible = true) {
                        Named("pill menu", names)
                        Target(x = 100, y = 0) { choice++ }
                    }
                }
                Layer(visible = true) { Named("other", names) }
                Layer(visible = dialogVisible, modal = true) { Named("dialog", names) }
            }
        }.use { scene ->
            scene.settle()
            dialogVisible = true
            scene.settle()

            assertEquals(listOf("other", "pill", "pill menu", "dialog"), layers.residents.map(names::getValue))
            scene.click(125f, 25f)
            assertEquals(1, choice)
        }
    }

    @Test
    fun `a layer's content keeps the composition locals of where it was opened`() {
        val layers = WindowLayers()
        val callSite = compositionLocalOf<String?> { null }
        var seen: String? = null
        var resident: LayerResident? = null
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                CompositionLocalProvider(callSite provides "the screen") {
                    Layer(visible = true) {
                        seen = callSite.current
                        resident = LocalResident.current
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            assertEquals("the screen", seen, "provided only at the call site, read in the host")
            assertSame(layers.residents.single(), resident, "inside a layer, the layer is the resident")
        }
    }

    // Every nx-ui menu is a Compose popup with an owner of its own until the menus
    // move onto layers, and a layer opened from one is drawn in the host's owner.
    @Test
    fun `a layer opened inside a popup answers to the host's owner and keeps the popup's other locals`() {
        val layers = WindowLayers()
        val callSite = compositionLocalOf<String?> { null }
        var hostFocus: FocusManager? = null
        var popupFocus: FocusManager? = null
        var layerFocus: FocusManager? = null
        var seen: String? = null
        LayerScene {
            LayerHost(layers, Modifier.fillMaxSize()) {
                hostFocus = LocalFocusManager.current
                Popup {
                    popupFocus = LocalFocusManager.current
                    CompositionLocalProvider(callSite provides "the menu") {
                        Layer(visible = true) {
                            layerFocus = LocalFocusManager.current
                            seen = callSite.current
                        }
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            assertNotSame(hostFocus, popupFocus, "the popup has an owner of its own, or this proves nothing")
            assertSame(hostFocus, layerFocus)
            assertEquals("the menu", seen)
        }
    }
}

@Composable
private fun Named(name: String, names: MutableMap<LayerResident, String>) {
    val resident = LocalResident.current ?: return
    names[resident] = name
}

/** A 50 by 50 clickable square at ([x], [y]) in the window, counting its clicks. */
@Composable
private fun Target(x: Int, y: Int, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        Box(Modifier.offset(x.dp, y.dp).size(50.dp).clickable(onClick = onClick))
    }
}
