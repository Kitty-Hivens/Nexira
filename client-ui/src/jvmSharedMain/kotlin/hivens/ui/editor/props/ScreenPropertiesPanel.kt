package hivens.ui.editor.props

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import hivens.ui.editor.EditModeController
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxField
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.screens.custom.ScreenIcons
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status
import hivens.widget.model.ScreenSpec

/**
 * What a screen somebody made is called, what it wears on the rail, and the way
 * to delete it.
 *
 * Opened from the same settings chip a region's own settings use, because a made
 * screen's settings are the screen's and not any widget's. Every edit writes at
 * once and goes through the editor's history, so a rename is undone the way a
 * moved widget is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenPropertiesPanel(
    visible: Boolean,
    spec: ScreenSpec?,
    controller: EditModeController,
    onDelete: (ScreenSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    var confirmDelete by remember(spec?.id) { mutableStateOf(false) }
    EditorSidePanel(
        visible   = visible && spec != null,
        title     = s.screenSettingsTitle,
        icon      = ScreenIcons.of(spec?.icon.orEmpty()),
        onDismiss = onDismiss,
        modifier  = modifier,
    ) {
        val current = spec ?: return@EditorSidePanel
        // Held here and not read back from the record on every keystroke: the write
        // lands on another thread a frame later, and a field fed from it would put
        // the caret back at the end under somebody typing in the middle.
        var draft by remember(current.id) { mutableStateOf(current.title) }
        val rename: (String) -> Unit = { draft = it; controller.updateScreen(current.id, title = it) }

        SectionLabel(s.screenTitleLabel)
        NxField(
            value         = draft,
            onValueChange = rename,
            placeholder   = s.screenUntitled,
            modifier      = Modifier.fillMaxWidth(),
        )
        PuppetField("screen.title", draft, onValueChange = rename)

        Spacer(Modifier.height(4.dp))
        SectionLabel(s.screenIconLabel)
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
                        .clickable { controller.updateScreen(current.id, icon = name) }
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    OnFill(fill) {
                        Symbol(
                            icon               = key,
                            contentDescription = null,
                            tint               = if (selected) NxColor.lead(text = true) else NxInk.quiet,
                            modifier           = Modifier.size(18.dp),
                        )
                    }
                }
                PuppetClick("screen.icon.$name") { controller.updateScreen(current.id, icon = name) }
            }
        }

        Spacer(Modifier.height(12.dp))
        NxButton(
            label    = s.screenDelete,
            onClick  = { confirmDelete = true },
            style    = NxButtonStyle.Destructive,
            icon     = NxIcon.Delete,
            modifier = Modifier.fillMaxWidth(),
        )
        PuppetClick("screen.delete") { confirmDelete = true }
    }

    val doomed = spec
    if (confirmDelete && doomed != null) {
        val delete = {
            confirmDelete = false
            onDelete(doomed)
            onDismiss()
        }
        PuppetClick("screen.delete.confirm") { delete() }
        val container = NxTheme.colours.step(NxTheme.colours.topStep)
        OnFill(container) {
            AlertDialog(
                onDismissRequest  = { confirmDelete = false },
                containerColor    = container,
                titleContentColor = NxInk.main,
                textContentColor  = NxInk.quiet,
                title             = { Text(s.screenDelete) },
                text              = {
                    Text(
                        text  = s.screenDeleteBody(doomed.title.ifBlank { s.screenUntitled }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = delete) { Text(s.editorDelete, color = NxColor.status(Status.Error, text = true)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text(s.editorCancel) }
                },
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
}
