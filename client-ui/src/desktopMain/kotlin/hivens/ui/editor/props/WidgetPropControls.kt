package hivens.ui.editor.props

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxColorField
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSelect
import hivens.ui.nx.NxSwitch
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import hivens.ui.widgets.customization.LabeledSlider
import hivens.ui.widgets.customization.panelLabelWidth
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
        // A number with no range still has to stay a number. Without this it fell
        // through to the free-text row below, which writes whatever was typed: a
        // word went into an integer field, the props then failed to decode, and
        // the widget came back at its defaults with nothing saying why.
        element.kind == PrimitiveKind.INT ->
            NumberRow(label, cur.content, decimals = false) { onChange(JsonPrimitive(it.toLong())) }
        element.kind == PrimitiveKind.FLOAT || element.kind == PrimitiveKind.DOUBLE ->
            NumberRow(label, cur.content, decimals = true) { onChange(JsonPrimitive(it)) }
        else ->
            StringRow(label, cur.content) { onChange(JsonPrimitive(it)) }
    }
}

/**
 * One row of the panel: the library's row, with the label column every other row
 * in here measures the same way.
 *
 * Local and one line, because saying `NxRow(compact = true, labelWidth = ...)` at
 * nine call sites is how the three left edges this replaces came about in the
 * first place.
 */
@Composable
private fun PanelRow(label: String, control: @Composable () -> Unit) {
    NxRow(title = label, compact = true, labelWidth = panelLabelWidth, trailing = control)
}

/**
 * The panel's text input: the library's sunken plane with a field on it.
 *
 * Not [hivens.ui.nx.NxField], for one reason and it is worth writing down. These
 * rows re-seed from the record only while they do NOT have focus, because the
 * write is debounced and a value coming back between keystrokes replaces what is
 * half typed. That guard needs the focus of the FIELD, and NxField's modifier
 * lands on the plane around it. The plane is what was hand-rolled here and is
 * what moves to the library. The guard stays where it can see what it guards.
 */
@Composable
private fun PanelField(
    text: String,
    onValueChange: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
) {
    NxSurface(
        level  = NxSurfaceLevel.Sunken,
        blurDp = 0f,
        shape  = MaterialTheme.shapes.small,
    ) {
        BasicTextField(
            value         = text,
            onValueChange = onValueChange,
            singleLine    = true,
            textStyle     = MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary),
            cursorBrush   = SolidColor(NxTheme.colors.primary),
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s10, vertical = Spacing.s8)
                .onFocusChanged { onFocus(it.isFocused) },
        )
    }
}

/**
 * A field that only takes a number.
 *
 * Refuses the keystroke rather than accepting it and complaining afterwards: a
 * character that cannot be part of a number never reaches the text, so there is
 * no invalid state to show, explain or recover from. An empty field and a lone
 * minus sign are allowed while typing, because both are on the way to a number,
 * and neither is reported until it is one.
 */
@Composable
private fun NumberRow(
    label: String,
    value: String,
    decimals: Boolean,
    onChange: (Double) -> Unit,
) {
    // Re-seeding from the record while the field has focus overwrites what is
    // being typed. The write is debounced, so the value that comes back lands
    // between keystrokes and the caret jumps to the end of a number nobody
    // finished. The record wins only when this field is not the one being
    // edited, which is the arrangement the pack settings window arrived at for
    // the same reason.
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    PanelRow(label) {
        PanelField(
            text          = text,
            onValueChange = { typed ->
                if (!isNumeric(typed, decimals)) return@PanelField
                text = typed
                typed.toDoubleOrNull()?.let(onChange)
            },
            onFocus       = { focused = it },
        )
    }
}

/**
 * Whether [text] is a number or on its way to being one.
 *
 * Pure, and separate from the field because it is the whole of the rule: what a
 * keystroke filter lets through is easy to get wrong in the direction that traps
 * somebody mid-edit, and every case worth arguing about is a one-line assertion.
 */
internal fun isNumeric(text: String, decimals: Boolean): Boolean {
    if (text.isEmpty() || text == "-") return true
    val body = text.removePrefix("-")
    if (body.isEmpty()) return false
    // ASCII digits rather than Char.isDigit, which is Unicode-aware and answers
    // true for an Arabic-Indic three that toDoubleOrNull will not parse. The
    // filter has to agree with the parser, or a character is accepted into the
    // field and can never resolve into a value.
    return if (decimals) {
        body.count { it == '.' } <= 1 && body.all { it in '0'..'9' || it == '.' }
    } else {
        body.all { it in '0'..'9' }
    }
}

@Composable
private fun BoolRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    PanelRow(label) {
        NxSwitch(checked = value, onCheckedChange = onChange)
    }
}

/**
 * One colour-valued property.
 *
 * The library's colour input rather than the panel's own copy, and the difference
 * is a bug rather than a look. The copy gated its change on a non-blank value, so
 * emptying the field emitted nothing and the stored hex stayed: the prop's own
 * documentation calls the empty string "fall back to the theme", and that value
 * was unreachable from the panel. The only way back was Reset to default, which
 * also wipes every other prop and the widget's plane. Blank reaches the record
 * here, because the library's field reports it.
 */
@Composable
private fun ColorRow(label: String, hex: String, onChange: (String) -> Unit) {
    PanelRow(label) {
        NxColorField(
            hex           = hex,
            onValueChange = { onChange(it.orEmpty()) },
        )
    }
}

/** A free-text row. Also the fill control: one field carrying a value or a name. */
@Composable
internal fun StringRow(label: String, value: String, onChange: (String) -> Unit) {
    // Re-seeding from the record while the field has focus overwrites what is
    // being typed. The write is debounced, so the value that comes back lands
    // between keystrokes and the caret jumps to the end of a number nobody
    // finished. The record wins only when this field is not the one being
    // edited, which is the arrangement the pack settings window arrived at for
    // the same reason.
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value, focused) { if (!focused) text = value }
    PanelRow(label) {
        PanelField(
            text          = text,
            onValueChange = { text = it; onChange(it) },
            onFocus       = { focused = it },
        )
    }
}

/**
 * One enum-valued property.
 *
 * The plate this used to draw was a rectangle with a border and no caret, which is
 * the shape of a disabled text field rather than of a choice: the only way to find
 * out it opened anything was to click it. [NxSelect] is the control that shape was
 * imitating.
 */
@Composable
private fun ChoiceRow(label: String, options: List<String>, selected: String, onChange: (String) -> Unit) {
    PanelRow(label) {
        NxSelect(
            options  = options,
            selected = selected,
            onSelect = onChange,
            label    = { it },
            modifier = Modifier.fillMaxWidth(),
        )
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
