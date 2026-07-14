package hivens.ui.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import hivens.ui.theme.LocalStyle

/**
 * A surface whose fill is an ARBITRARY [color] -- the one escape hatch NxSurface
 * deliberately withholds. Use ONLY where the colour is data, not a theme role:
 * the theme-picker swatches paint the colour of the theme being previewed, which
 * is not the live palette, so they cannot derive their body from a role. For any
 * themed plane use NxSurface / NxCard instead -- a colour argument there would
 * reopen the per-screen colour drift #351 closes.
 *
 * Because the colour is data, this does none of NxSurface's per-theme work (body
 * floor, bevel, glass coat): it is a plain clipped fill with an optional [border].
 */
@Composable
fun NxColorSurface(
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalStyle.current.cardCorner),
    border: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        content = content,
    )
}
