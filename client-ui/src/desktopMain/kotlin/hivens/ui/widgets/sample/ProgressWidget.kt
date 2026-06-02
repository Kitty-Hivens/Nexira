package hivens.ui.widgets.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.launcher.AutoSyncService
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class ProgressProps(
    @PropLabel("widget.home.new.progress.title") val title: String = "Фоновая активность",
    @PropLabel("widget.home.new.progress.idleText") val idleText: String = "Сейчас ничего не качается.",
)

// Compact background-activity card. Shows AutoSyncService state when
// a sync is in flight; collapses to a calm "idle" message otherwise.
// Polished version of the dashboard's autosync strip, broken out so
// the new home can host it independently.
@Widget(id = "home.new.progress", displayName = "widget.home.new.progress", propsClass = ProgressProps::class)
@Composable
fun ProgressWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ProgressProps>()
    val autoSyncService: AutoSyncService = koinInject()
    val snapshot by autoSyncService.snapshot.collectAsState()
    val overall  = snapshot.overall

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(glassSurfaceAlpha(0.40f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text       = p.title,
            style      = MaterialTheme.typography.labelLarge,
            color      = CelestiaTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        when (val s = overall) {
            is AutoSyncService.OverallState.InProgress -> InProgressBody(s)
            else                                       -> IdleBody(p.idleText)
        }
    }
}

@Composable
private fun InProgressBody(state: AutoSyncService.OverallState.InProgress) {
    val fraction = if (state.totalBytes > 0L) {
        (state.bytesRead.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier              = Modifier.fillMaxWidth(),
    ) {
        Text(
            text       = state.currentServer,
            style      = MaterialTheme.typography.bodyMedium,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text  = "${state.currentIdx}/${state.total}",
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
    if (state.totalBytes > 0L) {
        Spacer(Modifier.height(4.dp))
        Text(
            text  = "${state.bytesRead / 1_048_576} / ${state.totalBytes / 1_048_576} MB",
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.75f),
        )
    }
    Spacer(Modifier.height(6.dp))
    LinearProgressIndicator(
        progress   = { fraction },
        modifier   = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
        color      = CelestiaTheme.colors.primary,
        trackColor = CelestiaTheme.colors.outline.copy(alpha = 0.15f),
    )
}

@Composable
private fun IdleBody(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
    }
}
