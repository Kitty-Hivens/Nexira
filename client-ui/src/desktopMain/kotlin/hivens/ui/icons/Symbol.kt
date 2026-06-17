package hivens.ui.icons

import androidx.compose.foundation.layout.BoxWithConstraints
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

/** A Material Symbols glyph identified by its codepoint. See [NxIcon] for the catalog. */
@JvmInline
value class IconKey(val codepoint: Int)

/**
 * Renders a Material Symbols icon as a font glyph from the bundled subset font, replacing
 * the multi-MB material-icons-extended dependency. Drop-in for `Icon`: same argument order,
 * honours `Modifier.size(...)` (defaults to 24.dp like `Icon`). Sizes above 24.dp need the
 * explicit [size] parameter. The variable axes are driven per call:
 *
 * - [fill] 0f outlined .. 1f filled (animatable for the usual toggle effect)
 * - [weight] 100..700 stroke weight.
 */
@Composable
fun Symbol(
    icon: IconKey,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    fill: Float = 0f,
    weight: Int = 400,
    size: Dp? = null,
) {
    val base = Modifier.size(size ?: 24.dp).then(modifier)
    BoxWithConstraints(
        modifier = if (contentDescription != null) {
            base.semantics { this.contentDescription = contentDescription }
        } else {
            base
        },
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { maxHeight.toSp() }
        Text(
            text = String(Character.toChars(icon.codepoint)),
            color = tint,
            fontSize = px,
            fontFamily = FontFamily(
                Font(
                    Res.font.material_symbols,
                    variationSettings = FontVariation.Settings(
                        FontVariation.Setting("FILL", fill.coerceIn(0f, 1f)),
                        FontVariation.Setting("wght", weight.toFloat()),
                        FontVariation.Setting("opsz", px.value),
                        FontVariation.Setting("GRAD", 0f),
                    ),
                ),
            ),
            style = TextStyle(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}
