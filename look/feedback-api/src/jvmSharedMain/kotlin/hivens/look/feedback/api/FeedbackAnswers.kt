package hivens.look.feedback.api

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch

/** What a widget is, which decides how it answers an interaction. */
enum class FeedbackTarget {
    /** Something a person presses, types into or picks: a button, a chip, a toggle. */
    Control,

    /** A plane a person can press as a whole: a card that opens what it stands for. */
    Plane,
}

/**
 * Every answer of the feedback axis: how a widget answers what Compose calls an interaction, a pointer
 * over it, the keyboard's focus on it, a press, a drag, and how far what is disabled fades. A widget
 * reads them through [Feedback]. A provider fills them in with [build].
 *
 * Only interactions are asked here. That something is chosen, wrong, a target for a drop, open or busy
 * is told by the colour and stroke axes or by the widget itself.
 *
 * A role a provider leaves unfilled keeps its base answer: the indication Compose draws where nobody
 * chose one, black at 0.1 over what is hovered or focused and at 0.3 over what is pressed, and what is
 * disabled at Material's 0.38. [BASE] is every role at its base answer.
 *
 * Nothing here moves a layout: an indication draws over or under what it marks.
 *
 * Answers are never changed: a provider hands new ones whenever one of its roles changes.
 */
@Immutable
class FeedbackAnswers private constructor(filled: Builder) {

    private val indicationRule = filled.indication

    /**
     * How a widget of [target] answers its interactions: an [Indication], which the widget hands to its
     * clickable or draws with `Modifier.indication`. [shape] is the widget's outline, null where its own
     * clip shapes what is drawn. [tint] is the colour the colour axis gives the widget's content, null
     * where the indication takes the content colour around it.
     */
    fun indication(target: FeedbackTarget, shape: Shape?, tint: Color?): Indication =
        indicationRule?.invoke(target, shape, tint) ?: Neutral.INDICATION

    /** How opaque a disabled widget's content is, 0 to 1. Base Material's 0.38. */
    val disabledAlpha: Float = filled.disabledAlpha ?: Neutral.DISABLED_ALPHA

    /**
     * Where a provider fills in the roles it answers, every one empty at first, which keeps its base
     * answer. Each is named as the role it fills on [FeedbackAnswers].
     */
    class Builder internal constructor() {
        /** See [FeedbackAnswers.indication]. */
        var indication: ((target: FeedbackTarget, shape: Shape?, tint: Color?) -> Indication)? = null

        /** See [FeedbackAnswers.disabledAlpha]. */
        var disabledAlpha: Float? = null
    }

    companion object {
        /** Every role at its base answer: the feedback axis where no provider is chosen for it. */
        val BASE: FeedbackAnswers = FeedbackAnswers(Builder())

        /** The answers [block] fills in, every role it leaves alone at its base answer. */
        fun build(block: Builder.() -> Unit): FeedbackAnswers = FeedbackAnswers(Builder().apply(block))
    }
}

/** The feedback of the base answers: Compose's own. */
internal object Neutral {
    /** Material's opacity for disabled content. */
    const val DISABLED_ALPHA = 0.38f

    /**
     * The indication Compose's foundation draws where nobody provides one: black over the whole of
     * the widget's bounds, at 0.3 while it is pressed and at 0.1 while it is hovered or focused.
     */
    val INDICATION: IndicationNodeFactory = object : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode = DefaultIndicationNode(interactionSource)
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = System.identityHashCode(this)
        override fun toString(): String = "Feedback.Neutral"
    }
}

private class DefaultIndicationNode(private val interactionSource: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private var pressed = 0
    private var hovered = 0
    private var focused = 0

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressed--
                    is HoverInteraction.Enter -> hovered++
                    is HoverInteraction.Exit -> hovered--
                    is FocusInteraction.Focus -> focused++
                    is FocusInteraction.Unfocus -> focused--
                }
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        when {
            pressed > 0 -> drawRect(Color.Black.copy(alpha = 0.3f))
            hovered > 0 || focused > 0 -> drawRect(Color.Black.copy(alpha = 0.1f))
        }
    }
}
