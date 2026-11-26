package hivens.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance

/**
 * The right rail's insides: a slot that takes the height, and one pinned under it. What stands in them, and how the
 * first scrolls, is the arrangement's, so a config that holds them as its own shows its own column there: the feed
 * and the message history at rest, a project's blocks, the catalogue's filters.
 */
@Composable
fun RightPanel(instance: WidgetInstance, modifier: Modifier = Modifier) {
    Column(modifier) {
        SlotRenderer(instance, SlotId("main"), Modifier.weight(1f).fillMaxWidth())
        SlotRenderer(instance, SlotId("bottom"), Modifier.fillMaxWidth())
    }
}
