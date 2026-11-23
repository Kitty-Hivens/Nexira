package hivens.look.feedback.api

import androidx.compose.foundation.Indication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import hivens.look.manager.api.LookAxis

/**
 * The feedback axis: how a widget answers being hovered, focused, pressed or dragged, asked by what the
 * widget is, answered as one [FeedbackAnswers] by the provider the person's choice names, and by
 * [FeedbackAnswers.BASE] where no provider is chosen. A widget asks through the members here, which read
 * [local].
 *
 * A pack and the person's choice name it by [FeedbackApi.AXIS], and a provider answers it by naming that
 * id as its axis and [FeedbackApi.VERSION] as its contract.
 */
object Feedback : LookAxis<FeedbackAnswers>(FeedbackApi.AXIS, FeedbackApi.VERSION, FeedbackAnswers::class, FeedbackAnswers.BASE) {
    override val label: String = FeedbackModule.NAME

    /** See [FeedbackAnswers.indication]. */
    @Composable
    @ReadOnlyComposable
    fun indication(target: FeedbackTarget, shape: Shape? = null, tint: Color? = null): Indication =
        local.current.indication(target, shape, tint)

    /** See [FeedbackAnswers.disabledAlpha]. */
    val disabledAlpha: Float
        @Composable @ReadOnlyComposable
        get() = local.current.disabledAlpha
}
