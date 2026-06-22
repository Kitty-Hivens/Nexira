package hivens.ui.components

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
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
internal object NoOpIndication : IndicationNodeFactory {
    private class EmptyNode : Modifier.Node()

    override fun create(interactionSource: InteractionSource): DelegatableNode = EmptyNode()

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Hover / press state layer drawn as a rounded rect with the host's own corner
 * radius ([cornerDp], matching MaterialTheme.shapes.small / LocalStyle.buttonCorner),
 * so it can never mismatch the container the way M3 1.11-alpha07's default ripple
 * did. Alphas follow the M3 state-layer scale (hover ~8%, press ~12%) over
 * [color] -- pass the host's content color.
 */
internal class ShapedStateLayer(
    private val cornerDp: Dp,
    private val color: Color,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateLayerNode(interactionSource, cornerDp, color)

    override fun equals(other: Any?): Boolean =
        other is ShapedStateLayer && other.cornerDp == cornerDp && other.color == color

    override fun hashCode(): Int = 31 * cornerDp.hashCode() + color.hashCode()

    private class StateLayerNode(
        private val interactionSource: InteractionSource,
        private val cornerDp: Dp,
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
            val alpha = when {
                pressed -> 0.12f
                hovered -> 0.08f
                else    -> 0f
            }
            if (alpha > 0f) {
                val r = cornerDp.toPx()
                drawRoundRect(color = color.copy(alpha = alpha), cornerRadius = CornerRadius(r, r))
            }
            drawContent()
        }
    }
}
