package hivens.look.icon.google.rounded

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toOffset
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import kotlin.math.roundToInt

internal actual fun symbolFace(bytes: ByteArray): SymbolFace = SkiaSymbolFace(bytes)

private class SkiaSymbolFace(bytes: ByteArray) : SymbolFace {
    private val base = requireNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "the icon font does not load" }

    // Laid out as Compose lays its own text out, so an icon has the edges text beside it has. One for
    // every face, kept under a lock: a window and an off-screen picture may draw at the same time.
    private val measurer = TextMeasurer(createFontFamilyResolver(), LocaleList.Empty, Density(1f), LayoutDirection.Ltr, LAYOUTS)

    // Named, since Compose keeps faces by name and every copy carries the family name of the first.
    override fun at(axes: SymbolAxes): SymbolInk = Ink(
        FontFamily(
            Typeface(
                base.makeClone(
                    arrayOf(
                        FontVariation("FILL", axes.fill),
                        FontVariation("wght", axes.weight.toFloat()),
                        FontVariation("opsz", axes.opticalSize),
                    ),
                ),
                axes.key,
            ),
        ),
    )

    private inner class Ink(private val family: FontFamily) : SymbolInk {
        override fun DrawScope.draw(glyph: String, tint: Color) {
            val style = TextStyle(
                fontSize = size.minDimension.toSp(),
                fontFamily = family,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            )
            // A density of its own value: the scope is one too, and a kept layout would hold on to it.
            val drawnAt = Density(density, fontScale)
            val layout = synchronized(measurer) { measurer.measure(glyph, style, layoutDirection = layoutDirection, density = drawnAt) }
            val room = IntSize(size.width.roundToInt(), size.height.roundToInt())
            drawText(layout, tint, Alignment.Center.align(layout.size, room, layoutDirection).toOffset())
        }
    }

    private companion object {
        /** Glyphs kept laid out. A screen of icons holds a few dozen, one moving between fillings passes 33. */
        const val LAYOUTS = 512
    }
}
