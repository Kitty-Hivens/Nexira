package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxCard
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class AboutLinksProps(
    @PropLabel("widget.about.links.card.title") val title: String = "",
)

// External links: repo + issue tracker + releases. URLs are stable
// upstream so they stay inline rather than threading through
// configuration. Per-button atomization would be the same shape as
// system rows -- one card per button or floating buttons. Single
// card matches the legacy visual.
@Widget(id = "about.links.card", displayName = "widget.about.links.card", propsClass = AboutLinksProps::class)
@Composable
fun AboutLinksCardWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutLinksProps>()
    val s = LocalStrings.current

    NxCard(Modifier.fillMaxWidth()) {
        // No inner scroll: the surface already scrolls the right slot in narrow
        // layouts, and nesting a second scroll crashes (unbounded height).
        Column(Modifier.padding(20.dp)) {
            SectionLabel(p.title.ifBlank { s.aboutSectionLinks })
            Spacer(Modifier.height(12.dp))
            LinkButton(s.aboutLinkGithub,    "https://github.com/Kitty-Hivens/Nexira",         NxIcon.Code)
            Spacer(Modifier.height(8.dp))
            LinkButton(s.aboutLinkBugReport, "https://github.com/Kitty-Hivens/Nexira/issues",  NxIcon.BugReport)
            Spacer(Modifier.height(8.dp))
            LinkButton(s.aboutLinkReleases,  "https://github.com/Kitty-Hivens/Nexira/releases", NxIcon.Download)
        }
    }
}
