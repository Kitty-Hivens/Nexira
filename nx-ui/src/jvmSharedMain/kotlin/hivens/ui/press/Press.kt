package hivens.ui.press

import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import hivens.ui.cursor.CursorRole
import hivens.ui.cursor.cursor
import androidx.compose.foundation.clickable as foundationClickable
import androidx.compose.foundation.combinedClickable as foundationCombinedClickable
import androidx.compose.foundation.selection.selectable as foundationSelectable
import androidx.compose.foundation.selection.toggleable as foundationToggleable

// Compose's press modifiers under the pointer the look has for a press. Compose leaves the pointer to
// whoever writes the modifier, so a press written with its own has none, and one look could never put a
// hand over every button. These are called the same and take the same, and the project's scan refuses
// Compose's own anywhere else.

/** The pointer the look has for something that is pressed, [enabled] or not. */
fun Modifier.pressCursor(enabled: Boolean = true): Modifier = cursor(if (enabled) CursorRole.Pressable else CursorRole.Disabled)

/** Compose's clickable, with the look's pointer. */
fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled).foundationClickable(enabled, onClickLabel, role, interactionSource, onClick)

/** Compose's clickable with an indication of its own, with the look's pointer. */
fun Modifier.clickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled).foundationClickable(interactionSource, indication, enabled, onClickLabel, role, onClick)

/** Compose's combinedClickable, with the look's pointer. */
fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    hapticFeedbackEnabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled)
    .foundationCombinedClickable(enabled, onClickLabel, role, onLongClickLabel, onLongClick, onDoubleClick, hapticFeedbackEnabled, interactionSource, onClick)

/** Compose's combinedClickable with an indication of its own, with the look's pointer. */
fun Modifier.combinedClickable(
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    hapticFeedbackEnabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled)
    .foundationCombinedClickable(interactionSource, indication, enabled, onClickLabel, role, onLongClickLabel, onLongClick, onDoubleClick, hapticFeedbackEnabled, onClick)

/** Compose's selectable, with the look's pointer. */
fun Modifier.selectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled).foundationSelectable(selected, enabled, role, interactionSource, onClick)

/** Compose's selectable with an indication of its own, with the look's pointer. */
fun Modifier.selectable(
    selected: Boolean,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = pressCursor(enabled).foundationSelectable(selected, interactionSource, indication, enabled, role, onClick)

/** Compose's toggleable, with the look's pointer. */
fun Modifier.toggleable(
    value: Boolean,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = pressCursor(enabled).foundationToggleable(value, enabled, role, interactionSource, onValueChange)

/** Compose's toggleable with an indication of its own, with the look's pointer. */
fun Modifier.toggleable(
    value: Boolean,
    interactionSource: MutableInteractionSource?,
    indication: Indication?,
    enabled: Boolean = true,
    role: Role? = null,
    onValueChange: (Boolean) -> Unit,
): Modifier = pressCursor(enabled).foundationToggleable(value, interactionSource, indication, enabled, role, onValueChange)
