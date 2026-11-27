package hivens.ui.editor.props

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hivens.ui.editor.DocumentEditor
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxAlertDialog
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.press.TextButton
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.screens.custom.ScreenIcons
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status
import hivens.pages.api.ScreenId
import hivens.widget.model.CompositionName

/**
 * A screen of the document, as its settings panel sees it: where it is, the name and icon the person gave it, blank
 * for none, what it is called without them ([label]), the icon it wears, and whether somebody made it.
 */
internal data class ScreenTarget(
    val composition: CompositionName,
    val id: ScreenId,
    val title: String,
    val label: String,
    val icon: String,
    val made: Boolean,
)

/**
 * What a screen is called, what it wears on the rail, and, for one somebody made, the way to delete it.
 *
 * Every edit writes at once and goes through the editor's history, so a rename is undone the way a moved widget is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenPropertiesPanel(
    visible: Boolean,
    spec: ScreenTarget?,
    editor: DocumentEditor,
    onDelete: (ScreenTarget) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    var confirmDelete by remember(spec?.id) { mutableStateOf(false) }
    EditorSidePanel(
        visible   = visible && spec != null,
        title     = s.text("screenSettingsTitle"),
        icon      = ScreenIcons.of(spec?.icon.orEmpty()),
        onDismiss = onDismiss,
        modifier  = modifier,
    ) {
        val current = spec ?: return@EditorSidePanel
        // Held here and not read back from the record on every keystroke: the write
        // lands on another thread a frame later, and a field fed from it would put
        // the caret back at the end under somebody typing in the middle. A title that
        // is none of the field's own writes landing was changed under it, by an undo
        // say, and the field takes it.
        var draft by remember(current.id) { mutableStateOf(current.title) }
        val pending = remember(current.id) { ArrayDeque<String>() }
        val rename: (String) -> Unit = {
            draft = it
            pending.addLast(it)
            editor.updateScreen(current.composition, current.id, title = it)
        }
        LaunchedEffect(current.id, current.title) {
            titleLanded(pending, current.title, draft)?.let { draft = it }
        }

        SectionLabel(s.text("screenTitleLabel"))
        NxField(
            value         = draft,
            onValueChange = rename,
            placeholder   = current.label.ifBlank { s.text("screenUntitled") },
            modifier      = Modifier.fillMaxWidth(),
        )
        PuppetField("screen.title", draft, onValueChange = rename)

        Spacer(Modifier.height(4.dp))
        SectionLabel(s.text("screenIconLabel"))
        val chosen = ScreenIcons.of(current.icon)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
        ) {
            ScreenIcons.ALL.forEach { (name, key) ->
                val selected = key == chosen
                val fill = if (selected) NxColor.wash(NxColor.lead(), 0.22f) else NxColor.wash(NxInk.quiet, 0.08f)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(fill)
                        .border(1.dp, if (selected) NxColor.lead() else NxInk.line, RoundedCornerShape(8.dp))
                        .clickable { editor.updateScreen(current.composition, current.id, icon = name) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    OnFill(fill) {
                        Symbol(
                            icon               = key,
                            contentDescription = null,
                            tint               = if (selected) NxColor.lead() else NxInk.quiet,
                            modifier           = Modifier.size(18.dp),
                        )
                    }
                }
                PuppetClick("screen.icon.$name") { editor.updateScreen(current.composition, current.id, icon = name) }
            }
        }

        // Only a screen somebody made: the launcher's own come back with a reset, not a delete.
        if (current.made) {
            Spacer(Modifier.height(12.dp))
            NxButton(
                label    = s.text("screenDelete"),
                onClick  = { confirmDelete = true },
                style    = NxButtonStyle.Destructive,
                icon     = NxIcon.Delete,
                modifier = Modifier.fillMaxWidth(),
            )
            PuppetClick("screen.delete") { confirmDelete = true }
        }
    }

    val doomed = spec
    if (confirmDelete && doomed != null) {
        val delete = {
            confirmDelete = false
            onDelete(doomed)
            onDismiss()
        }
        PuppetClick("screen.delete.confirm") { delete() }
        val container = NxColor.depth(NxColor.floating)
        OnFill(container) {
            NxAlertDialog(
                onDismissRequest  = { confirmDelete = false },
                paneTitle         = s.text("screenDelete"),
                containerColor    = container,
                titleContentColor = NxInk.main,
                textContentColor  = NxInk.quiet,
                title             = { Text(s.text("screenDelete")) },
                text              = {
                    Text(
                        text  = s.text("screenDeleteBody", doomed.title.ifBlank { doomed.label.ifBlank { s.text("screenUntitled") } }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = delete) { Text(s.text("editorDelete"), color = NxColor.status(Status.Error)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(s.text("editorCancel")) }
                },
            )
        }
    }
}

/**
 * What a title field shows once [landed] is the title on record, given the writes it has made that had not landed yet
 * ([pending], oldest first, drained here as they land) and what it shows now. Null where it keeps what it shows: the
 * title is one of its own writes arriving. Anything else was changed under the field and is shown.
 */
internal fun titleLanded(pending: ArrayDeque<String>, landed: String, shown: String): String? {
    val own = pending.indexOf(landed)
    if (own >= 0) {
        repeat(own + 1) { pending.removeFirst() }
        return null
    }
    pending.clear()
    return landed.takeIf { it != shown }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
}
