package hivens.ui.widgets.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.widgets.AdaptiveWidget
import hivens.ui.widgets.scaled
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberWidgetState
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class NotesProps(
    // Editor-set heading. Blank shows the widget's display name instead.
    @PropLabel("widget.notes.scratch.title") val title: String = "",
)

// Runtime, per-instance, persisted (NOT a prop): the editor never sees it, the
// widget owns it, and two instances keep independent bodies across restarts.
@Serializable
data class NotesState(
    val body: String = "",
)

/**
 * Scratchpad: the first widget to own mutable, persisted per-instance state via
 * [rememberWidgetState]. Proves the state primitive end to end -- type, it persists;
 * two instances stay independent; survives restart -- and that editor props
 * ([rememberProps], the title) and runtime state (the body) coexist and are distinct.
 */
@Widget(id = "notes.scratch", displayName = "widget.notes.scratch", propsClass = NotesProps::class)
@Composable
fun NotesWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<NotesProps>()
    val strings = LocalStrings.current
    val palette = NxTheme.colors
    var notes by instance.rememberWidgetState { NotesState() }

    AdaptiveWidget(referenceWidth = 220.dp, referenceHeight = 180.dp) { scale ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(LocalStyle.current.cardCorner * scale))
                .background(glassSurfaceAlpha(0.55f))
                .padding(14.dp * scale),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text       = p.title.ifBlank { strings.widgetLabel("widget.notes.scratch") },
                    style      = MaterialTheme.typography.labelLarge.scaled(scale),
                    color      = palette.textSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp * scale))
                Text(
                    text  = notes.body.length.toString(),
                    style = MaterialTheme.typography.labelSmall.scaled(scale),
                    color = palette.textSecondary.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(8.dp * scale))

            BasicTextField(
                value         = notes.body,
                onValueChange = { notes = notes.copy(body = it) },
                textStyle     = MaterialTheme.typography.bodyMedium.scaled(scale).copy(color = palette.textPrimary),
                cursorBrush   = SolidColor(palette.primary),
                modifier      = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                decorationBox = { inner ->
                    if (notes.body.isEmpty()) {
                        Text(
                            text  = strings.widgetLabel("widget.notes.scratch.placeholder"),
                            style = MaterialTheme.typography.bodyMedium.scaled(scale),
                            color = palette.textSecondary.copy(alpha = 0.5f),
                        )
                    }
                    inner()
                },
            )
        }
    }
}
