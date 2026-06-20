package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.ui.components.NavItemRowContent
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Vertical category nav for the profile surface. Writes
// `selectedCategory.value` on tap; the surface composable reads it
// to pick which content slot to render. Removing this widget locks
// the user on whichever category was last selected -- reset the
// surface to bring nav back.
@Widget(id = "profile.nav", displayName = "widget.profile.nav")
@Composable
fun ProfileNavWidget(instance: WidgetInstance) {
    val ctx = LocalProfileContext.current
    val s = LocalStrings.current
    val style = LocalStyle.current
    val current by ctx.selectedCategory

    // Both provider sections always show; each owns its signed-in/out state.
    val categories = ProfileCategory.entries

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        categories.forEach { category ->
            val isSelected = category == current
            val label = category.label(s)
            PuppetClick("profile.category.${category.name}") {
                ctx.selectedCategory.value = category
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.cardCorner))
                    .background(
                        if (isSelected) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
                        else CelestiaTheme.colors.background.copy(alpha = 0.0f),
                    )
                    .clickable { ctx.selectedCategory.value = category }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavItemRowContent(
                    icon = category.icon,
                    label = label,
                    isSelected = isSelected,
                )
            }
        }
    }
}
