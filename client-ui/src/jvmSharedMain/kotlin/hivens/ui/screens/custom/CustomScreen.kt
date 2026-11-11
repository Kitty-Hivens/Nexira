package hivens.ui.screens.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxInk
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SCREEN_MAIN_SLOT
import hivens.widget.model.screen

/**
 * A screen somebody made, opened by its id.
 *
 * One slot, filling the pane, and nothing around it: what the screen is lives in
 * the slot, which the editor arranges and the slot's own viewport scrolls or not.
 * The record is read live, so a rename shows at once and a deletion while the
 * screen is open says so instead of drawing an empty pane.
 */
@Composable
fun CustomScreen(id: String) {
    PuppetScreen("Custom:$id")
    val spec = LocalLayoutGraph.current.screen(id)
    if (spec == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(LocalStrings.current.screenMissing, style = MaterialTheme.typography.bodyLarge, color = NxInk.quiet)
        }
        return
    }
    SlotRenderer(spec.surface, SCREEN_MAIN_SLOT, Modifier.fillMaxSize(), spacing = 8.dp)
}
