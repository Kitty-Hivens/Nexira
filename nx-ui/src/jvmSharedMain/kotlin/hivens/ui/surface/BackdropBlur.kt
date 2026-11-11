package hivens.ui.surface

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * A blur of whatever is already painted beneath a surface, drawn as the first thing
 * in its body so the body lands on the blurred copy.
 */
internal fun interface BackdropBlur {
    fun draw(scope: DrawScope)
}

/**
 * The blur for glass of radius [radiusDp], or null when there is nothing to draw: no
 * radius, the user switched surface blur off, or the host has no way to read what is
 * behind a surface.
 */
@Composable
internal expect fun rememberBackdropBlur(radiusDp: Float): BackdropBlur?
