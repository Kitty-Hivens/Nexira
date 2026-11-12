package hivens.ui.widgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.widgets.modules.widgetLabel
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.MisplacedWidgetDecorator
import hivens.widget.api.ProvideSamples
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.hasSamples
import hivens.widget.api.resolveSurface
import hivens.widget.model.WidgetInstance

/**
 * What stands in for a widget placed where something it reads has no answer, which
 * only a hand-edited layout or a moved page can do, since the editor will not put it
 * there.
 *
 * Outside the editor, nothing at all: the samples a widget can be drawn on are not
 * the person's data, and drawn for use they would be taken for it. In the editor, the
 * widget itself on the samples its keys declare, dimmed and marked, so whoever is
 * arranging sees what it is and that its data is not here. A widget needing a key
 * with no sample gets [MisplacedWidgetPlaceholder] instead.
 */
val MisplacedWidget: MisplacedWidgetDecorator = { descriptor, instance, missing -> Misplaced(descriptor, instance, missing) }

@Composable
private fun Misplaced(descriptor: WidgetDescriptor, instance: WidgetInstance, missing: List<String>) {
    if (LocalEditMode.current !is EditModeState.On) return
    if (LocalScope.current?.hasSamples(missing) == true) {
        SampledWidget(descriptor, instance)
    } else {
        MisplacedWidgetPlaceholder(descriptor, missing)
    }
}

/** The widget drawn on samples, dimmed, under a mark saying its own data is not here. */
@Composable
fun SampledWidget(descriptor: WidgetDescriptor, instance: WidgetInstance) {
    Box {
        Box(Modifier.alpha(SAMPLE_ALPHA)) {
            ProvideSamples(descriptor.needs) {
                val surface = descriptor.resolveSurface(instance)
                if (surface == null) {
                    descriptor.Render(instance)
                } else {
                    LocalWidgetSurfaceRenderer.current(surface) { descriptor.Render(instance) }
                }
            }
        }
        SampleMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}

@Composable
private fun SampleMark(modifier: Modifier) {
    NxSurface(SurfaceKind.Card, modifier, shape = MaterialTheme.shapes.small) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Symbol(NxIcon.VisibilityOff, contentDescription = null, tint = NxInk.quiet, size = 12.dp)
            Text(LocalStrings.current.editorNotAvailableHere, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
        }
    }
}

/** How strongly a widget on samples is dimmed: present enough to recognise, faint enough not to pass for live. */
private const val SAMPLE_ALPHA = 0.45f

/**
 * Drawn in the editor in place of a widget needing a key that has no sample, so it
 * cannot be shown at all. It says which widget it is and what it misses, so whoever
 * arranged it can find it and move it rather than face a gap nobody can explain.
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
