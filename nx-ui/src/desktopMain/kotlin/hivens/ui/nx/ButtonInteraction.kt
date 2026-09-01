package hivens.ui.nx

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch

/**
 * The M3 state-layer alpha for the current interaction: press over focus over
 * hover over idle. Pulled out as a pure function so the contract (press 12% /
 * focus 10% / hover 8% / idle 0) is unit-testable without a renderer.
 */
internal fun stateLayerAlpha(pressed: Boolean, hovered: Boolean, focused: Boolean): Float = when {
    pressed -> 0.12f
    focused -> 0.10f
    hovered -> 0.08f
    else    -> 0f
}

/**
 * The theme-level indication: [hivens.ui.theme.NxTheme] provides it as the app-wide
 * `LocalIndication`, so every default-overload clickable/selectable/toggleable inherits
 * a state layer instead of the mismatched default ripple. It fills the node's bounds
 * tinted with the ambient [LocalContentColor] -- a host that clips before its clickable
 * clips the layer to its own shape, so feedback is shape-correct by construction; an
 * unclipped full-bleed row keeps an honest rectangle.
 */
object ThemeStateLayer : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayerNode(interactionSource, cornerDp = null, shape = null, color = null)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Hover / press state layer drawn at the host's own shape, so it can never
 * mismatch the container the way M3 1.11-alpha07's default ripple did. Two ways
 * to hand it that shape:
 *
 *  - `ShapedStateLayer(cornerDp, color)` -- a uniform rounded rect at [cornerDp]
 *    (the button path: matches MaterialTheme.shapes.small, which Form.materialShapes
 *    anchors to Form.buttonCorner).
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
}

/**
 * The one draw node behind [ThemeStateLayer] and [ShapedStateLayer]. Geometry: a
 * [cornerDp] rounded rect, a [shape] outline, or (both null) a bounds fill that the
 * host's clip shapes. A null [color] resolves the ambient [LocalContentColor] at draw
 * time (the composition-local read invalidates draw when it changes).
 */
private class StateLayerNode(
    private val interactionSource: InteractionSource,
    private val cornerDp: Dp?,
    private val shape: Shape?,
    private val color: Color?,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var hovered = false
    private var pressed = false
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> hovered = true
                    is HoverInteraction.Exit -> hovered = false
                    is PressInteraction.Press -> pressed = true
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    is FocusInteraction.Focus -> focused = true
                    is FocusInteraction.Unfocus -> focused = false
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val alpha = stateLayerAlpha(pressed, hovered, focused)
        if (alpha > 0f) {
            val tint = (color ?: currentValueOf(LocalContentColor)).copy(alpha = alpha)
            val corner = cornerDp
            val hostShape = shape
            when {
                corner != null -> {
                    val r = corner.toPx()
                    drawRoundRect(color = tint, cornerRadius = CornerRadius(r, r))
                }
                hostShape != null ->
                    drawOutline(hostShape.createOutline(size, layoutDirection, this), color = tint)
                else -> drawRect(tint)
            }
        }
        drawContent()
    }
}
