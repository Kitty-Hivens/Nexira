package hivens.look.icon.google.rounded

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation

internal actual fun symbolFace(bytes: ByteArray): SymbolFace = SkiaSymbolFace(bytes)

private class SkiaSymbolFace(bytes: ByteArray) : SymbolFace {
    private val base = requireNotNull(FontMgr.default.makeFromData(Data.makeFromBytes(bytes))) { "the icon font does not load" }

    // Named, since Compose keeps faces by name and every copy carries the family name of the first.
    override fun at(axes: SymbolAxes): FontFamily = FontFamily(
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
    )
}
