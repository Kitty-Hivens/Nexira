package hivens.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.nx.ui.generated.resources.Res
import hivens.nx.ui.generated.resources.material_symbols
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
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
    // Caller modifier before the default size: the reverse order lets size(24)
    // clamp a smaller caller size up (size enforces incoming constraints).
    val base = modifier.then(Modifier.size(size ?: 24.dp))
    BoxWithConstraints(
        modifier = if (contentDescription != null) {
            base.semantics { this.contentDescription = contentDescription }
        } else {
            base
        },
        contentAlignment = Alignment.Center,
    ) {
        val px = with(LocalDensity.current) { maxHeight.toSp() }
        // Remembered per axis value: every icon on screen built a fresh settings
        // object and font family on each composition, so a list of rows recomposing
        // allocated a font family per glyph per frame.
        val opsz = px.value.coerceIn(24f, 48f)
        val settings = remember(fill, weight, opsz) {
            FontVariation.Settings(
                FontVariation.Setting("FILL", fill.coerceIn(0f, 1f)),
                FontVariation.Setting("wght", weight.toFloat()),
                FontVariation.Setting("opsz", opsz),
                FontVariation.Setting("GRAD", 0f),
            )
        }
        val font = Font(Res.font.material_symbols, variationSettings = settings)
        val family = remember(font) { FontFamily(font) }
        Text(
            // Unbounded so a dense glyph (globe, description) whose ink reaches the
            // em edges isn't clipped by the size-px box -- it overflows by a hair
            // into the caller's own padding instead of "sinking in".
            modifier = Modifier.wrapContentSize(unbounded = true),
            text = String(Character.toChars(icon.codepoint)),
            color = tint,
            fontSize = px,
            fontFamily = family,
            style = TextStyle(
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

@Preview
@Composable
private fun SymbolDarkPreview() = SymbolCatalogue(dark = true)

@Preview
@Composable
private fun SymbolLightPreview() = SymbolCatalogue(dark = false)

/**
 * Every glyph [NxIcon] holds, read off the object itself so the sheet follows the generated
 * catalogue, then the axes one glyph can be drawn at.
 */
@Composable
private fun SymbolCatalogue(dark: Boolean) {
    val catalogue = remember {
        NxIcon::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType && !it.name.startsWith("$") }
            .onEach { it.isAccessible = true }
            .map { it.name to IconKey(it.getInt(null)) }
            .sortedBy { it.first }
    }
    NxTheme(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(760.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in catalogue.chunked(7)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, icon) in row) {
                        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Symbol(icon, null, tint = NxInk.main)
                            Text(name, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(NxIcon.Favorite, null, tint = NxColor.lead())
                Symbol(NxIcon.Favorite, null, tint = NxColor.lead(), fill = 1f)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 200)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 400)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 700)
                Symbol(NxIcon.Star, null, tint = NxInk.main, size = 16.dp)
                Symbol(NxIcon.Star, null, tint = NxInk.main, size = 40.dp)
                Symbol(NxIcon.Star, null, tint = NxInk.main, modifier = Modifier.size(32.dp))
            }
        }
    }
}
