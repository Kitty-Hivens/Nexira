package hivens.ui.editor.decoration

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.nexiraStrings

/**
 * What an empty slot holds while somebody is arranging: nothing that takes room, only a small square for a slot given
 * no room at all, so there is something to drop onto. The outline around the room the slot has is the slot chrome's.
 *
 * It used to stand eighty points tall in every empty slot, which with every slot of the frame arrangeable would have
 * pushed the whole window about the moment the editor opened.
 */
@Composable
fun EmptySlotPlaceholder() {
    val s = nexiraStrings()
    Box(
        Modifier
            .sizeIn(minWidth = MIN_TARGET, minHeight = MIN_TARGET)
            .semantics { contentDescription = s.text("editorDragWidgetHere") },
    )
}

/** The least an empty slot measures while arranging, enough to drop onto. */
private val MIN_TARGET = 24.dp
