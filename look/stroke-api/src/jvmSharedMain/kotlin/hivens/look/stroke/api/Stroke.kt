package hivens.look.stroke.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis

/**
 * The stroke axis: the strokes a widget asks by what they are for, answered as one [StrokeAnswers] by
 * the provider the person's choice names, and by [StrokeAnswers.BASE] where none does. A widget asks
 * through the members here, which read [local], and hands the answer what to draw along and the state
 * it is in.
 *
 * A pack and the person's choice name it by [StrokeApi.AXIS], and a provider answers it by naming that
 * id as its axis and [StrokeApi.VERSION] as its contract.
 */
object Stroke : LookAxis<StrokeAnswers>(StrokeApi.AXIS, StrokeApi.VERSION, StrokeAnswers::class, StrokeAnswers.BASE) {
    override val label: String = StrokeModule.NAME

    /** See [StrokeAnswers.edge]. */
    val edge: StrokeRole
        @Composable @ReadOnlyComposable
        get() = local.current.edge

    /** See [StrokeAnswers.mark]. */
    val mark: StrokeRole
        @Composable @ReadOnlyComposable
        get() = local.current.mark

    /** See [StrokeAnswers.divider]. */
    val divider: StrokeRole
        @Composable @ReadOnlyComposable
        get() = local.current.divider

    /** See [StrokeAnswers.placeholder]. */
    val placeholder: StrokeRole
        @Composable @ReadOnlyComposable
        get() = local.current.placeholder
}
