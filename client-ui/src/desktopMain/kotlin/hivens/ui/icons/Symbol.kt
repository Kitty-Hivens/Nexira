package hivens.ui.icons

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.material_symbols
import org.jetbrains.compose.resources.Font

/** A Material Symbols glyph, identified by its codepoint. See [NxIcon] for the catalog. */
@JvmInline
value class IconKey(val codepoint: Int)

/**
 * Renders a Material Symbols icon as a font glyph -- the whole icon set is one bundled
 * subset font (only the glyphs in tools/icons/icons.txt), so this replaces the multi-MB
 * material-icons-extended dependency. The variable axes are driven per call:
 *
 * - [fill] 0f outlined .. 1f filled (animate it for the usual toggle effect)
 * - [weight] 100..700 stroke weight, [size] both the box and the optical size.
 */
@Composable
fun Symbol(
    icon: IconKey,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    fill: Float = 0f,
    weight: Int = 400,
    contentDescription: String? = null,
) {
    val pxSize = with(LocalDensity.current) { size.toSp() }
    val family = FontFamily(
        Font(
            Res.font.material_symbols,
            variationSettings = FontVariation.Settings(
                FontVariation.Setting("FILL", fill.coerceIn(0f, 1f)),
                FontVariation.Setting("wght", weight.toFloat()),
                FontVariation.Setting("opsz", pxSize.value),
                FontVariation.Setting("GRAD", 0f),
            ),
        ),
    )
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = String(Character.toChars(icon.codepoint)),
            color = tint,
            fontSize = pxSize,
            fontFamily = family,
            style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)),
            modifier = if (contentDescription != null) {
                Modifier.semantics { this.contentDescription = contentDescription }
            } else {
                Modifier
            },
        )
    }
}
