package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxNavRowContent
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme

/**
 * Vertical category nav for the two-column Settings layout. Renders
 * one row per [SettingsCategory]; the selected one takes the primary
 * fill, inactive rows sit on the section background tint and lift
 * slightly on hover. Click invokes [onSelect].
 *
 * Width is intentionally fixed (200.dp) -- not weight-based -- so
 * category labels don't reflow when the content side resizes.
 */
@Composable
internal fun SettingsCategoryNav(
    current: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Column(
        modifier = modifier
            .width(200.dp)
            .fillMaxHeight()
            .padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            val isSelected = category == current
            PuppetClick("settings.category.${category.name}") { onSelect(category) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (isSelected) NxTheme.colors.primary.copy(alpha = 0.18f)
                        else NxTheme.colors.background.copy(alpha = 0.0f),
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NxNavRowContent(
                    icon = category.icon,
                    label = category.label(s),
                    isSelected = isSelected,
                )
            }
        }
    }
}
