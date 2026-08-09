package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxNavRowContent
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

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
    val authRegistry: AuthProviderRegistry = koinInject()
    val current by ctx.selectedCategory

    // Microsoft / multi-account is deferred: its category shows only when a client
    // id is configured (the device-code provider registers only then). Without one
    // the profile is a single SmartyCraft section -- no dangling Microsoft tab into
    // an empty pane.
    val msaConfigured = remember { authRegistry.hasDeviceCodeProvider() }
    val categories = ProfileCategory.entries.filter {
        it != ProfileCategory.Microsoft || msaConfigured
    }

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
                        if (isSelected) NxTheme.colors.primary.copy(alpha = 0.18f)
                        else NxTheme.colors.background.copy(alpha = 0.0f),
                    )
                    .clickable { ctx.selectedCategory.value = category }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NxNavRowContent(
                    icon = category.icon,
                    label = label,
                    isSelected = isSelected,
                )
            }
        }

        // The face choice belongs beside the list of providers rather than
        // inside one of their sections: it is a statement about which of them
        // wins, so it cannot live in a pane that shows only one at a time.
        Spacer(Modifier.weight(1f))
        FacePicker()
    }
}
