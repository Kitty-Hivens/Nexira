package hivens.look.icon.api

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate

/**
 * The icons the contract draws itself, on a grid of 24, so a control whose whole face is one of them is
 * still there when no library is: strokes of 2, with no filling and no weight.
 */
internal object Essentials {
    private val drawings: Map<String, IconDrawing> = mapOf(
        "close" to lines("6,6 18,18", "18,6 6,18"),
        "arrow_back" to lines("20,12 4,12", "11,5 4,12 11,19"),
        "check" to lines("5,12.5 10,17.5 19,7"),
        "add" to lines("12,5 12,19", "5,12 19,12"),
        "chevron_left" to lines("14.5,6 8.5,12 14.5,18"),
        "chevron_right" to lines("9.5,6 15.5,12 9.5,18"),
        "expand_more" to lines("6,9.5 12,15.5 18,9.5"),
        "expand_less" to lines("6,14.5 12,8.5 18,14.5"),
        "keyboard_arrow_down" to lines("6,9.5 12,15.5 18,9.5"),
        "keyboard_arrow_up" to lines("6,14.5 12,8.5 18,14.5"),
        "arrow_drop_down" to lines("7,10 12,15 17,10"),
        "more_vert" to lines("12,5 12,5", "12,12 12,12", "12,19 12,19"),
    )

    val names: Set<String> get() = drawings.keys

    fun of(name: String): IconDrawing? = drawings[name]

    /** Strokes through the points of each of [strokes], as `x,y` apart by spaces. */
    private fun lines(vararg strokes: String): IconDrawing = Lines(
        strokes.map { stroke ->
            stroke.split(' ').map { point -> point.split(',').let { (x, y) -> Offset(x.toFloat(), y.toFloat()) } }
        },
    )
}

@Immutable
private class Lines(private val strokes: List<List<Offset>>) : IconDrawing() {
    // Made as it is first drawn: a path is the platform's, and the answers are there before anything draws.
    private val path by lazy {
        Path().apply {
            strokes.forEach { points ->
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
        }
    }

    @Composable
    override fun Draw(style: IconStyle) = Spacer(
        Modifier.drawBehind {
            val side = size.minDimension
            translate((size.width - side) / 2f, (size.height - side) / 2f) {
                scale(side / GRID, pivot = Offset.Zero) {
                    drawPath(path, style.tint, style = Stroke(STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        },
    )
}

private const val GRID = 24f
private const val STROKE = 2f
