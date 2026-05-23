package hivens.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Vertical category nav for the two-column Profile layout. Mirrors
 * [hivens.ui.screens.settings.SettingsCategoryNav] in shape and
 * behaviour -- fixed 200dp width, fillMaxWidth click area, primary
 * fill on the active row.
 */
@Composable
internal fun ProfileCategoryNav(
    current: ProfileCategory,
    onSelect: (ProfileCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    Column(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ProfileCategory.entries.forEach { category ->
            val isSelected = category == current
            PuppetClick("profile.category.${category.name}") { onSelect(category) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.cardCorner))
                    .background(
                        if (isSelected) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
                        else CelestiaTheme.colors.background.copy(alpha = 0.0f),
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = if (isSelected) CelestiaTheme.colors.primary
                           else CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text  = category.label(s),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) CelestiaTheme.colors.primary
                            else CelestiaTheme.colors.textPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
