package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class QuickLaunchProps(
    @PropLabel("widget.home.new.quicklaunch.buttonLabel") val buttonLabel: String = "",
)

// Quick-launch target = most recently played, falling back to most
// recently installed when nothing has been played. Empty repo elides
// the widget entirely -- HomeNewRecent already shows the install CTA
// in that state and two empty cards would be noisy.
@Widget(id = "home.new.quicklaunch", displayName = "widget.home.new.quicklaunch", propsClass = QuickLaunchProps::class)
@Composable
fun HomeNewQuickLaunch(instance: WidgetInstance) {
    val p = instance.rememberProps<QuickLaunchProps>()
    val s = LocalStrings.current
    val qt = rememberQuickLaunchTarget() ?: return
    val target = qt.target

    val label = if (target.lastPlayedEpochOrZero > 0L) s.homeQuickContinue else s.homeQuickStart

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.45f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = NxTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text       = target.displayName,
                    style      = MaterialTheme.typography.titleMedium,
                    color      = NxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = target.packRef.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = qt.launch,
                enabled = qt.canLaunch,
                shape   = MaterialTheme.shapes.small,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = NxTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) {
                Symbol(qt.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    qt.buttonLabel ?: p.buttonLabel.ifBlank { s.homeQuickButton },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
