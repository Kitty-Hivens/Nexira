package hivens.ui.editor.props

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxSwitch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.customization.HexField
import hivens.ui.widgets.customization.LabeledSlider
import hivens.widget.model.PropChoice
import hivens.widget.model.PropColor
import hivens.widget.model.PropRange
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

// Renders one editor control for a single prop field, dispatching on the
// serial kind + @SerialInfo annotations. `current` is the effective
// value (default baseline overlaid with the instance's override), so it
// is never null. onChange emits the new value as a JsonElement. INT/FLOAT
// fields without a @PropRange fall through to a text field -- a slider
// needs bounds.
@Composable
internal fun PropFieldRow(
    label: String,
    element: SerialDescriptor,
    annotations: List<Annotation>,
    current: JsonElement,
    onChange: (JsonElement) -> Unit,
) {
    val isColor = annotations.any { it is PropColor }
    val choice  = annotations.filterIsInstance<PropChoice>().firstOrNull()
    val range   = annotations.filterIsInstance<PropRange>().firstOrNull()
    val cur     = current.jsonPrimitive

    when {
        element.kind == SerialKind.ENUM -> {
            val options = (0 until element.elementsCount).map { element.getElementName(it) }
            ChoiceRow(label, options, cur.content) { onChange(JsonPrimitive(it)) }
        }
        choice != null ->
            ChoiceRow(label, choice.options.toList(), cur.content) { onChange(JsonPrimitive(it)) }
        isColor ->
            ColorRow(label, cur.content) { onChange(JsonPrimitive(it)) }
        element.kind == PrimitiveKind.BOOLEAN ->
            BoolRow(label, cur.booleanOrNull ?: false) { onChange(JsonPrimitive(it)) }
        element.kind == PrimitiveKind.INT && range != null ->
            LabeledSlider(
                label         = label,
                value         = (cur.intOrNull ?: range.min.toInt()).toFloat(),
                range         = range.min.toFloat()..range.max.toFloat(),
                format        = "%.0f",
                keyStep       = 1f,
                onValueChange = { onChange(JsonPrimitive(it.roundToInt())) },
            )
        (element.kind == PrimitiveKind.FLOAT || element.kind == PrimitiveKind.DOUBLE) && range != null ->
            LabeledSlider(
                label         = label,
                value         = cur.floatOrNull ?: range.min.toFloat(),
                range         = range.min.toFloat()..range.max.toFloat(),
                format        = "%.2f",
                onValueChange = { onChange(JsonPrimitive(it)) },
            )
        else ->
            StringRow(label, cur.content) { onChange(JsonPrimitive(it)) }
    }
}

@Composable
private fun BoolRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        NxSwitch(
            checked         = value,
            onCheckedChange = onChange,
        )
    }
}

@Composable
private fun ColorRow(label: String, hex: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        HexField(
            initialHex   = hex,
            invalidLabel = "hex",
            onValidHex   = onChange,
            modifier     = Modifier.weight(1f),
        )
    }
}

/** A free-text row. Also the fill control: one field carrying a value or a name. */
@Composable
internal fun StringRow(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(NxTheme.colors.surface.copy(alpha = 0.4f))
                .border(1.dp, NxTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value         = text,
                onValueChange = { text = it; onChange(it) },
                singleLine    = true,
                textStyle     = TextStyle(color = NxTheme.colors.textPrimary, fontSize = 13.sp),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(140.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text     = selected,
                style    = MaterialTheme.typography.bodySmall,
                color    = NxTheme.colors.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(NxTheme.colors.surface.copy(alpha = 0.4f))
                    .border(1.dp, NxTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            NxContextMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    NxMenuItem(
                        label    = opt,
                        selected = opt == selected,
                        onClick  = { onChange(opt); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * A row that opens a group of refinements.
 *
 * Local to the prop panel rather than a design-system primitive: it has one caller,
 * and a disclosure row moves to nx-ui when a second one wants it rather than on the
 * guess that one will.
 */
@Composable
internal fun DisclosureRow(label: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        // Hugs its label and takes a pill. Full width plus a default indication drew
        // the press and focus states as a rectangle spanning the whole panel, which
        // is neither the shape of the control nor the size of it.
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            icon = if (expanded) NxIcon.ExpandLess else NxIcon.ExpandMore,
            contentDescription = null,
            tint = NxTheme.colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.labelMedium,
            color = NxTheme.colors.textSecondary,
        )
    }
}

/** A whole-dp slider for one corner or one side, in the range both share. */
@Composable
internal fun CornerRow(label: String, value: Float, onChange: (Float) -> Unit) {
    LabeledSlider(
        label         = label,
        value         = value,
        range         = 0f..40f,
        format        = "%.0f",
        keyStep       = 1f,
        onValueChange = onChange,
    )
}
