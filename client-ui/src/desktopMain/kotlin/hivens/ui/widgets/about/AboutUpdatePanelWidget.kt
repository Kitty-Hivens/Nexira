package hivens.ui.widgets.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.config.Branding
import hivens.core.data.ReleaseChannel
import hivens.ui.components.GlassCard
import hivens.ui.components.channelColor
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class AboutUpdateProps(
    @PropLabel("widget.about.update.panel.title") val title: String = "",
)

// Update panel. The surface auto-checks in the background, so there is no manual
// "check" button and no "up to date" line: the panel shows the current version
// (channel-coloured) with the "i" that opens the full update manager, and a
// download card only when an update is actually available.
@Widget(id = "about.update.panel", displayName = "widget.about.update.panel", propsClass = AboutUpdateProps::class)
@Composable
fun AboutUpdatePanelWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutUpdateProps>()
    val ctx = LocalAboutContext.current
    val af = LocalAprilFools.current
    val s = LocalStrings.current
    val state by ctx.updateState

    val versionText = remember { "v${Branding.VERSION.removePrefix("v")}" }
    val channel = remember { ReleaseChannel.classify(Branding.VERSION.removePrefix("v")) }
    val accent = channelColor(channel)

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            SectionLabel(p.title.ifBlank { s.aboutSectionUpdates })
            Spacer(Modifier.height(16.dp))

            // Current version. Only the "i" opens the manager -- the tap target
            // is the icon, not the whole row.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon = NxIcon.Info,
                    contentDescription = s.updateManagerOpenHint,
                    tint               = NxTheme.colors.primary,
                    modifier           = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { ctx.showUpdateManager.value = true },
                )
                Spacer(Modifier.width(8.dp))
                Text(s.aboutCurrentVersion, color = NxTheme.colors.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text       = versionText,
                    color      = accent,
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = LocalMonoFamily.current,
                    textAlign  = TextAlign.End,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f),
                )
            }

            // Only an actual available update renders below; up-to-date stays silent.
            (state as? UpdateCheckState.Available)?.let { current ->
                val availChannel = ReleaseChannel.classify(current.update.version.removePrefix("v"))
                val availAccent = if (current.update.isCritical) NxTheme.colors.error else channelColor(availChannel)

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Symbol(NxIcon.NewReleases, null, tint = availAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = current.update.version,
                        color      = availAccent,
                        fontWeight = FontWeight.Bold,
                        fontFamily = LocalMonoFamily.current,
                    )
                }
                Spacer(Modifier.height(12.dp))
                af.ChaosButton(
                    id      = "about_open_update_btn",
                    text    = if (current.update.isCritical) s.updateDownloadNow else s.updateDownload,
                    onClick = { ctx.showUpdateDialog.value = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = if (current.update.isCritical) NxTheme.colors.error else glassSurfaceAlpha(0.55f),
                        contentColor   = NxTheme.colors.textPrimary,
                    ),
                )
            }
        }
    }
}
