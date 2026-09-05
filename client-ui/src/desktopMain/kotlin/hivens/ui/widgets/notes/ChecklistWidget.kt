package hivens.ui.widgets.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.AdaptiveWidget
import hivens.ui.widgets.scaled
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberWidgetState
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ChecklistProps(
    @PropLabel("widget.checklist.title") val title: String = "",
    @PropLabel("widget.checklist.hideCompleted") val hideCompleted: Boolean = false,
)

@Serializable
data class ChecklistItem(val id: Int, val text: String, val done: Boolean = false)

// Per-instance persisted state. nextId is a monotonic counter so each item gets a
// stable key (index keys glitch Compose on delete); kept in state so it survives
// restart and never collides with a deleted item's id.
@Serializable
data class ChecklistState(val items: List<ChecklistItem> = emptyList(), val nextId: Int = 1)

/**
 * A todo list: the second widget on the per-instance state primitive, and the one
 * that proves a COLLECTION lives in runtime state (something the scalar prop system
 * cannot express). Add / toggle / delete mutate [ChecklistState]; the title +
 * hide-completed are editor [ChecklistProps]. Two instances keep independent lists
 * across restarts.
 */
@Widget(
    id = "checklist",
    displayName = "widget.checklist",
    propsClass = ChecklistProps::class,
    surface = """{"fill":"base","opacity":0.55}""",
)
@Composable
fun ChecklistWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ChecklistProps>()
    val strings = LocalStrings.current
    val palette = NxTheme.colors
    var state by instance.rememberWidgetState { ChecklistState() }

    fun add(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        state = state.copy(items = state.items + ChecklistItem(state.nextId, t), nextId = state.nextId + 1)
    }
    fun toggle(id: Int) {
        state = state.copy(items = state.items.map { if (it.id == id) it.copy(done = !it.done) else it })
    }
    fun delete(id: Int) {
        state = state.copy(items = state.items.filterNot { it.id == id })
    }

    val shown = if (p.hideCompleted) state.items.filterNot { it.done } else state.items
    val doneCount = state.items.count { it.done }

    AdaptiveWidget(referenceWidth = 240.dp, referenceHeight = 220.dp) { scale ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp * scale),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text       = p.title.ifBlank { strings.widgetLabel("widget.checklist") },
                    style      = MaterialTheme.typography.labelLarge.scaled(scale),
                    color      = palette.textSecondary,
                    fontWeight = FontWeight.Medium,
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp * scale))
                Text(
                    text  = "$doneCount/${state.items.size}",
                    style = MaterialTheme.typography.labelSmall.scaled(scale),
                    color = palette.textSecondary.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(8.dp * scale))

            Column(
                modifier            = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp * scale),
            ) {
                if (shown.isEmpty()) {
                    Text(
                        text     = strings.widgetLabel("widget.checklist.empty"),
                        style    = MaterialTheme.typography.bodySmall.scaled(scale),
                        color    = palette.textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp * scale),
                    )
                } else {
                    shown.forEach { item ->
                        ChecklistRow(item, scale, onToggle = { toggle(item.id) }, onDelete = { delete(item.id) })
                    }
                }
            }

            Spacer(Modifier.height(8.dp * scale))
            AddRow(scale) { add(it) }
        }
    }
}

@Composable
private fun ChecklistRow(item: ChecklistItem, scale: Float, onToggle: () -> Unit, onDelete: () -> Unit) {
    val palette = NxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.fillMaxWidth().padding(vertical = 3.dp * scale),
    ) {
        Symbol(icon = if (item.done) NxIcon.CheckBox else NxIcon.CheckBoxOutlineBlank,
            contentDescription = null,
            tint               = if (item.done) palette.primary else palette.textSecondary,
            modifier           = Modifier.size(20.dp * scale).clip(RoundedCornerShape(4.dp)).clickable(onClick = onToggle),
        )
        Spacer(Modifier.width(10.dp * scale))
        Text(
            text       = item.text,
            style      = MaterialTheme.typography.bodyMedium.scaled(scale),
            color      = if (item.done) palette.textSecondary.copy(alpha = 0.6f) else palette.textPrimary,
            textDecoration = if (item.done) TextDecoration.LineThrough else null,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp * scale))
        Symbol(icon = NxIcon.Close,
            contentDescription = null,
            tint               = palette.textSecondary.copy(alpha = 0.5f),
            modifier           = Modifier.size(16.dp * scale).clip(RoundedCornerShape(4.dp)).clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun AddRow(scale: Float, onAdd: (String) -> Unit) {
    val strings = LocalStrings.current
    val palette = NxTheme.colors
    var draft by remember { mutableStateOf("") }
    fun commit() {
        onAdd(draft)
        draft = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BasicTextField(
            value           = draft,
            onValueChange   = { draft = it },
            singleLine      = true,
            textStyle       = MaterialTheme.typography.bodyMedium.scaled(scale).copy(color = palette.textPrimary),
            cursorBrush     = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier        = Modifier.weight(1f),
            decorationBox   = { inner ->
                if (draft.isEmpty()) {
                    Text(
                        text  = strings.widgetLabel("widget.checklist.add"),
                        style = MaterialTheme.typography.bodyMedium.scaled(scale),
                        color = palette.textSecondary.copy(alpha = 0.5f),
                    )
                }
                inner()
            },
        )
        Spacer(Modifier.width(6.dp * scale))
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier
                .size(24.dp * scale)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = { commit() }),
        ) {
            Symbol(icon = NxIcon.Add,
                contentDescription = null,
                tint               = palette.primary,
                modifier           = Modifier.size(18.dp * scale),
            )
        }
    }
}
