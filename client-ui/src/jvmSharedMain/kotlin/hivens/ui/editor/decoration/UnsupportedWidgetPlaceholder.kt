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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.LocalMonoFamily
import hivens.widget.model.WidgetInstance
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

// Edit-mode stand-in for a widget whose kind is no longer in the registry
// (renamed, removed, or a plugin not loaded this session). Production renders
// nothing -- the instance keeps its props / children on disk untouched -- so
// this is editor-only: it makes the orphan visible and offers a one-tap remove,
// while the schema-bump prune reaps the genuinely dead ones automatically. The
// raw kind id is shown verbatim (a technical identifier, not translatable) so a
// reporter can say exactly which widget went missing.
@Composable
fun UnsupportedWidgetPlaceholder(
    instance: WidgetInstance,
    onRemove: () -> Unit,
) {
    val s = LocalStrings.current
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
                s.editorUnsupportedWidget,
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
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Symbol(NxIcon.Close,
                contentDescription = s.editorDelete,
                tint = NxInk.quiet,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
