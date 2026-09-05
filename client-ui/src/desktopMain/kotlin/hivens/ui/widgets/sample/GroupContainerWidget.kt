package hivens.ui.widgets.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Holds other widgets in its "body" slot, stacked vertically.
//
// Transparent by default, because the children carry their own planes and a
// plate behind a row of plates reads as a second card rather than as a group.
// The value is named rather than absent so the editor can raise it where a
// group is meant to be one object.
@Widget(
    id          = "container.group",
    displayName = "widget.container.group",
    slots       = ["body"],
    surface     = """{"fill":"base","opacity":0.0}""",
)
@Composable
fun GroupContainerWidget(instance: WidgetInstance) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        SlotRenderer(parent = instance, slot = SlotId("body"), modifier = Modifier.fillMaxWidth())
    }
}
