package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.nx.workProgress
import hivens.ui.nx.PlayTone
import hivens.ui.icons.NxIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.components.LaunchControl
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.familyForText
import hivens.ui.widgets.home.new.rememberQuickLaunchTarget
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class LaunchButtonProps(
    // Blank falls back to the localized ready label.
    @PropLabel("widget.home.new.launchbutton.label") val label: String = "",
)

// Big "Continue last" launch tile. Decoupled from the QuickLaunch
// card -- this one is a single full-width tap target with a gradient
// background, no surrounding labels or metadata. Designed to feel
// like a console "press to play" affordance.
@Widget(id = "home.new.launchbutton", displayName = "widget.home.new.launchbutton", propsClass = LaunchButtonProps::class)
@Composable
fun LaunchButtonWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<LaunchButtonProps>()
    val s = LocalStrings.current
    val quickLaunch = rememberQuickLaunchTarget(p.label.ifBlank { s.launchTileReady }) ?: return
    LaunchTile(quickLaunch.control, quickLaunch.target.displayName)
}

/**
 * The tile itself, apart from choosing its pack. Split out so a render probe can draw
 * every state without a launcher behind it.
 */
@Composable
internal fun LaunchTile(control: LaunchControl, packName: String) {
    // Lit whenever the tile has something to do: play, stop the running game, or
    // take a signed-out player to sign in. It used to go grey for every reason at
    // once and say "can't play yet", which answered none of them.
    val ready = control.actionable
    val colors = NxTheme.colors
    val ink = if (ready) colors.onPrimary else colors.textPrimary
    val quiet = if (ready) colors.onPrimary.copy(alpha = 0.85f) else colors.textSecondary

    val gradient = Brush.linearGradient(
        colors = listOf(
            colors.primary,
            colors.primary.copy(alpha = 0.78f),
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.small)
            .then(if (ready) Modifier.background(gradient) else Modifier.background(colors.surfaceVariant))
            // The tile is its own progress bar while it waits, the same language the
            // Play plate speaks: the work fills the tile rather than a bar beside it.
            .then(if (control.tone == PlayTone.Waiting) Modifier.workProgress(control.progress, colors.primary.copy(alpha = 0.22f)) else Modifier)
            .clickable(enabled = ready, onClick = control.onClick)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(ink.copy(alpha = if (ready) 0.18f else 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(icon = control.icon,
                    contentDescription = null,
                    tint               = if (control.tone == PlayTone.Problem) colors.warnAccent else ink,
                    fill               = if (control.icon == NxIcon.PlayArrow || control.icon == NxIcon.Stop) 1f else 0f,
                    modifier           = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = control.label,
                    style      = MaterialTheme.typography.titleLarge,
                    color      = if (control.tone == PlayTone.Unavailable) colors.textSecondary else ink,
                    fontWeight = FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                )
                // The share rides the pack's line rather than the title's: beside the
                // title it cut the longer states short ("updating mods" lost its noun).
                val share = control.progress?.takeIf { control.tone == PlayTone.Waiting }
                    ?.let { " \u00b7 ${(it * 100).toInt()}%" }.orEmpty()
                Text(
                    text  = packName + share,
                    fontFamily = familyForText(packName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = quiet,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
