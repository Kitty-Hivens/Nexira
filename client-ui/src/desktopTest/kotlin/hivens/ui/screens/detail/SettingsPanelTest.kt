package hivens.ui.screens.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.onEventThread
import hivens.ui.screens.detail.settings.PackSettingsCategory
import hivens.ui.settle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A pack page's settings sheet against its history entry. The router updates the page
 * in place between two entries of one pack, so the sheet has to follow the entry.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SettingsPanelTest {

    @Test
    fun `the sheet follows the entry it is drawn for, and a choice made on the page stands`() {
        var entry by mutableStateOf<Pair<Boolean, PackSettingsCategory?>>(true to PackSettingsCategory.Version)
        var panel: SettingsPanel? = null
        onEventThread {
            val scene = ImageComposeScene(50, 50, Density(1f)) {
                panel = rememberSettingsPanel("a", entry.first, entry.second)
            }
            try {
                scene.settle(4)
                val sheet = assertNotNull(panel)
                assertTrue(sheet.open)
                assertEquals(PackSettingsCategory.Version, sheet.section)

                // A rail widget opens the same pack: the entry without the settings.
                entry = false to null
                Snapshot.sendApplyNotifications()
                scene.settle(4)
                assertFalse(sheet.open, "the entry closed the settings")

                // The gear on the page itself, with the entry as it was.
                sheet.open = true
                Snapshot.sendApplyNotifications()
                scene.settle(4)
                assertTrue(sheet.open, "and the page's own choice is not taken back while the entry says the same")
            } finally {
                scene.close()
            }
        }
    }
}
