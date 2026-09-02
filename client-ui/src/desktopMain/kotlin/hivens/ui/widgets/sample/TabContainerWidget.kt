package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.SlotId
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

// Tab container: a tab-bar header over a fixed set of child slots, showing
// only the active tab's slot. Each tab is a full slot, so any widgets can
// live inside it -- drop them in while that tab is selected in the editor.
//
// The active tab is EPHEMERAL view state: it survives recomposition but is
// never written back to the layout graph, so flipping tabs at runtime does
// not rewrite layout-graph.json. The tab count is bounded by the declared
// slots (3); `tabCount` only hides the trailing tabs (their slot content is
// preserved), and the labels are editable props.
@Serializable
data class TabContainerProps(
    @PropLabel("widget.container.tabs.tabCount") @PropRange(1.0, 3.0) val tabCount: Int = 2,
    // Blank labels resolve to the localized "Tab N" at render; a non-blank
    // value is the user's own tab name (single language, by choice).
    @PropLabel("widget.container.tabs.label1") val label1: String = "",
    @PropLabel("widget.container.tabs.label2") val label2: String = "",
    @PropLabel("widget.container.tabs.label3") val label3: String = "",
)

// Transparent by default, for the same reason as the group container: the tab
// bodies carry their own planes, and a plate behind them reads as a second card.
// Named rather than absent so the editor has a value to raise.
@Widget(
    id          = "container.tabs",
    displayName = "widget.container.tabs",
    slots       = ["tab_0", "tab_1", "tab_2"],
    propsClass  = TabContainerProps::class,
    surface     = """{"fill":"base","opacity":0.0}""",
)
@Composable
fun TabContainerWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<TabContainerProps>()
    val s = LocalStrings.current
    val labels = listOf(p.label1, p.label2, p.label3)
        .mapIndexed { idx, label -> label.ifBlank { s.widgetTabDefaultLabel(idx + 1) } }
    val count = p.tabCount.coerceIn(1, labels.size)

    // Keyed on instanceId so two tab containers keep independent selection.
    var active by remember(instance.instanceId) { mutableStateOf(0) }
    val activeIdx = active.coerceIn(0, count - 1)

    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) { idx ->
                NxChoiceChip(
                    label    = labels[idx],
                    selected = idx == activeIdx,
                    onToggle = { active = idx },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SlotRenderer(
            parent   = instance,
            slot     = SlotId("tab_$activeIdx"),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
