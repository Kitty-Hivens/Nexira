package hivens.ui.widgets.themepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.puppet.PuppetScreen
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance

// theme.picker surface composable. What the theme.picker config holds. A frame
// of slots and nothing else: the header (the title and Apply) on top, and the grid and
// the preview under it, side by side or stacked by the width. What fills them, the
// themes on offer, the pending choice and applying it, is the colour provider's
// controls', which hold the choice in the page, so the frame names none of it.
@Composable
fun ThemePickerSurface(instance: WidgetInstance) {
    PuppetScreen("ThemePicker")

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        SlotRenderer(instance, SlotId("header"), Modifier.fillMaxWidth().padding(bottom = 24.dp))
        // Grid is the editable panel. The preview reads the same selection, so
        // removing the preview widget hides it without breaking selection.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < STACK_BELOW) {
                // The preview over the grid on a window too narrow for them side by side:
                // beside it the preview was left a strip a few letters wide.
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SlotRenderer(instance, SlotId("preview"), Modifier.fillMaxWidth().height(PREVIEW_HEIGHT_STACKED))
                    SlotRenderer(instance, SlotId("grid"), Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                Row(
                    modifier              = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SlotRenderer(instance, SlotId("grid"), Modifier.weight(2f).fillMaxHeight())
                    SlotRenderer(instance, SlotId("preview"), Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/** Under this width the preview sits above the grid instead of beside it. */
private val STACK_BELOW = 560.dp
private val PREVIEW_HEIGHT_STACKED = 260.dp
