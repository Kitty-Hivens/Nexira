package hivens.ui.surface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

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
    shape: Shape = RoundedCornerShape(12.dp),
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

@Preview
@Composable
private fun NxColorSurfaceDarkPreview() = ColorSurfaces(dark = true)

@Preview
@Composable
private fun NxColorSurfaceLightPreview() = ColorSurfaces(dark = false)

@Composable
private fun ColorSurfaces(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(360.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NxColorSurface(Color(0xFF3B6EA8), Modifier.size(48.dp))
            NxColorSurface(Color(0xFFB8608E), Modifier.size(48.dp), shape = RoundedCornerShape(8.dp))
            NxColorSurface(Color(0xFFF2F2F2), Modifier.size(48.dp), border = BorderStroke(1.dp, NxInk.line))
            NxColorSurface(Color(0xFF1B1B1B), Modifier.size(48.dp), shape = CircleShape, border = BorderStroke(2.dp, NxColor.lead()))
            NxColorSurface(Color.Transparent, Modifier.size(48.dp), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, NxInk.line))
        }
    }
}
