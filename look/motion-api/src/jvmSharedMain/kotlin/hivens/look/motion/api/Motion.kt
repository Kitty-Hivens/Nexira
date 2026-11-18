package hivens.look.motion.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis
import hivens.widget.model.Entrance

/**
 * The motion axis: what a widget asks of movement, by what is happening, answered as one
 * [MotionAnswers] by the provider the person's choice names, and by [MotionAnswers.BASE] where none
 * does. A widget asks through the members here, which read [local].
 *
 * A pack and the person's choice name it by [MotionApi.AXIS], and a provider answers it by naming that
 * id as its axis and [MotionApi.VERSION] as its contract.
 */
object Motion : LookAxis<MotionAnswers>(MotionApi.AXIS, MotionApi.VERSION, MotionAnswers::class, MotionAnswers.BASE) {
    override val label: String = MotionModule.NAME

    /** See [MotionAnswers.tap]. */
    val tap: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.tap

    /** See [MotionAnswers.colourShift]. */
    val colourShift: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.colourShift

    /** See [MotionAnswers.track]. */
    val track: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.track

    /** See [MotionAnswers.fade]. */
    val fade: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.fade

    /** See [MotionAnswers.popup]. */
    val popup: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.popup

    /** See [MotionAnswers.panelSlide]. */
    val panelSlide: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.panelSlide

    /** See [MotionAnswers.reveal]. */
    val reveal: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.reveal

    /** See [MotionAnswers.emphasis]. */
    val emphasis: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.emphasis

    /** See [MotionAnswers.pageSwap]. */
    val pageSwap: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.pageSwap

    /** See [MotionAnswers.scroll]. */
    val scroll: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.scroll

    /** See [MotionAnswers.dismiss]. */
    val dismiss: MotionRole
        @Composable @ReadOnlyComposable
        get() = local.current.dismiss

    /** See [MotionAnswers.lookChange]. */
    val lookChange: MotionRole?
        @Composable @ReadOnlyComposable
        get() = local.current.lookChange

    /** See [MotionAnswers.drift]. */
    val drift: MotionRole?
        @Composable @ReadOnlyComposable
        get() = local.current.drift

    /** See [MotionAnswers.sweep]. */
    val sweep: MotionLoop
        @Composable @ReadOnlyComposable
        get() = local.current.sweep

    /** See [MotionAnswers.ambient]. */
    @Composable
    @ReadOnlyComposable
    fun ambient(periodMs: Int): MotionLoop = local.current.ambient(periodMs)

    /** See [MotionAnswers.arrival]. */
    @Composable
    @ReadOnlyComposable
    fun arrival(entrance: Entrance): Arrival? = local.current.arrival(entrance)

    /** See [MotionAnswers.stagger]. */
    @Composable
    @ReadOnlyComposable
    fun stagger(order: Int): Int = local.current.stagger(order)

    /** See [MotionAnswers.decoration]. */
    @Composable
    @ReadOnlyComposable
    fun decoration(role: MotionRole): MotionRole? = local.current.decoration(role)
}
