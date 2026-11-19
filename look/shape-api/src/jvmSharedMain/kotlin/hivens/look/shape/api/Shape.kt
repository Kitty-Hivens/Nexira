package hivens.look.shape.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis
import androidx.compose.ui.graphics.Shape as Outline

/**
 * The shape axis: the corners and outlines a widget asks by what it draws, answered as one
 * [ShapeAnswers] by the provider the person's choice names, and by [ShapeAnswers.BASE] where none
 * does. A widget asks through the members here, which read [local].
 *
 * A pack and the person's choice name it by [ShapeApi.AXIS], and a provider answers it by naming that
 * id as its axis and [ShapeApi.VERSION] as its contract.
 */
object Shape : LookAxis<ShapeAnswers>(ShapeApi.AXIS, ShapeApi.VERSION, ShapeAnswers::class, ShapeAnswers.BASE) {
    override val label: String = ShapeModule.NAME

    /** See [ShapeAnswers.control]. */
    val control: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.control

    /** See [ShapeAnswers.card]. */
    val card: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.card

    /** See [ShapeAnswers.capsule]. */
    val capsule: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.capsule

    /** See [ShapeAnswers.indicator]. */
    val indicator: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.indicator

    /** See [ShapeAnswers.menu]. */
    val menu: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.menu

    /** See [ShapeAnswers.tooltip]. */
    val tooltip: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.tooltip

    /** See [ShapeAnswers.sheet]. */
    val sheet: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.sheet

    /** See [ShapeAnswers.dialog]. */
    val dialog: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.dialog

    /** See [ShapeAnswers.thumb]. */
    val thumb: Outline
        @Composable @ReadOnlyComposable
        get() = local.current.thumb

    /** See [ShapeAnswers.plate]. */
    val plate: PlateOutline
        @Composable @ReadOnlyComposable
        get() = local.current.plate
}
