package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import hivens.ui.screens.detail.settings.PackSettingsCategory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The screen router: two history entries of one destination differ by what the
 * screen stamped onto its entry, and share the destination's saved state.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenRouterTest {

    // A pack page with its settings open, and a widget on a rail that opens the same
    // pack: the two entries are different values with one retention key, so the swap
    // drew both while it faded from one to the other, each under the same saved
    // state, and the window went down. An image scene hands what a window would
    // throw to its coroutine context instead, which is where it is caught here.
    @Test
    fun `two entries of one destination in a row do not take the window down`() {
        var current by mutableStateOf<Screen>(Screen.PackDetail("a", openSettings = true, settingsSection = PackSettingsCategory.Version))
        val drawn = ArrayList<Screen>()
        val errors = ArrayList<Throwable>()
        onEventThread {
            val scene = ImageComposeScene(200, 200, Density(1f), Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> errors += e }) {
                ScreenRouter(current, listOf(current)) { screen ->
                    val visits by rememberSaveable { mutableStateOf(0) }
                    drawn += screen
                    Box(Modifier.size((10 + visits).dp))
                }
            }
            try {
                var t = 0L
                fun frames() = repeat(20) {
                    t += 16_000_000L
                    scene.render(t)
                }
                scene.render(t)
                current = Screen.PackDetail("a")
                Snapshot.sendApplyNotifications()
                frames()
                // And the router still answers afterwards.
                current = Screen.PackVersions("a")
                Snapshot.sendApplyNotifications()
                frames()
            } finally {
                scene.close()
            }
        }
        assertEquals(emptyList(), errors.map { it.toString() })
        assertEquals(Screen.PackVersions("a"), drawn.last())
    }
}
