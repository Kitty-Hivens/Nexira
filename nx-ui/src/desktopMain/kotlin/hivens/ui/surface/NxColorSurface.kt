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
import hivens.ui.theme.Form

/**
 * A surface whose fill is an ARBITRARY [color], and which does nothing else to it.
 *
 * Use ONLY where the colour is data, not a theme role: the theme-picker swatches
 * paint the colour of the theme being previewed, which is not the live palette, so
 * they cannot derive their body from a level. For any themed plane use NxSurface /
 * NxCard instead -- picking a tone per screen is the drift #351 closes.
 *
 * NxSurface takes a colour too, for the same reason. The difference is the treatment
 * around it: there a colour still meets the body floor, the bevel and the blur, and
 * saying "just this colour and nothing else" would mean naming four more arguments to
 * switch each of them off. Here it is a clipped fill with an optional [border].
 */
@Composable
fun NxColorSurface(
    color: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Form.cardCorner),
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
