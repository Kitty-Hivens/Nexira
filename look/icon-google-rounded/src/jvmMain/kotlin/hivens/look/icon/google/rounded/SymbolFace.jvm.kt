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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toOffset
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import kotlin.math.roundToInt

internal actual fun symbolFace(bytes: ByteArray): SymbolFace = SkiaSymbolFace(bytes)

private class SkiaSymbolFace(bytes: ByteArray) : SymbolFace {
    private val base = requireNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "the icon font does not load" }

    // Laid out as Compose lays its own text out, so an icon has the edges text beside it has. At a density
    // of one, since the size of a room is in its own pixels already. One for every face, kept under a lock:
    // a window and an off-screen picture may draw at the same time. Made at the first icon drawn and not
    // with the face: the face is read on the boot thread before any window, where Compose has no text
    // to lay out yet, and the provider failed to open there.
    private val measurer by lazy { TextMeasurer(createFontFamilyResolver(), LocaleList.Empty, Density(1f), LayoutDirection.Ltr, LAYOUTS) }

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
                fontSize = size.minDimension.sp,
                fontFamily = family,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            )
            val layout = synchronized(measurer) { measurer.measure(glyph, style, layoutDirection = layoutDirection) }
            val room = IntSize(size.width.roundToInt(), size.height.roundToInt())
            drawText(layout, tint, Alignment.Center.align(layout.size, room, layoutDirection).toOffset())
        }
    }

    private companion object {
        /** Glyphs kept laid out. A screen of icons holds a few dozen, one moving between fillings passes 33. */
        const val LAYOUTS = 512
    }
}
