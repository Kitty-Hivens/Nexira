package hivens.ui.nx

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/**
 * The M3 state-layer alpha for the current interaction: press over focus over
 * hover over idle. Pulled out as a pure function so the contract (press 12% /
 * focus 10% / hover 8% / idle 0) is unit-testable without a renderer.
 */
internal fun stateLayerAlpha(pressed: Boolean, hovered: Boolean, focused: Boolean): Float =
    StateLayerAlphas.CONTROL.of(pressed, hovered, focused)

/**
 * How strong a state layer is in each interaction. Press wins, then focus where the layer
 * shows focus at all, then hover.
 */
internal data class StateLayerAlphas(val hover: Float, val focus: Float, val press: Float) {
    fun of(pressed: Boolean, hovered: Boolean, focused: Boolean): Float = when {
        pressed -> press
        focused && focus > 0f -> focus
        hovered -> hover
        else -> 0f
    }

    companion object {
        /** A control's: the M3 state-layer scale, over the control's content colour. */
        val CONTROL = StateLayerAlphas(hover = 0.08f, focus = 0.10f, press = 0.12f)

        /** A plane's: a lighter hover over the lead, and no focus of its own. */
        val PLANE = StateLayerAlphas(hover = 0.06f, focus = 0f, press = 0.12f)
    }
}

/**
 * nx's answer to an interaction: a state layer drawn under what the widget holds, in [color]
 * at [alphas], over the widget's own [shape] so it can never mismatch the container the way
 * M3 1.11-alpha07's default ripple did. A null [shape] fills the bounds, which the widget's
 * own clip shapes. A null [color] takes the ambient [LocalContentColor] at draw time.
 */
internal class StateLayer(
    private val shape: Shape?,
    private val color: Color?,
    private val alphas: StateLayerAlphas,
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayerNode(interactionSource, shape, color, alphas)

    override fun equals(other: Any?): Boolean =
        other is StateLayer && other.shape == shape && other.color == color && other.alphas == alphas

    override fun hashCode(): Int = listOf(shape, color, alphas).hashCode()
}

/**
 * The draw node behind [StateLayer]. A null [color] resolves the ambient [LocalContentColor]
 * at draw time (the composition-local read invalidates draw when it changes).
 */
private class StateLayerNode(
    private val interactionSource: InteractionSource,
    private val shape: Shape?,
    private val color: Color?,
    private val alphas: StateLayerAlphas,
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
        val alpha = alphas.of(pressed, hovered, focused)
        if (alpha > 0f) {
            val tint = (color ?: currentValueOf(LocalContentColor)).copy(alpha = alpha)
            val hostShape = shape
            if (hostShape != null) {
                drawOutline(hostShape.createOutline(size, layoutDirection, this), color = tint)
            } else {
                drawRect(tint)
            }
        }
        drawContent()
    }
}
