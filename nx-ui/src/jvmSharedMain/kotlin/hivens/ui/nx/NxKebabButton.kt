package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

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
    tint: Color = NxInk.quiet,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        NxIconButton(
            icon               = NxIcon.MoreVert,
            contentDescription = contentDescription,
            onClick            = { open = true },
            tint               = tint,
        )
        NxContextMenu(expanded = open, onDismissRequest = { open = false }) {
            menuItems { open = false }
        }
    }
}

@Preview
@Composable
private fun NxKebabButtonDarkPreview() = KebabButtons(dark = true)

@Preview
@Composable
private fun NxKebabButtonLightPreview() = KebabButtons(dark = false)

@Composable
private fun KebabButtons(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(200.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxKebabButton("More") { dismiss -> NxMenuItem("Rename", onClick = dismiss) }
            NxKebabButton("More", tint = NxInk.main) { dismiss -> NxMenuItem("Rename", onClick = dismiss) }
        }
    }
}
