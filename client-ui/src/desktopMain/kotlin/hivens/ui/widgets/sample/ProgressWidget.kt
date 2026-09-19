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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.activity.Activity
import hivens.core.activity.ActivityPhase
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberSource
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ProgressProps(
    // Blank defaults resolve to the localized text at render; a non-blank
    // value is the user's own override (single language, by choice).
    @PropLabel("widget.home.new.progress.title") val title: String = "",
    @PropLabel("widget.home.new.progress.idleText") val idleText: String = "",
)

// Compact background-activity card. Names whatever the launcher is working on
// right now -- an install, a pack update, a batch of content updates -- and
// collapses to a calm "idle" message when there is nothing in flight.
@Widget(
    id = "home.new.progress",
    displayName = "widget.home.new.progress",
    propsClass = ProgressProps::class,
    surface = """{"fill":"base","opacity":0.4,"padding":{"top":12.0}}""",
)
@Composable
fun ProgressWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ProgressProps>()
    val s = LocalStrings.current
    // Bound declaratively to the activity source -- the widget no longer knows
    // which service backs it (the SourceKey is wired in Sources + Main DI).
    val activities by rememberSource(Sources.Activity)
    // The oldest one still running. A card this size can name one thing, and the
    // one that has been waiting longest is the one a reader is wondering about;
    // the activity pill is where the whole list lives.
    val running = activities.firstOrNull { it.phase is ActivityPhase.Running }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text       = p.title.ifBlank { s.widgetProgressTitle },
            style      = MaterialTheme.typography.labelLarge,
            color      = NxTheme.colors.textSecondary,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        if (running != null) {
            InProgressBody(running)
        } else {
            IdleBody(p.idleText.ifBlank { s.widgetProgressIdle })
        }
    }
}

@Composable
private fun InProgressBody(activity: Activity) {
    val phase = activity.phase as? ActivityPhase.Running ?: return
    // Below zero means the size is not known yet, which the bar shows as an
    // indeterminate sweep rather than as nothing having happened.
    val measured = phase.total > 0L
    val fraction = if (measured) {
        (phase.done.toFloat() / phase.total.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier              = Modifier.fillMaxWidth(),
    ) {
        Text(
            text       = activity.title,
            style      = MaterialTheme.typography.bodyMedium,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (measured) {
            Text(
                text  = "${phase.done}/${phase.total}",
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
    }
    phase.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        Spacer(Modifier.height(4.dp))
        Text(
            text     = detail,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(6.dp))
    val barModifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
    if (measured) {
        LinearProgressIndicator(
            progress   = { fraction },
            modifier   = barModifier,
            color      = NxTheme.colors.primary,
            trackColor = NxTheme.colors.outline.copy(alpha = 0.15f),
        )
    } else {
        LinearProgressIndicator(
            modifier   = barModifier,
            color      = NxTheme.colors.primary,
            trackColor = NxTheme.colors.outline.copy(alpha = 0.15f),
        )
    }
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
            color = NxTheme.colors.textSecondary,
        )
    }
}
