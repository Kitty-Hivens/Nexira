package hivens.ui.editor

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

internal actual fun resizePointerIcon(edge: ResizeEdge): PointerIcon = PointerIcon(Cursor(edge.cursor()))

internal actual fun transparentPointerIcon(): PointerIcon {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val cursor = Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "drag-ghost")
    return PointerIcon(cursor)
}

private fun ResizeEdge.cursor(): Int = when (this) {
    ResizeEdge.North     -> Cursor.N_RESIZE_CURSOR
    ResizeEdge.South     -> Cursor.S_RESIZE_CURSOR
    ResizeEdge.West      -> Cursor.W_RESIZE_CURSOR
    ResizeEdge.East      -> Cursor.E_RESIZE_CURSOR
    ResizeEdge.NorthWest -> Cursor.NW_RESIZE_CURSOR
    ResizeEdge.NorthEast -> Cursor.NE_RESIZE_CURSOR
    ResizeEdge.SouthWest -> Cursor.SW_RESIZE_CURSOR
    ResizeEdge.SouthEast -> Cursor.SE_RESIZE_CURSOR
}
