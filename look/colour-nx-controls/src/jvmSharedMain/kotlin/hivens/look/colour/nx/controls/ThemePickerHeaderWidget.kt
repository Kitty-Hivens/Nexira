package hivens.look.colour.nx.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.i18n.api.strings
import hivens.themes.api.ThemesKeys
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.puppet.PuppetClick
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxInk
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.history.api.HistoryKeys

// The picker's title and Apply, on a panel of its own like the grid and the preview under it: the
// title is text, and on the bare page it lay over whatever the wallpaper had there. Not removable,
// since a picker without Apply has lost its whole purpose. Apply puts the pending choice in use and
// leaves the picker, and only once the choice is in use: with nothing to apply it, Apply does nothing
// at all rather than walk away from a choice that did not land.
//
// Drawn only while nx draws the colour. Already there when the page opens, as the page's own chrome.
@Widget(
    id = "theme.picker.header",
    displayName = "widget.theme.picker.header",
    removable = false,
    drawsOwnSurface = true,
    enter = "none",
    needs = [ThemesKeys.ACTIVE_ID, "theme.picker.selection"],
    shownWhen = NX_DRAWING_ID,
)
@Composable
fun ThemePickerHeaderWidget() {
    val active = rememberScopedSource(ThemesKeys.Active).valueOrNull() ?: return
    val selection = rememberScopedSource(ThemeSelectionSource).valueOrNull() ?: return
    val s = strings(ColourNxControls.ID)
    val apply = rememberScopedCommand(ThemesKeys.Choose)
    val back = rememberScopedAction(HistoryKeys.Back)
    val applyAndLeave: () -> Unit = {
        if (apply != null) {
            apply(selection.or(active).id)
            back?.invoke()
        }
    }

    PuppetClick("themePicker.back") { back?.invoke() }
    PuppetClick("themePicker.apply") { applyAndLeave() }

    NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
        // Wraps rather than squeezes: on a phone held upright Apply was left two letters.
        FlowRow(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement   = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // No back arrow of its own. The window frame already carries one,
            // enabled by the same history this screen would pop.
            Text(
                text       = s.text("themePickerTitle"),
                style      = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color      = NxInk.main,
            )
            NxButton(
                label = s.text("themePickerApply"),
                onClick = applyAndLeave,
                style = NxButtonStyle.Primary,
            )
        }
    }
}
