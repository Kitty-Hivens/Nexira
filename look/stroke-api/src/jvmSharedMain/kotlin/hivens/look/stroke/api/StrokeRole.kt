package hivens.look.stroke.api

import androidx.compose.foundation.border
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle

/**
 * One answer of the stroke axis: a stroke that draws itself. A widget hands it what to draw along, an
 * outline, a line or a path, with the brush to draw in and the state it is in ([StrokeState]), and the
 * answer draws. How thick, how it ends and joins, whether it is dashed, doubled, glowing or anything
 * else is the answer's own, so a role says what a stroke is for and never how it looks.
 *
 * The colour is not the answer's: it is the colour axis's, and the widget passes it as the brush.
 *
 * A provider answers a plain stroke with [solid], one that differs by state with [byState], or a stroke
 * of its own by extending this class. Everything plain Compose can stroke is covered: an outline around
 * a node ([outline], what `Modifier.border` does), and an outline, a line or a path in a draw block
 * ([strokeOutline], [strokeLine], [strokePath], what `drawOutline`, `drawLine` and `drawPath` do with
 * a `Stroke` style). A stroke that moves over time does so in [outline], whose node may run its own
 * animation. The draw calls draw one frame of the state they are given.
 */
@Immutable
abstract class StrokeRole {

    /** How thick it draws in [state]. `Dp.Hairline` is one device pixel at any density. */
    abstract fun width(state: StrokeState = StrokeState.Rest): Dp

    /** How it draws in [state] at [density], as Compose's own stroke style: width in pixels, cap, join, miter, dash. */
    abstract fun style(state: StrokeState, density: Density): DrawStyle

    /** The whole pixels a layout leaves for it in [state], never fewer than one. */
    fun thickness(state: StrokeState, density: Density): Int = ceil(style(state, density).width).toInt().coerceAtLeast(1)

    /** Draws along [outline] as it is given, the middle of the stroke on the outline. */
    open fun DrawScope.strokeOutline(outline: Outline, brush: Brush, state: StrokeState) {
        drawOutline(outline, brush, style = style(state, this))
    }

    /** Draws from [start] to [end], the middle of the stroke on the line. */
    open fun DrawScope.strokeLine(start: Offset, end: Offset, brush: Brush, state: StrokeState) {
        val style = style(state, this)
        drawLine(brush, start, end, strokeWidth = style.width, cap = style.cap, pathEffect = style.pathEffect)
    }

    /** Draws along [path], the middle of the stroke on it. */
    open fun DrawScope.strokePath(path: Path, brush: Brush, state: StrokeState) {
        drawPath(path, brush, style = style(state, this))
    }

    /**
     * Outlines a node in [shape], inside its bounds and over its content, the way `Modifier.border`
     * does. The default insets the outline by half the stroke and draws it with [strokeOutline].
     */
    open fun outline(shape: Shape, brush: Brush, state: StrokeState): Modifier = Modifier.drawWithCache {
        val half = thickness(state, this) / 2f
        val inner = Size((size.width - 2 * half).coerceAtLeast(0f), (size.height - 2 * half).coerceAtLeast(0f))
        val along = shape.createOutline(inner, layoutDirection, this)
        onDrawWithContent {
            drawContent()
            translate(half, half) { strokeOutline(along, brush, state) }
        }
    }

    companion object {
        /**
         * A stroke of one [width] in every state, ending in [cap], joining in [join] up to [miter], dashed
         * by [dash] where given. [snap] holds it to whole device pixels, at least one, so a hairline stays
         * crisp at a fractional density.
         */
        fun solid(
            width: Dp,
            cap: StrokeCap = StrokeCap.Butt,
            join: StrokeJoin = StrokeJoin.Miter,
            miter: Float = DrawStyle.DefaultMiter,
            dash: Dash? = null,
            snap: Boolean = false,
        ): StrokeRole = SolidRole(width, cap, join, miter, dash, snap)

        /** A stroke that is [answer]'s for each state it is asked in. */
        fun byState(answer: (StrokeState) -> StrokeRole): StrokeRole = StateRole(answer)
    }
}

/** A dash: [on] drawn, [off] left, starting [phase] into the pattern. */
@Immutable
class Dash(val on: Dp, val off: Dp, val phase: Dp = 0.dp) {
    override fun equals(other: Any?): Boolean = other is Dash && other.on == on && other.off == off && other.phase == phase

    override fun hashCode(): Int = listOf(on, off, phase).hashCode()

    override fun toString(): String = "Dash(on=$on, off=$off, phase=$phase)"
}

/** Outlines this node by [role] in [shape], see [StrokeRole.outline]. */
fun Modifier.stroke(role: StrokeRole, brush: Brush, shape: Shape, state: StrokeState = StrokeState.Rest): Modifier =
    then(role.outline(shape, brush, state))

/** Outlines this node by [role] in [shape], in one [color], see [StrokeRole.outline]. */
fun Modifier.stroke(role: StrokeRole, color: Color, shape: Shape, state: StrokeState = StrokeState.Rest): Modifier =
    stroke(role, SolidColor(color), shape, state)

/** Draws along [outline] by [role], see [StrokeRole.strokeOutline]. */
fun DrawScope.drawStroke(role: StrokeRole, outline: Outline, brush: Brush, state: StrokeState = StrokeState.Rest) {
    with(role) { strokeOutline(outline, brush, state) }
}

/** Draws from [start] to [end] by [role], see [StrokeRole.strokeLine]. */
fun DrawScope.drawStroke(role: StrokeRole, start: Offset, end: Offset, brush: Brush, state: StrokeState = StrokeState.Rest) {
    with(role) { strokeLine(start, end, brush, state) }
}

/** Draws along [path] by [role], see [StrokeRole.strokePath]. */
fun DrawScope.drawStroke(role: StrokeRole, path: Path, brush: Brush, state: StrokeState = StrokeState.Rest) {
    with(role) { strokePath(path, brush, state) }
}

private class SolidRole(
    private val width: Dp,
    private val cap: StrokeCap,
    private val join: StrokeJoin,
    private val miter: Float,
    private val dash: Dash?,
    private val snap: Boolean,
) : StrokeRole() {

    override fun width(state: StrokeState): Dp = width

    override fun style(state: StrokeState, density: Density): DrawStyle = with(density) {
        val px = width.toPx()
        DrawStyle(
            width = if (snap) px.roundToInt().coerceAtLeast(1).toFloat() else px,
            miter = miter,
            cap = cap,
            join = join,
            pathEffect = dash?.let { PathEffect.dashPathEffect(floatArrayOf(it.on.toPx(), it.off.toPx()), it.phase.toPx()) },
        )
    }

    // Compose's own border where it draws the same stroke, so a plain edge is exactly what
    // Modifier.border draws. It fills its outline rather than stroking it, so it has no dash, no
    // join but a mitred one and no snapping, and those go the drawn way.
    override fun outline(shape: Shape, brush: Brush, state: StrokeState): Modifier =
        if (dash == null && !snap && join == StrokeJoin.Miter) Modifier.border(width, brush, shape) else super.outline(shape, brush, state)

    override fun equals(other: Any?): Boolean =
        other is SolidRole && other.width == width && other.cap == cap && other.join == join && other.miter == miter &&
            other.dash == dash && other.snap == snap

    override fun hashCode(): Int = listOf(width, cap, join, miter, dash, snap).hashCode()

    override fun toString(): String = "StrokeRole.solid($width, $cap, $join, miter=$miter, dash=$dash, snap=$snap)"
}

private class StateRole(private val answer: (StrokeState) -> StrokeRole) : StrokeRole() {

    override fun width(state: StrokeState): Dp = answer(state).width(state)

    override fun style(state: StrokeState, density: Density): DrawStyle = answer(state).style(state, density)

    override fun DrawScope.strokeOutline(outline: Outline, brush: Brush, state: StrokeState) {
        drawStroke(answer(state), outline, brush, state)
    }

    override fun DrawScope.strokeLine(start: Offset, end: Offset, brush: Brush, state: StrokeState) {
        drawStroke(answer(state), start, end, brush, state)
    }

    override fun DrawScope.strokePath(path: Path, brush: Brush, state: StrokeState) {
        drawStroke(answer(state), path, brush, state)
    }

    override fun outline(shape: Shape, brush: Brush, state: StrokeState): Modifier = answer(state).outline(shape, brush, state)

    override fun toString(): String = "StrokeRole.byState($answer)"
}
