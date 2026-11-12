package hivens.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.modules.widgetLabel
import hivens.widget.api.MisplacedWidgetDecorator
import hivens.widget.api.WidgetDescriptor

/**
 * Drawn in place of a widget that stands where something it reads has no answer,
 * which only a hand-edited layout can do, since the editor will not put it there.
 * It says which widget it is and what it misses, so whoever arranged it can find it
 * and move it rather than face a gap nobody can explain.
 *
 * The ids are shown as they are, the way an unsupported widget shows its kind: they
 * name keys and are not words to translate.
 */
@Composable
fun MisplacedWidgetPlaceholder(descriptor: WidgetDescriptor, missing: List<String>) {
    NxSurface(SurfaceKind.Card, shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Symbol(NxIcon.Warning, contentDescription = null, tint = NxColor.status(Status.Warning), size = 16.dp)
            Column {
                Text(
                    text = widgetLabel(descriptor.kind, descriptor.displayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = NxInk.main,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = missing.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                    fontFamily = LocalMonoFamily.current,
                )
            }
        }
    }
}

/** [MisplacedWidgetPlaceholder] as the kernel's hook takes it. */
val MisplacedWidget: MisplacedWidgetDecorator = { descriptor, _, missing -> MisplacedWidgetPlaceholder(descriptor, missing) }
