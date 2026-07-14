package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * The blessed "three dots" overflow affordance: a MoreVert button that opens an
 * [NxContextMenu] right-aligned under itself. [menuItems] is the column of
 * [NxMenuItem]s; it receives a `dismiss` callback each item calls to close the
 * menu after acting. Consolidates the kebab pattern that was hand-rolled per
 * call site (issue #387) so the app has one overflow control.
 */
@Composable
fun NxKebabButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = NxTheme.colors.textSecondary,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Symbol(NxIcon.MoreVert, contentDescription = contentDescription, tint = tint)
        }
        NxContextMenu(expanded = open, onDismissRequest = { open = false }) {
            menuItems { open = false }
        }
    }
}
