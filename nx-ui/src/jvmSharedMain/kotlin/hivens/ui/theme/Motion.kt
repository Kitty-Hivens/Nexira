package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.motion.api.Arrival
import hivens.look.motion.api.Motion as MotionAxis
import hivens.widget.model.Entrance

/** A role of the motion axis, see [hivens.look.motion.api.MotionRole]. */
typealias MotionRole = hivens.look.motion.api.MotionRole

/** A loop of the motion axis, see [hivens.look.motion.api.MotionLoop]. */
typealias MotionLoop = hivens.look.motion.api.MotionLoop

/**
 * The motion axis under the name nx-ui has always asked it by, while the components still live here.
 *
 * A call site asks for what is happening, a panel arriving, content opening, a press answering, and
 * gets what the look answers for it: nx's durations and curves where the nx motion provider draws
 * ([nxMotionAnswers]), the axis's plain base where none does. Reach for the nearest role. A genuinely new
 * kind of movement is a new role of the axis, not a literal at the call site.
 */
object Motion {
    /** See [hivens.look.motion.api.MotionAnswers.tap]. */
    val tap: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.tap

    /** See [hivens.look.motion.api.MotionAnswers.fade]. */
    val fade: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.fade

    /** See [hivens.look.motion.api.MotionAnswers.colourShift]. */
    val colorShift: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.colourShift

    /** See [hivens.look.motion.api.MotionAnswers.panelSlide]. */
    val panelSlide: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.panelSlide

    /** See [hivens.look.motion.api.MotionAnswers.reveal]. */
    val reveal: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.reveal

    /** See [hivens.look.motion.api.MotionAnswers.emphasis]. */
    val emphasis: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.emphasis

    /** See [hivens.look.motion.api.MotionAnswers.track]. */
    val track: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.track

    /** See [hivens.look.motion.api.MotionAnswers.popup]. */
    val popup: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.popup

    /** See [hivens.look.motion.api.MotionAnswers.pageSwap]. */
    val pageSwap: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.pageSwap

    /** See [hivens.look.motion.api.MotionAnswers.scroll]. */
    val scroll: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.scroll

    /** See [hivens.look.motion.api.MotionAnswers.dismiss]. */
    val dismiss: MotionRole
        @Composable @ReadOnlyComposable get() = MotionAxis.dismiss

    /** See [hivens.look.motion.api.MotionAnswers.lookChange]. */
    val lookChange: MotionRole?
        @Composable @ReadOnlyComposable get() = MotionAxis.lookChange

    /** See [hivens.look.motion.api.MotionAnswers.drift]. */
    val drift: MotionRole?
        @Composable @ReadOnlyComposable get() = MotionAxis.drift

    /** See [hivens.look.motion.api.MotionAnswers.sweep]. */
    val sweep: MotionLoop
        @Composable @ReadOnlyComposable get() = MotionAxis.sweep

    /** See [hivens.look.motion.api.MotionAnswers.ambient]. */
    @Composable
    @ReadOnlyComposable
    fun ambient(periodMs: Int): MotionLoop = MotionAxis.ambient(periodMs)

    /** See [hivens.look.motion.api.MotionAnswers.arrival]. */
    @Composable
    @ReadOnlyComposable
    fun arrival(entrance: Entrance): Arrival? = MotionAxis.arrival(entrance)

    /** See [hivens.look.motion.api.MotionAnswers.stagger]. */
    @Composable
    @ReadOnlyComposable
    fun stagger(order: Int): Int = MotionAxis.stagger(order)

    /** See [hivens.look.motion.api.MotionAnswers.decoration]. */
    @Composable
    @ReadOnlyComposable
    fun decoration(role: MotionRole): MotionRole? = MotionAxis.decoration(role)
}
