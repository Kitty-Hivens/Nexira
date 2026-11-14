package hivens.ui.screens.versions

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import hivens.ui.icons.NxIcon
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.nx.NxIconButton
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A content row's versions button opens the version picker, and closing the picker
 * gives the keyboard back to that button. The picker was drawn in place rather than
 * as a layer, so it had nowhere to give the keyboard back to.
 */
@OptIn(ExperimentalComposeUiApi::class)
class VersionPickerFocusReturnTest {

    @Test
    fun `closing the version picker gives the keyboard back to the button that opened it`() {
        val layers = WindowLayers()
        var open by mutableStateOf(false)
        var focusManager: FocusManager? = null
        val scene = ImageComposeScene(900, 600, density = Density(1f)) {
            NxTheme(dark = true) {
                LayerHost(layers, Modifier.fillMaxSize()) {
                    focusManager = LocalFocusManager.current
                    Row(Modifier.focusGroup()) {
                        NxIconButton(icon = NxIcon.Info, contentDescription = "Details", onClick = {})
                        NxIconButton(icon = NxIcon.SwapHoriz, contentDescription = "Versions", onClick = { open = true })
                    }
                    if (open) {
                        VersionPickerWindow(
                            title = "Versions",
                            packName = "Sodium",
                            packIcon = null,
                            versions = emptyList(),
                            intentFor = { PickerIntent.Switch },
                            onConfirm = {},
                            onDismiss = { open = false },
                        )
                    }
                }
            }
        }
        var time = 0L
        fun settle() = repeat(20) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            scene.render(time)
        }
        try {
            settle()
            val keys = assertNotNull(focusManager)
            repeat(2) {
                keys.moveFocus(FocusDirection.Next)
                settle()
            }
            val settings = assertNotNull(scene.focusedLeft(), "Tab reached the buttons")
            assertTrue(settings > 0f, "Tab stopped on the details button, not on versions")

            open = true
            settle()
            open = false
            settle()
            assertEquals(settings, scene.focusedLeft(), "the keyboard went to the details button, not back to versions")
        } finally {
            scene.close()
        }
    }

    /** The left edge of the control that holds the keyboard focus, or null when none does. */
    private fun ImageComposeScene.focusedLeft(): Float? =
        semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
            ?.boundsInRoot?.left

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }
}
