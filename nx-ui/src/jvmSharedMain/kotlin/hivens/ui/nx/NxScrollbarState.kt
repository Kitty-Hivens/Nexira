package hivens.ui.nx

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Previewed

/**
 * The auto-hiding bar for a scrollable that is described by its state.
 *
 * A bar is a desktop idea: a pointer needs something to grab, and a finger scrolls
 * the content itself. So the desktop draws [NxVerticalScrollbar] over the state's
 * adapter and a touch host draws nothing, which keeps the menus that call this free
 * of the platform's scrollbar types.
 */
@Composable
expect fun NxVerticalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier = Modifier)

@Composable
expect fun NxVerticalScrollbar(state: LazyListState, revealed: Boolean, modifier: Modifier = Modifier)

/** [NxVerticalScrollbar] laid along the bottom edge, for content that scrolls sideways. */
@Composable
expect fun NxHorizontalScrollbar(state: ScrollState, revealed: Boolean, modifier: Modifier = Modifier)

@Preview
@Composable
private fun NxScrollbarDarkPreview() = Scrollbars(dark = true)

@Preview
@Composable
private fun NxScrollbarLightPreview() = Scrollbars(dark = false)

/** Both bars revealed, as a hovered list shows them. A touch host draws no bar, so on Android the lists stand alone. */
@Composable
private fun Scrollbars(dark: Boolean) {
    Previewed(dark = dark) {
        Column(
            modifier            = Modifier.width(360.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth().height(160.dp)) {
                val vertical = rememberScrollState()
                Column(Modifier.verticalScroll(vertical).padding(12.dp)) {
                    repeat(20) { Text("Row ${it + 1}", color = NxInk.main) }
                }
                NxVerticalScrollbar(vertical, revealed = true, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
            }
            NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                val horizontal = rememberScrollState()
                Box(Modifier.padding(bottom = 10.dp)) {
                    Row(Modifier.horizontalScroll(horizontal).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(20) { Text("Column ${it + 1}", color = NxInk.main) }
                    }
                }
                NxHorizontalScrollbar(horizontal, revealed = true, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp))
            }
        }
    }
}
