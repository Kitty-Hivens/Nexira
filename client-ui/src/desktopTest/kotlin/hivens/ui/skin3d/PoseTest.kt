package hivens.ui.skin3d

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PoseTest {

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps

    private val sample = Pose(
        head = PartAngles(pitch = 0.4f, yaw = -0.2f),
        rightArm = PartAngles(roll = -2.7f),
        leftLeg = PartAngles(pitch = 0.6f),
        rootYaw = 0.5f,
    )

    @Test fun `get maps every part to its channel`() {
        assertEquals(sample.head, sample[BodyPart.Head])
        assertEquals(sample.rightArm, sample[BodyPart.RightArm])
        assertEquals(sample.leftLeg, sample[BodyPart.LeftLeg])
        assertEquals(PartAngles.ZERO, sample[BodyPart.Body])
    }

    @Test fun `identity is neutral for layering`() {
        assertEquals(sample, sample + Pose.IDENTITY)
        assertEquals(sample, Pose.IDENTITY + sample)
    }

    @Test fun `plus sums channels component-wise`() {
        val wobble = Pose(rightArm = PartAngles(roll = 0.25f), rootYaw = 0.1f)
        val sum = sample + wobble
        assertTrue(close(sum.rightArm.roll, -2.45f))
        assertTrue(close(sum.rootYaw, 0.6f))
        assertEquals(sample.head, sum.head)
    }

    @Test fun `lerp hits both endpoints and the midpoint`() {
        val to = Pose(head = PartAngles(pitch = 1.2f), rootYaw = 1.5f)
        assertEquals(sample, sample.lerp(to, 0f))
        assertEquals(to, sample.lerp(to, 1f))
        val mid = sample.lerp(to, 0.5f)
        assertTrue(close(mid.head.pitch, 0.8f))
        assertTrue(close(mid.head.yaw, -0.1f))
        assertTrue(close(mid.rightArm.roll, -1.35f))
        assertTrue(close(mid.rootYaw, 1.0f))
    }

    @Test fun `rootYaw blends along the shortest arc across the wrap`() {
        // 3.0 -> -3.0 directly would swing -6.0 rad; the short way is +0.283.
        val from = Pose(rootYaw = 3.0f)
        val to = Pose(rootYaw = -3.0f)
        val mid = from.lerp(to, 0.5f)
        assertTrue(close(mid.rootYaw, 3.0f + 0.28318530f / 2f, eps = 1e-3f), "rootYaw=${mid.rootYaw}")
    }

    @Test fun `shortestArc stays in the half-open pi range`() {
        assertTrue(close(shortestArc(0f, PI.toFloat()), PI.toFloat()))
        assertTrue(close(shortestArc(PI.toFloat(), 0f), -PI.toFloat()) || close(shortestArc(PI.toFloat(), 0f), PI.toFloat()))
        assertTrue(close(shortestArc(-0.1f, 0.1f), 0.2f))
        assertTrue(close(shortestArc(0.1f, -0.1f), -0.2f))
    }

    @Test fun `presets carry their defining structure`() {
        assertEquals(Pose.IDENTITY, Poses.Stand)
        assertTrue(kotlin.math.abs(Poses.Wave.rightArm.roll) > 2.5f, "wave raises the right arm high")
        assertTrue(close(Poses.Sit.rightLeg.pitch, -(PI / 2).toFloat()))
        assertTrue(close(Poses.Sit.leftLeg.pitch, -(PI / 2).toFloat()))
        assertTrue(close(Poses.TurnAway.rootYaw, PI.toFloat()))
        assertTrue(Poses.FaceCover.rightArm.pitch < -2f && Poses.FaceCover.leftArm.pitch < -2f,
            "face cover raises both arms forward-up")
    }
}
