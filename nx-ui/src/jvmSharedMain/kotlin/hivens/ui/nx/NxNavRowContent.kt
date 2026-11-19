package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.look.shape.api.Shape
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

/**
 * Leading icon + label for one vertical-nav entry, selected styling
 * applied. The caller owns the surrounding clickable Row (background,
 * shape, click target) so each nav can keep its own selection wiring;
 * this is only the shared row body.
 */
@Composable
fun NxNavRowContent(icon: IconKey, label: String, isSelected: Boolean) {
    Symbol(icon = icon,
        contentDescription = null,
        tint = if (isSelected) NxColor.lead() else NxInk.quiet,
        modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(12.dp))
    Text(
        text  = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) NxColor.lead() else NxInk.main,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview
@Composable
private fun NxNavRowContentDarkPreview() = NavRowContents(dark = true)

@Preview
@Composable
private fun NxNavRowContentLightPreview() = NavRowContents(dark = false)

@Composable
private fun NavRowContents(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(Modifier.width(240.dp).background(NxColor.page).padding(8.dp)) {
            for ((icon, label) in listOf(NxIcon.Home to "Home", NxIcon.Inventory2 to "Library", NxIcon.Settings to "Settings")) {
                val selected = label == "Library"
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .clip(Shape.control)
                        .background(if (selected) NxColor.wash(NxColor.lead(), 0.12f) else NxColor.page)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NxNavRowContent(icon, label, isSelected = selected)
                }
            }
        }
    }
}
