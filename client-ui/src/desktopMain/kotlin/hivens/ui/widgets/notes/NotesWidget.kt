package hivens.ui.widgets.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.AdaptiveWidget
import hivens.ui.widgets.scaled
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberWidgetState
import hivens.widget.api.widgetStateRefused
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
@Widget(
    id = "notes.scratch",
    minWidth = 88, minHeight = 72,
    prefWidth = 220, prefHeight = 180,
    maxWidth = 880, maxHeight = 720,
    displayName = "widget.notes.scratch",
    propsClass = NotesProps::class,
    surface = """{"fill":"panel"}""",
)
@Composable
fun NotesWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<NotesProps>()
    val strings = LocalStrings.current
    var notes by instance.rememberWidgetState { NotesState() }
    val tooLong = instance.widgetStateRefused()

    AdaptiveWidget { scale ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp * scale),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text       = p.title.ifBlank { strings.widgetLabel("widget.notes.scratch") },
                    style      = MaterialTheme.typography.labelLarge.scaled(scale),
                    color      = NxInk.quiet,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp * scale))
                // The count turns into the reason while the note is past what the
                // store keeps, since what is on screen is then not what comes back.
                Text(
                    text  = if (tooLong) strings.widgetStateTooLong else notes.body.length.toString(),
                    style = MaterialTheme.typography.labelSmall.scaled(scale),
                    color = if (tooLong) NxColor.status(Status.Error, text = true) else NxInk.quiet,
                )
            }

            Spacer(Modifier.height(8.dp * scale))

            BasicTextField(
                value         = notes.body,
                onValueChange = { notes = notes.copy(body = it) },
                textStyle     = MaterialTheme.typography.bodyMedium.scaled(scale).copy(color = NxInk.main),
                cursorBrush   = SolidColor(NxColor.lead()),
                modifier      = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                decorationBox = { inner ->
                    if (notes.body.isEmpty()) {
                        Text(
                            text  = strings.widgetLabel("widget.notes.scratch.placeholder"),
                            style = MaterialTheme.typography.bodyMedium.scaled(scale),
                            color = NxInk.quiet,
                        )
                    }
                    inner()
                },
            )
        }
    }
}
