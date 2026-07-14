package hivens.ui.skin3d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoseAnimationTest {

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps
    private fun assertPoseClose(a: Pose, b: Pose, eps: Float = 1e-4f) {
        for (part in BodyPart.entries) {
            val pa = a[part]; val pb = b[part]
            assertTrue(
                close(pa.pitch, pb.pitch, eps) && close(pa.yaw, pb.yaw, eps) && close(pa.roll, pb.roll, eps),
                "$part: $pa vs $pb",
            )
        }
        assertTrue(close(a.rootYaw, b.rootYaw, eps), "rootYaw ${a.rootYaw} vs ${b.rootYaw}")
    }

    // ── sources + layering ───────────────────────────────────────────────────

    @Test fun `a pose as a source is static and constant`() {
        val src = Poses.Wave.asSource()
        assertTrue(src.isStatic)
        assertEquals(Poses.Wave, src.poseAt(0))
        assertEquals(Poses.Wave, src.poseAt(123456))
    }

    @Test fun `layered sums poses and is static only when all layers are`() {
        val sum = layered(Poses.Wave.asSource(), Cycles.handWave(periodMs = 400, amount = 0.2f))
        assertFalse(sum.isStatic)
        assertTrue(layered(Poses.Wave.asSource(), Poses.Sit.asSource()).isStatic)
        // Quarter period: wobble at its positive peak, layered on the preset.
        val p = sum.poseAt(100)
        assertTrue(close(p.rightArm.roll, Poses.Wave.rightArm.roll + 0.2f), "roll=${p.rightArm.roll}")
        assertEquals(Poses.Wave.leftArm, p.leftArm, "untouched channels pass through")
    }

    // ── cycles ───────────────────────────────────────────────────────────────

    @Test fun `walk is neutral at phase zero and periodic`() {
        val walk = Cycles.walk(periodMs = 800, swing = 0.5f)
        assertPoseClose(Pose.IDENTITY, walk.poseAt(0))
        assertPoseClose(walk.poseAt(137), walk.poseAt(137 + 800))
    }

    @Test fun `walk swings right arm with the left leg, counter-phase to their pair`() {
        val walk = Cycles.walk(periodMs = 800, swing = 0.5f)
        val p = walk.poseAt(200)   // quarter period: peak swing
        assertTrue(close(p.rightArm.pitch, 0.5f))
        assertTrue(close(p.leftLeg.pitch, 0.5f), "left leg moves with the right arm")
        assertTrue(close(p.leftArm.pitch, -0.5f))
        assertTrue(close(p.rightLeg.pitch, -0.5f))
    }

    @Test fun `idle is neutral at phase zero and periodic over two base periods`() {
        val idle = Cycles.idle(periodMs = 1000, amount = 0.05f)
        assertPoseClose(Pose.IDENTITY, idle.poseAt(0))
        // The head drift runs at half the breath rate, so the full cycle is 2s.
        assertPoseClose(idle.poseAt(333), idle.poseAt(333 + 2000))
        // Mid-breath actually moves something.
        assertFalse(close(idle.poseAt(250).body.pitch, 0f), "breath displaces the body")
    }

    @Test fun `handWave returns to neutral each period`() {
        val wave = Cycles.handWave(periodMs = 500, amount = 0.3f)
        assertPoseClose(Pose.IDENTITY, wave.poseAt(0))
        assertPoseClose(Pose.IDENTITY, wave.poseAt(500))
        assertTrue(close(wave.poseAt(125).rightArm.roll, 0.3f))
    }

    // ── easings ──────────────────────────────────────────────────────────────

    @Test fun `easings start at zero and settle at one`() {
        assertTrue(close(Easings.Linear(0f), 0f) && close(Easings.Linear(1f), 1f))
        assertTrue(close(Easings.EaseInOutCubic(0f), 0f))
        assertTrue(close(Easings.EaseInOutCubic(1f), 1f))
        assertTrue(close(Easings.EaseInOutCubic(0.5f), 0.5f))
        val spring = Easings.spring()
        assertTrue(close(spring(0f), 0f))
        assertTrue(kotlin.math.abs(spring(1f) - 1f) < 0.01f, "spring(1)=${spring(1f)}")
    }

    @Test fun `the spring overshoots like a spring`() {
        val spring = Easings.spring(damping = 0.4f)
        val peak = (0..100).maxOf { spring(it / 100f) }
        assertTrue(peak > 1.05f, "peak=$peak")
    }

    // ── transition ───────────────────────────────────────────────────────────

    @Test fun `transition passes sources through outside its window and blends inside`() {
        val a = Pose(head = PartAngles(pitch = 1f)).asSource()
        val b = Pose(head = PartAngles(pitch = 2f)).asSource()
        val tr = transition(a, b, startMs = 1000, durMs = 400, easing = Easings.Linear)
        assertTrue(close(tr.poseAt(0).head.pitch, 1f))
        assertTrue(close(tr.poseAt(1000).head.pitch, 1f))
        assertTrue(close(tr.poseAt(1200).head.pitch, 1.5f))
        assertTrue(close(tr.poseAt(1400).head.pitch, 2f))
        assertTrue(close(tr.poseAt(99999).head.pitch, 2f))
    }

    // ── animator ─────────────────────────────────────────────────────────────

    @Test fun `animator tweens into the target and settles`() {
        val animator = PoseAnimator(Pose(head = PartAngles(pitch = 1f)).asSource())
        animator.play(Pose(head = PartAngles(pitch = 2f)).asSource(), nowMs = 1000, transitionMs = 200, easing = Easings.Linear)
        assertTrue(close(animator.poseAt(1000).head.pitch, 1f), "starts at the captured pose")
        assertTrue(close(animator.poseAt(1100).head.pitch, 1.5f))
        assertTrue(close(animator.poseAt(1200).head.pitch, 2f))
        assertFalse(animator.isSettled(1100))
        assertTrue(animator.isSettled(1200))
    }

    @Test fun `re-play mid-transition continues from the evaluated pose without a pop`() {
        val animator = PoseAnimator(Pose(head = PartAngles(pitch = 0f)).asSource())
        animator.play(Pose(head = PartAngles(pitch = 2f)).asSource(), nowMs = 0, transitionMs = 400, easing = Easings.Linear)
        val mid = animator.poseAt(200).head.pitch   // 1.0 halfway up
        animator.play(Pose(head = PartAngles(pitch = -1f)).asSource(), nowMs = 200, transitionMs = 400, easing = Easings.Linear)
        assertTrue(close(animator.poseAt(200).head.pitch, mid), "retarget starts where the blend was")
        assertTrue(close(animator.poseAt(600).head.pitch, -1f))
    }

    @Test fun `snapTo lands immediately and settles`() {
        val animator = PoseAnimator()
        animator.play(Poses.Wave.asSource(), nowMs = 0, transitionMs = 300)
        animator.snapTo(Poses.Sit.asSource())
        assertEquals(Poses.Sit, animator.poseAt(50))
        assertTrue(animator.isSettled(50))
    }

    @Test fun `a cyclic target never settles`() {
        val animator = PoseAnimator()
        animator.play(Cycles.walk(), nowMs = 0, transitionMs = 100)
        assertFalse(animator.isSettled(10_000))
    }
}
