package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.components.GlassCard
import hivens.ui.i18n.LocalStrings
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class AboutLinksProps(
    @PropLabel("Заголовок") val title: String = "",
)

// External links: repo + issue tracker + releases. URLs are stable
// upstream so they stay inline rather than threading through
// configuration. Per-button atomization would be the same shape as
// system rows -- one card per button or floating buttons. Single
// card matches the legacy visual.
@Widget(id = "about.links.card", displayName = "Ссылки", propsClass = AboutLinksProps::class)
@Composable
fun AboutLinksCardWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutLinksProps>()
    val s = LocalStrings.current

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            SectionLabel(p.title.ifBlank { s.aboutSectionLinks })
            Spacer(Modifier.height(12.dp))
            LinkButton(s.aboutLinkGithub,    "https://github.com/Kitty-Hivens/Nexira",         Icons.Default.Code)
            Spacer(Modifier.height(8.dp))
            LinkButton(s.aboutLinkBugReport, "https://github.com/Kitty-Hivens/Nexira/issues",  Icons.Default.BugReport)
            Spacer(Modifier.height(8.dp))
            LinkButton(s.aboutLinkReleases,  "https://github.com/Kitty-Hivens/Nexira/releases", Icons.Default.Download)
        }
    }
}
