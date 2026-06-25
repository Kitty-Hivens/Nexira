package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * A bevel edge derived from the body's own luminance: lighten a dark body, darken a
 * light one. It reads as a beveled edge of the same material -- not a drawn frame in
 * a foreign color, the distinction the surface hairline depends on. The result is
 * opaque, so unlike a low-alpha outline it never blends with whatever sits behind.
 *
 * @param body  the resolved body color of this surface
 * @param delta lerp fraction toward white (dark body) or black (light body)
 */
fun bevelHairline(body: Color, delta: Float = 0.08f): Color =
    if (body.luminance() < 0.5f) lerp(body, Color.White, delta)
    else lerp(body, Color.Black, delta)
