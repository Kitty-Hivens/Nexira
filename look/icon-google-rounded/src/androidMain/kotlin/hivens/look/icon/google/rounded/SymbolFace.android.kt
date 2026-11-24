package hivens.look.icon.google.rounded

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily as PlatformFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.roundToInt

internal actual fun symbolFace(bytes: ByteArray): SymbolFace = AndroidSymbolFace(bytes)

private class AndroidSymbolFace(bytes: ByteArray) : SymbolFace {
    // Direct, which is what a font is read from in place, every copy the same memory.
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply {
        put(bytes)
        rewind()
    }

    override fun at(axes: SymbolAxes): SymbolInk {
        val font = Font.Builder(buffer)
            .setFontVariationSettings("'FILL' ${axes.fill}, 'wght' ${axes.weight}, 'opsz' ${axes.opticalSize}")
            .build()
        return Ink(Typeface.CustomFallbackBuilder(PlatformFamily.Builder(font).build()).build())
    }

    private class Ink(private val typeface: Typeface) : SymbolInk {
        override fun DrawScope.draw(glyph: String, tint: Color) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = this@Ink.typeface
                // The room's own pixels, so the icon is the size of its room whatever the font scale.
                textSize = size.minDimension
                color = tint.toArgb()
            }
            val lines = paint.fontMetricsInt
            val text = IntSize(ceil(paint.measureText(glyph)).toInt(), lines.descent - lines.ascent)
            val room = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val at = Alignment.Center.align(text, room, layoutDirection)
            drawIntoCanvas { it.nativeCanvas.drawText(glyph, at.x.toFloat(), (at.y - lines.ascent).toFloat(), paint) }
        }
    }
}
