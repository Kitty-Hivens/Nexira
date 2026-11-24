package hivens.ui.editor

import androidx.compose.ui.input.pointer.PointerIcon

/**
 * A cursor that draws nothing, for the moment a dragged ghost stands in for the pointer. Not the look's to
 * answer: the ghost is the pointer then, whatever the look draws pointers like.
 */
internal expect fun transparentPointerIcon(): PointerIcon
