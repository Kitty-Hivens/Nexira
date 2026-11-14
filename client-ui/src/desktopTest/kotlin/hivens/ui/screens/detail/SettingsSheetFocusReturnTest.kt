package hivens.ui.screens.detail

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.nx.NxSideSheet
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pack page's Settings button opens the settings sheet, and closing the sheet
 * gives the keyboard back to that button. It used to land on the first control of
 * the group the sheet took it from, the folder button beside it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsSheetFocusReturnTest {

    @Test
    fun `closing the settings sheet gives the keyboard back to the Settings button`() {
        val layers = WindowLayers()
        var open by mutableStateOf(false)
        var focusManager: FocusManager? = null
        val scene = ImageComposeScene(900, 600, density = Density(1f)) {
            NxTheme(dark = true) {
                LayerHost(layers, Modifier.fillMaxSize()) {
                    focusManager = LocalFocusManager.current
                    Row(Modifier.focusGroup()) {
                        HeroAction(NxIcon.FolderOpen, null) {}
                        HeroAction(NxIcon.Settings, "Settings") { open = true }
                    }
                    if (open) {
                        NxSideSheet(onDismissRequest = { open = false }) { Box(Modifier.size(40.dp).focusable()) }
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
            assertTrue(settings > 0f, "Tab stopped on the folder button, not on Settings")

            open = true
            settle()
            open = false
            settle()
            assertEquals(settings, scene.focusedLeft(), "the keyboard went to the folder button, not back to Settings")
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
