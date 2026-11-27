package hivens.ui.editor.decoration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

/**
 * What the editor draws for a widget whose kind is not in the registry: renamed, removed, or brought by a module that
 * is not loaded. Nobody else sees it, and the record keeps its props and children, so the widget comes back as it was
 * when its module does. It stands in the same chrome as any widget, so it is moved and removed the same way. The kind is
 * shown as it is stored, a technical id and not a word to translate, so a report can say which widget went missing.
 */
@Composable
fun UnsupportedWidgetPlaceholder(instance: WidgetInstance) {
    val s = nexiraStrings()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NxColor.status(Status.Error).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .background(NxColor.status(Status.Error).copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(NxIcon.Warning,
            contentDescription = null,
            tint = NxColor.status(Status.Error),
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                s.text("editorUnsupportedWidget"),
                color = NxColor.status(Status.Error),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                instance.kind.value,
                color = NxInk.quiet,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = LocalMonoFamily.current,
            )
        }
    }
}

/**
 * The declaration the editor's chrome is handed for a widget of a [kind] the registry does not have: nothing declared,
 * no slots it could draw, and removable, since nothing this build knows depends on it.
 */
internal data class UnknownWidget(override val kind: WidgetKind) : WidgetDescriptor {
    override val displayName: String get() = kind.value
    override val removable: Boolean get() = true

    @Composable
    override fun Render(instance: WidgetInstance) = Unit
}
