package hivens.look.icon.google.rounded

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as PlatformFamily
import androidx.compose.ui.text.font.FontFamily
import java.nio.ByteBuffer

internal actual fun symbolFace(bytes: ByteArray): SymbolFace = AndroidSymbolFace(bytes)

private class AndroidSymbolFace(bytes: ByteArray) : SymbolFace {
    // Direct, which is what a font is read from in place, every copy the same memory.
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        rewind()
    }

    override fun at(axes: SymbolAxes): FontFamily {
        val font = Font.Builder(buffer)
            .setFontVariationSettings("'FILL' ${axes.fill}, 'wght' ${axes.weight}, 'opsz' ${axes.opticalSize}")
            .build()
        return FontFamily(Typeface.CustomFallbackBuilder(PlatformFamily.Builder(font).build()).build())
    }
}
