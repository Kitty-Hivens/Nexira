package hivens.ui.editor

import androidx.compose.ui.input.pointer.PointerIcon

/** The system cursor that names this edge while the pointer is over its handle. */
internal expect fun resizePointerIcon(edge: ResizeEdge): PointerIcon

/** A cursor that draws nothing, for the moment a dragged ghost stands in for the pointer. */
internal expect fun transparentPointerIcon(): PointerIcon
