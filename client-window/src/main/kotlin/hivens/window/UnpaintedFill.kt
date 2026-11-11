package hivens.window

import java.awt.Color
import java.awt.Container
import java.awt.Window
import javax.swing.RootPaneContainer

/**
 * Gives [color] to the window and to every component inside it, for whatever
 * shows where Skia has not drawn yet.
 *
 * A window that grows shows its new part before a frame that large is ready.
 * What fills it meanwhile is not the window's own background but the Swing
 * panels it holds: the root pane, the content pane and Skia's own layer all
 * paint `Panel.background`, a light grey, so a resize on a dark theme flashed
 * light strips along the edges being dragged.
 */
fun Window.fillUnpainted(color: Color) {
    background = color
    if (this is RootPaneContainer) paintBehind(rootPane, color)
}

private fun paintBehind(c: Container, color: Color) {
    c.background = color
    for (child in c.components) {
        if (child is Container) paintBehind(child, color) else child.background = color
    }
}
