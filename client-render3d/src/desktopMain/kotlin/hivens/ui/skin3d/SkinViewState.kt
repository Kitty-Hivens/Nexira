package hivens.ui.skin3d

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

// Hoisted state for SkinView3D: orbit angles plus the pose playback head.
// External surfaces drive the character through this (the login scene turns
// it away while a password field is focused; the profile hero plays an idle
// cycle) without touching the view's internals. Animation lives ONLY here --
// one source of truth -- and the view owns the clock that feeds it.

// Pitch clamp so the model never flips fully upside down on a vertical drag.
private const val PITCH_LIMIT = 1.2f

@Stable
class SkinViewState(
    initialYaw: Float = 0.5f,
    initialPitch: Float = 0.08f,
    initialAnimation: PoseSource? = null,
) {
    internal val animator = PoseAnimator(initialAnimation ?: Pose.IDENTITY.asSource())

    var yaw: Float by mutableFloatStateOf(initialYaw)

    private val pitchState = mutableFloatStateOf(initialPitch.coerceIn(-PITCH_LIMIT, PITCH_LIMIT))
    var pitch: Float
        get() = pitchState.value
        set(value) { pitchState.value = value.coerceIn(-PITCH_LIMIT, PITCH_LIMIT) }

    /** Pose clock, ms. The view's frame loop writes it (scaled by the motion
     *  multiplier); the draw reads it, so a pose change invalidates exactly
     *  the draw and nothing recomposes. */
    internal var timeMs: Long by mutableLongStateOf(0L)

    /** Bumped on every retarget so the view relaunches a frame loop that had
     *  stopped on a settled pose. */
    internal var animationRevision: Int by mutableIntStateOf(0)

    /** Motion scale for the scene's own clock, pushed by the view each
     *  composition. 0 = motion off: retargets snap to their end state. */
    internal var motionMultiplier: Float = 1f

    /** Tween into [animation] from whatever pose is currently on screen. */
    fun play(animation: PoseSource, transitionMs: Long = 280) {
        if (motionMultiplier <= 0f) {
            animator.snapTo(animation)
        } else {
            animator.play(animation, timeMs, transitionMs)
        }
        animationRevision++
    }

    /** Set a static pose immediately, no tween. */
    fun setPose(pose: Pose) {
        animator.snapTo(pose.asSource())
        animationRevision++
    }

    /** Turn the camera to [target] yaw along the shortest arc. Snaps when the
     *  style has motion off. */
    suspend fun animateYawTo(target: Float, durationMs: Int = 450) {
        val start = yaw
        val delta = shortestArc(start, target)
        if (motionMultiplier <= 0f) {
            yaw = start + delta
            return
        }
        val duration = (durationMs * motionMultiplier).toInt().coerceAtLeast(1)
        animate(0f, 1f, animationSpec = tween(duration)) { t, _ -> yaw = start + delta * t }
    }
}

@Composable
fun rememberSkinViewState(
    initialYaw: Float = 0.5f,
    initialPitch: Float = 0.08f,
    initialAnimation: PoseSource? = null,
): SkinViewState = remember { SkinViewState(initialYaw, initialPitch, initialAnimation) }
