package hivens.ui.widgets.shell

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.layers.Layer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A rail entry that opens a dialog, sign out, gets the keyboard back when the dialog
 * closes. It used to land on the first entry of the rail, Home, because the dialog
 * could only hand the focus back to the group it took it from.
 */
@OptIn(ExperimentalComposeUiApi::class)
class RailFocusReturnTest {

    @Test
    fun `the keyboard goes back to the rail entry that opened the dialog`() {
        val layers = WindowLayers()
        var open by mutableStateOf(false)
        var focusManager: FocusManager? = null
        val scene = ImageComposeScene(300, 300, density = Density(1f)) {
            NxTheme(dark = true) {
                LayerHost(layers, Modifier.fillMaxSize()) {
                    focusManager = LocalFocusManager.current
                    Column(Modifier.width(64.dp).focusGroup()) {
                        NavSlot(icon = NxIcon.Home, phase = 0f, active = true, onClick = {})
                        NavSlot(icon = NxIcon.Star, phase = 0f, active = false, onClick = {})
                        NavSlot(icon = NxIcon.ExitToApp, phase = 0f, active = false, chaosEligible = false, onClick = { open = true })
                    }
                    Layer(visible = open, modal = true, onDismissRequest = { open = false }) {
                        Box(Modifier.size(40.dp).focusable())
                    }
                }
            }
        }
        fun settle() = repeat(4) {
            Snapshot.sendApplyNotifications()
            scene.render()
        }
        try {
            settle()
            val keys = assertNotNull(focusManager)
            repeat(3) {
                keys.moveFocus(FocusDirection.Next)
                settle()
            }
            assertEquals(LOGOUT_TOP, scene.focusedTop(), "Tab reached the sign-out entry")

            open = true
            settle()
            open = false
            settle()
            assertEquals(LOGOUT_TOP, scene.focusedTop(), "the keyboard went back to the first entry, not to sign out")
        } finally {
            scene.close()
        }
    }

    /** The top edge of the control that holds the keyboard focus, or null when none does. */
    private fun ImageComposeScene.focusedTop(): Float? =
        semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.boundsInRoot?.top

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    private companion object {
        /** The third rail entry, each one 54 high at density one. */
        const val LOGOUT_TOP = 108f
    }
}
