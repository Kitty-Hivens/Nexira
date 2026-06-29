package hivens.ui.nx

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Shape-correct hover/press feedback for buttons, kept with the base
 * components rather than inside the April Fools layer. The canonical button
 * ([NxButton]) and the easter-egg button both draw their state layer through
 * here, so a button's interaction correctness does not depend on the seasonal
 * prank being on the classpath.
 *
 * M3 1.11-alpha07's default ripple paints its state-layer with a shape that
 * doesn't match the button container, so the layer is drawn here as a rounded
 * rect with the host's own corner radius -- it can't mismatch.
 */
object NoOpIndication : IndicationNodeFactory {
    private class EmptyNode : Modifier.Node()

    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyNode()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The M3 state-layer alpha for the current interaction: press over hover over
 * idle. Pulled out as a pure function so the contract (press 12% / hover 8% /
 * idle 0) is unit-testable without a renderer.
 */
internal fun stateLayerAlpha(pressed: Boolean, hovered: Boolean): Float = when {
    pressed -> 0.12f
    hovered -> 0.08f
    else    -> 0f
}

/**
 * Hover / press state layer drawn at the host's own shape, so it can never
 * mismatch the container the way M3 1.11-alpha07's default ripple did. Two ways
 * to hand it that shape:
 *
 *  - `ShapedStateLayer(cornerDp, color)` -- a uniform rounded rect at [cornerDp]
 *    (the button path: matches MaterialTheme.shapes.small / LocalStyle.buttonCorner).
 *  - `ShapedStateLayer(shape, color)` -- the host's actual [Shape] outline
 *    (circle, pill, per-corner radii), for non-button hosts.
 *
 * Alphas follow the M3 state-layer scale (hover ~8%, press ~12%) over [color] --
 * pass the host's content color.
 */
class ShapedStateLayer private constructor(
    private val cornerDp: Dp?,
    private val shape: Shape?,
    private val color: Color,
) : IndicationNodeFactory {

    constructor(cornerDp: Dp, color: Color) : this(cornerDp, null, color)
    constructor(shape: Shape, color: Color) : this(null, shape, color)

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayerNode(interactionSource, cornerDp, shape, color)

    override fun equals(other: Any?): Boolean =
        other is ShapedStateLayer && other.cornerDp == cornerDp && other.shape == shape && other.color == color

    override fun hashCode(): Int {
        var h = cornerDp.hashCode()
        h = 31 * h + shape.hashCode()
        h = 31 * h + color.hashCode()
        return h
    }

    private class StateLayerNode(
        private val interactionSource: InteractionSource,
        private val cornerDp: Dp?,
        private val shape: Shape?,
        private val color: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private var hovered = false
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is HoverInteraction.Enter -> hovered = true
                        is HoverInteraction.Exit -> hovered = false
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            val alpha = stateLayerAlpha(pressed, hovered)
            if (alpha > 0f) {
                val tint = color.copy(alpha = alpha)
                val corner = cornerDp
                val hostShape = shape
                if (corner != null) {
                    val r = corner.toPx()
                    drawRoundRect(color = tint, cornerRadius = CornerRadius(r, r))
                } else if (hostShape != null) {
                    drawOutline(hostShape.createOutline(size, layoutDirection, this), color = tint)
                }
            }
            drawContent()
        }
    }
}
