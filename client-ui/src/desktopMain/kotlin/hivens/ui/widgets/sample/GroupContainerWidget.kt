package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// First-class container widget: holds nested sub-widgets in its "body"
// slot. The simplest possible composition primitive -- pick this from
// the palette, drop other widgets inside it. Phase A's smoke surface
// for nested drag&drop, schema_version=2's children field, and the
// editor's smallest-area-first hit-test all converge here.
//
// Visually a tinted glass card with 12dp interior padding; the body
// slot stacks its children vertically. Nesting depth is communicated
// only through EditableWidgetChrome's depth-aware border alpha;
// containers themselves stay visually quiet so the user's own widgets
// remain the figure.
@Widget(
    id          = "container.group",
    displayName = "widget.container.group",
    slots       = ["body"],
    surface     = """{"fill":"base","opacity":0.55}""",
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
