package hivens.ui.editor

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

internal actual fun transparentPointerIcon(): PointerIcon {
    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
    val cursor = Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "drag-ghost")
    return PointerIcon(cursor)
}
