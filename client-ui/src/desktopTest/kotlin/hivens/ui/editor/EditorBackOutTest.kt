package hivens.ui.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.nx.NxDialog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The editor's back-out as entries of the window's dismiss stack: what one Escape,
 * or one Android back, closes while editing, and that nothing of the session is
 * left for either to reach once it ends. The reset confirmations are the dialogs
 * the host opens, modal layers with an entry of their own.
 */
class EditorBackOutTest {

    private var editing by mutableStateOf(true)
    private var selection by mutableStateOf(false)
    private var slotMenu by mutableStateOf(false)
    private var previewing by mutableStateOf(false)
    private var resetSurface by mutableStateOf(false)
    private var resetAll by mutableStateOf(false)

    private val layers = WindowLayers()
    private val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
        LayerHost(layers, Modifier.fillMaxSize()) {
            EditorBackOut(
                editing             = editing,
                onLeave             = { editing = false },
                selection           = selection,
                onClearSelection    = { selection = false },
                slotMenu            = slotMenu,
                onCloseSlotMenu     = { slotMenu = false },
                previewing          = previewing,
                onEndPreview        = { previewing = false },
            )
            // As the host gates them: only while editing.
            if (editing && resetSurface) NxDialog(onDismissRequest = { resetSurface = false }) {}
            if (editing && resetAll) NxDialog(onDismissRequest = { resetAll = false }) {}
        }
    }
    private var time = 0L

    @AfterTest
    fun tearDown() = scene.close()

    private fun settle() {
        repeat(3) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            scene.render(time)
        }
    }

    /**
     * One Escape with no field focused, which reaches the stack as back. How a key
     * gets there is the layers module's to test.
     */
    private fun escape(): Boolean = layers.dismiss.dismissTop().also { settle() }

    private fun state() = listOf(editing, selection, slotMenu, previewing, resetSurface, resetAll)

    @Test
    fun `one Escape backs out one step from the top and the last one leaves the editor`() {
        settle()
        // A right click selects a slot and opens its menu in one go, then preview.
        selection = true
        slotMenu = true
        settle()
        previewing = true
        settle()

        assertTrue(escape())
        assertFalse(previewing, "preview first, because it hides the slot steps")
        assertTrue(slotMenu)

        escape()
        assertFalse(slotMenu, "then the menu")
        assertTrue(selection)

        escape()
        assertFalse(selection, "then the selection")
        assertTrue(editing)

        escape()
        assertFalse(editing, "and the editor itself last")

        assertFalse(escape(), "with the editor closed, Escape is left to whoever else wants it")
    }

    // The reset confirmation used to outlive the editor, and Escape closed the editor
    // behind it instead of the confirmation in front of it.
    @Test
    fun `Escape closes a reset confirmation and leaves the session behind it as it was`() {
        settle()
        selection = true
        settle()
        resetSurface = true
        settle()

        escape()
        assertEquals(listOf(true, true, false, false, false, false), state(), "only the confirmation closed")

        resetSurface = true
        settle()
        // Reset everything is asked from the surface confirmation, which closes as it opens.
        resetSurface = false
        resetAll = true
        settle()
        escape()
        assertFalse(resetAll)
        assertTrue(editing)
    }

    @Test
    fun `a confirmation asked for in preview closes before preview does`() {
        settle()
        previewing = true
        settle()
        resetAll = true
        settle()

        escape()
        assertFalse(resetAll)
        assertTrue(previewing)
    }

    @Test
    fun `nothing of a session that ended is left on the stack`() {
        settle()
        selection = true
        resetAll = true
        settle()

        // Ctrl+E closes the editor without going through the steps, and the flags are
        // cleared a frame later. In between there is nothing for Escape to reach.
        editing = false
        settle()
        assertFalse(layers.dismiss.canDismiss)
        assertFalse(layers.dismiss.handlesBack, "no dialog is left holding the window either")
        assertFalse(escape())
        assertTrue(resetAll, "nothing was closed on a session that is gone")
    }

    @Test
    fun `Android back runs the same steps through the same stack`() {
        settle()
        resetSurface = true
        settle()

        assertTrue(layers.dismiss.handlesBack)
        assertTrue(layers.dismiss.dismissTop())
        settle()
        assertFalse(resetSurface)
        assertTrue(layers.dismiss.dismissTop())
        settle()
        assertFalse(editing)
        assertFalse(layers.dismiss.handlesBack, "the navigation gets back once the editor is closed")
    }
}
