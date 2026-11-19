package hivens.look.motion.api

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween as tweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp

/**
 * One answer of the motion axis for a movement that starts and ends: how it runs, and how something
 * arriving or leaving by it comes and goes.
 *
 * Usable as it is wherever a `FiniteAnimationSpec<Float>` is asked for, and through [of] for any other
 * type. A provider answers a tween with [tween], or a movement of its own, a spring say, by extending
 * this class: a role says what it runs for and never which curve.
 */
@Immutable
abstract class MotionRole : FiniteAnimationSpec<Float> {

    /**
     * About how long it runs, in milliseconds: what something that follows it waits for. A tween runs
     * exactly this long, a spring settles in about this long.
     */
    abstract val durationMs: Int

    /** The movement for any animatable type, started [delayMs] milliseconds late. */
    abstract fun <T> of(delayMs: Int = 0): FiniteAnimationSpec<T>

    /** How something arrives by this role, growing or shrinking about [origin] where it does. */
    abstract fun enter(origin: TransformOrigin): EnterTransition

    /** How something leaves by this role, growing or shrinking about [origin] where it does. */
    abstract fun exit(origin: TransformOrigin): ExitTransition

    /** How something arrives by this role, about its centre. */
    val enter: EnterTransition get() = enter(TransformOrigin.Center)

    /** How something leaves by this role, about its centre. */
    val exit: ExitTransition get() = exit(TransformOrigin.Center)

    override fun <V : AnimationVector> vectorize(converter: TwoWayConverter<Float, V>): VectorizedFiniteAnimationSpec<V> =
        of<Float>().vectorize(converter)

    companion object {
        /**
         * A role that runs [durationMs] milliseconds on [easing], arriving by [enter] and leaving by
         * [exit], each handed the role and the origin. A fade on the role itself where not given.
         */
        fun tween(
            durationMs: Int,
            easing: Easing,
            enter: (MotionRole, TransformOrigin) -> EnterTransition = { role, _ -> fadeIn(role.of()) },
            exit: (MotionRole, TransformOrigin) -> ExitTransition = { role, _ -> fadeOut(role.of()) },
        ): MotionRole = TweenRole(durationMs, easing, enter, exit)
    }
}

private class TweenRole(
    override val durationMs: Int,
    private val easing: Easing,
    private val enterOf: (MotionRole, TransformOrigin) -> EnterTransition,
    private val exitOf: (MotionRole, TransformOrigin) -> ExitTransition,
) : MotionRole() {
    override fun <T> of(delayMs: Int): FiniteAnimationSpec<T> = tweenSpec(durationMs, delayMs, easing)
    override fun enter(origin: TransformOrigin): EnterTransition = enterOf(this, origin)
    override fun exit(origin: TransformOrigin): ExitTransition = exitOf(this, origin)
    override fun toString(): String = "MotionRole(${durationMs}ms, $easing)"
}

/**
 * One answer of the motion axis for a movement that repeats: one period of it, which the caller
 * repeats as it needs. Always a fixed period, since a loop is timed by its length.
 */
@Immutable
abstract class MotionLoop {

    /** One period, in milliseconds. */
    abstract val periodMs: Int

    /** One period for any animatable type, with [gapMs] milliseconds of rest before it. */
    abstract fun <T> of(gapMs: Int = 0): DurationBasedAnimationSpec<T>

    companion object {
        /** A loop of [periodMs] milliseconds on [easing]. */
        fun tween(periodMs: Int, easing: Easing): MotionLoop = TweenLoop(periodMs, easing)
    }
}

private class TweenLoop(override val periodMs: Int, private val easing: Easing) : MotionLoop() {
    override fun <T> of(gapMs: Int): DurationBasedAnimationSpec<T> = tweenSpec(periodMs, gapMs, easing)
    override fun toString(): String = "MotionLoop(${periodMs}ms, $easing)"
}

/**
 * What a widget's arrival means on this look: the movement it runs on ([role]), how far it travels up
 * into place ([travel]) and the share of its size it grows from ([fromScale]). Zero travel and a scale
 * of one leave only the fade.
 */
@Immutable
class Arrival(
    val role: MotionRole,
    val travel: Dp,
    val fromScale: Float,
) {
    override fun equals(other: Any?): Boolean =
        other is Arrival && other.role == role && other.travel == travel && other.fromScale == fromScale

    override fun hashCode(): Int = listOf(role, travel, fromScale).hashCode()

    override fun toString(): String = "Arrival($role, travel=$travel, fromScale=$fromScale)"
}
