package hivens.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.theme.CelestiaTheme

/**
 * Leading icon + label for one vertical-nav entry, selected styling
 * applied. The caller owns the surrounding clickable Row (background,
 * shape, click target) so each nav can keep its own selection wiring;
 * this is only the shared row body.
 */
@Composable
internal fun NavItemRowContent(icon: ImageVector, label: String, isSelected: Boolean) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isSelected) CelestiaTheme.colors.primary
               else CelestiaTheme.colors.textSecondary,
        modifier = Modifier.size(20.dp),
    )
    Spacer(Modifier.width(12.dp))
    Text(
        text  = label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isSelected) CelestiaTheme.colors.primary
                else CelestiaTheme.colors.textPrimary,
        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
    )
}
