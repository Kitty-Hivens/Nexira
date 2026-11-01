package hivens.ui.skin3d

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

// Time-driven pose animation. Everything is a deterministic function of time
// (no stateful integrators), so animations are frame-rate independent, freely
// composable and unit-testable: presets tween into cycles, cycles layer over
// presets, and a transition captures whatever pose is currently on screen as
// its frozen start -- structural animation by composition rather than a fixed
// clip enum. The Compose host owns the clock; motion-off simply stops feeding
// new times.

interface PoseSource {
    fun poseAt(tMs: Long): Pose

    /** True when the pose no longer changes with time -- lets the view stop
     *  its frame loop instead of re-rasterizing an unchanged model. */
    val isStatic: Boolean get() = false
}

fun Pose.asSource(): PoseSource = object : PoseSource {
    override fun poseAt(tMs: Long): Pose = this@asSource
    override val isStatic: Boolean get() = true
}

/** Additive sum of [layers] -- a wobble cycle over a raised-arm preset, etc. */
fun layered(vararg layers: PoseSource): PoseSource = object : PoseSource {
    override fun poseAt(tMs: Long): Pose =
        layers.fold(Pose.IDENTITY) { acc, layer -> acc + layer.poseAt(tMs) }
    override val isStatic: Boolean = layers.all { it.isStatic }
}

private const val TWO_PI_F = (2.0 * PI).toFloat()

// Procedural cycles. All are neutral at phase zero, so a motion-off snap or a
// transition landing exactly on a period boundary shows the base pose, not a
// mid-swing frame.
object Cycles {
    private fun phase(tMs: Long, periodMs: Long): Float =
        (tMs % periodMs).toFloat() / periodMs * TWO_PI_F

    /** Gait swing: right arm with left leg, counter-phase to their pair. */
    fun walk(periodMs: Long = 900, swing: Float = 0.7f): PoseSource = object : PoseSource {
        override fun poseAt(tMs: Long): Pose {
            val s = sin(phase(tMs, periodMs)) * swing
            return Pose(
                rightArm = PartAngles(pitch = s),
                leftArm = PartAngles(pitch = -s),
                rightLeg = PartAngles(pitch = -s),
                leftLeg = PartAngles(pitch = s),
            )
        }
    }

    /**
     * Breathing sway with a slow head drift. The drift runs at half the
     * breath rate so the look-around does not metronome with the chest --
     * the source's full period is therefore 2 * [periodMs].
     */
    fun idle(periodMs: Long = 3600, amount: Float = 0.04f): PoseSource = object : PoseSource {
        override fun poseAt(tMs: Long): Pose {
            val p = phase(tMs, periodMs * 2) * 2f   // breath phase; drift = p / 2
            val breathe = sin(p) * amount
            val drift = sin(p * 0.5f) * amount * 2.5f
            return Pose(
                body = PartAngles(pitch = breathe * 0.4f),
                head = PartAngles(yaw = drift, pitch = breathe * 0.5f),
                rightArm = PartAngles(roll = -breathe * 0.6f),
                leftArm = PartAngles(roll = breathe * 0.6f),
            )
        }
    }

    /** Hand wobble to layer over [Poses.Wave]. */
    fun handWave(periodMs: Long = 500, amount: Float = 0.25f): PoseSource = object : PoseSource {
        override fun poseAt(tMs: Long): Pose =
            Pose(rightArm = PartAngles(roll = sin(phase(tMs, periodMs)) * amount))
    }
}

typealias Easing = (Float) -> Float

object Easings {
    val Linear: Easing = { it }

    val EaseInOutCubic: Easing = { t ->
        if (t < 0.5f) 4f * t * t * t
        else 1f - (-2f * t + 2f).let { it * it * it } / 2f
    }

    /**
     * Underdamped spring step response in closed form (no integrator, so the
     * curve is a pure function of t like every other easing). [damping] is the
     * damping ratio in (0, 1); the natural frequency is chosen so the response
     * has settled to within ~0.25% by t = 1 -- callers treat t >= 1 as done,
     * and the residue is far below a visible angle.
     */
    fun spring(damping: Float = 0.55f): Easing {
        val wn = 6f / damping
        val wd = wn * sqrt(1f - damping * damping)
        val tilt = damping / sqrt(1f - damping * damping)
        return { t -> 1f - exp(-damping * wn * t) * (cos(wd * t) + tilt * sin(wd * t)) }
    }
}

/**
 * Blend from [from] into [to] over [startMs, startMs+durMs]; outside the
 * window it passes the corresponding source through untouched (the target
 * exactly, no easing residue).
 */
fun transition(
    from: PoseSource,
    to: PoseSource,
    startMs: Long,
    durMs: Long,
    easing: Easing = Easings.EaseInOutCubic,
): PoseSource = object : PoseSource {
    override fun poseAt(tMs: Long): Pose {
        if (durMs <= 0L || tMs >= startMs + durMs) return to.poseAt(tMs)
        if (tMs <= startMs) return from.poseAt(tMs)
        val t = easing((tMs - startMs).toFloat() / durMs)
        return from.poseAt(tMs).lerp(to.poseAt(tMs), t)
    }
}

/**
 * The playback head a view drives: [play] tweens from whatever pose is
 * currently evaluated (mid-transition included -- the current blend is frozen
 * as the new start, so re-targeting never pops), [snapTo] lands immediately
 * (the motion-off path), and [isSettled] reports when the frame loop may
 * stop: a static target with no tween still running.
 */
class PoseAnimator(initial: PoseSource = Pose.IDENTITY.asSource()) {
    private var current: PoseSource = initial
    private var frozenFrom: Pose? = null
    private var startMs = 0L
    private var durMs = 0L
    private var easing: Easing = Easings.EaseInOutCubic

    fun play(
        next: PoseSource,
        nowMs: Long,
        transitionMs: Long = 280,
        easing: Easing = Easings.EaseInOutCubic,
    ) {
        frozenFrom = poseAt(nowMs)
        current = next
        startMs = nowMs
        durMs = transitionMs
        this.easing = easing
    }

    fun snapTo(next: PoseSource) {
        current = next
        frozenFrom = null
        durMs = 0L
    }

    fun poseAt(nowMs: Long): Pose {
        val target = current.poseAt(nowMs)
        val from = frozenFrom ?: return target
        if (durMs <= 0L || nowMs >= startMs + durMs) return target
        val t = easing(((nowMs - startMs).toFloat() / durMs).coerceIn(0f, 1f))
        return from.lerp(target, t)
    }

    fun isSettled(nowMs: Long): Boolean =
        current.isStatic && (frozenFrom == null || nowMs >= startMs + durMs)
}
