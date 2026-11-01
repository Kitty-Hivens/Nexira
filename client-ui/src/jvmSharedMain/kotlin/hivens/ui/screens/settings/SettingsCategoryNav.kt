package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import hivens.ui.theme.NxInk
import hivens.ui.icons.Symbol
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import hivens.ui.theme.NxColor

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
    /** Takes the width it is given instead of a fixed column, for a window with no room beside it. */
    fill: Boolean = false,
) {
    val s = LocalStrings.current
    Column(
        modifier = modifier
            .then(if (fill) Modifier else Modifier.width(200.dp).padding(end = 12.dp))
            .fillMaxHeight(),
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
                        if (isSelected) NxColor.lead().copy(alpha = 0.18f)
                        else NxColor.page.copy(alpha = 0.0f),
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

/**
 * The categories as a row of pills along the top, for a window with no room for the column beside
 * the page. Scrolls sideways when they do not all fit, and the selected one keeps the accent wash
 * the column gives its row.
 */
@Composable
internal fun SettingsCategoryChips(
    current: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsCategory.entries.forEach { category ->
            val isSelected = category == current
            PuppetClick("settings.category.${category.name}") { onSelect(category) }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) NxColor.lead().copy(alpha = 0.22f)
                        else NxColor.wash(NxInk.quiet, 0.10f),
                    )
                    .clickable { onSelect(category) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Symbol(
                    category.icon,
                    contentDescription = null,
                    tint = if (isSelected) NxColor.lead(text = true) else NxInk.quiet,
                    size = 18.dp,
                )
                Text(
                    category.label(s),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) NxColor.lead(text = true) else NxInk.main,
                    maxLines = 1,
                )
            }
        }
    }
}
