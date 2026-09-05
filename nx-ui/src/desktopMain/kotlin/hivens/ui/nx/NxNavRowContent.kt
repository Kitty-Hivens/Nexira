package hivens.ui.nx

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

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
        tint = if (isSelected) NxTheme.colors.primary
               else NxTheme.colors.textSecondary,
        modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(Spacing.s12))
    Text(
        text  = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) NxTheme.colors.primary
                else NxTheme.colors.textPrimary,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
