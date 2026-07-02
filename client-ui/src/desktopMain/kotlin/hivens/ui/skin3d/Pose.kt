package hivens.ui.skin3d

import hivens.ui.scene3d.Vec3
import kotlin.math.PI

// Pose vocabulary for the skin rig: per-part Euler angles plus a root turn and
// offset. Euler (not quaternions) on purpose -- vanilla Minecraft animates
// exactly these three channels per part, blending is per-channel lerp over
// small preset ranges, and nothing here composes two arbitrary orientations.
// Angles are radians and deliberately unnormalized: additive layering can
// legitimately push a channel past pi (a raised arm plus a wobble cycle), and
// sin/cos downstream do not care. Only [Pose.rootYaw] blends via the shortest
// arc, because TurnAway (pi) sits exactly on the wrap boundary.

enum class BodyPart { Head, Body, RightArm, LeftArm, RightLeg, LeftLeg }

data class PartAngles(val pitch: Float = 0f, val yaw: Float = 0f, val roll: Float = 0f) {
    operator fun plus(o: PartAngles): PartAngles =
        PartAngles(pitch + o.pitch, yaw + o.yaw, roll + o.roll)

    fun lerp(to: PartAngles, t: Float): PartAngles = PartAngles(
        pitch + (to.pitch - pitch) * t,
        yaw + (to.yaw - yaw) * t,
        roll + (to.roll - roll) * t,
    )

    companion object { val ZERO = PartAngles() }
}

data class Pose(
    val head: PartAngles = PartAngles.ZERO,
    val body: PartAngles = PartAngles.ZERO,
    val rightArm: PartAngles = PartAngles.ZERO,
    val leftArm: PartAngles = PartAngles.ZERO,
    val rightLeg: PartAngles = PartAngles.ZERO,
    val leftLeg: PartAngles = PartAngles.ZERO,
    val rootYaw: Float = 0f,
    val rootOffset: Vec3 = Vec3(0f, 0f, 0f),
) {
    operator fun get(part: BodyPart): PartAngles = when (part) {
        BodyPart.Head     -> head
        BodyPart.Body     -> body
        BodyPart.RightArm -> rightArm
        BodyPart.LeftArm  -> leftArm
        BodyPart.RightLeg -> rightLeg
        BodyPart.LeftLeg  -> leftLeg
    }

    /** Component-wise sum -- procedural cycles layer additively over a base pose. */
    operator fun plus(o: Pose): Pose = Pose(
        head = head + o.head,
        body = body + o.body,
        rightArm = rightArm + o.rightArm,
        leftArm = leftArm + o.leftArm,
        rightLeg = rightLeg + o.rightLeg,
        leftLeg = leftLeg + o.leftLeg,
        rootYaw = rootYaw + o.rootYaw,
        rootOffset = Vec3(
            rootOffset.x + o.rootOffset.x,
            rootOffset.y + o.rootOffset.y,
            rootOffset.z + o.rootOffset.z,
        ),
    )

    /** Per-channel linear blend; [rootYaw] takes the shortest arc. */
    fun lerp(to: Pose, t: Float): Pose = Pose(
        head = head.lerp(to.head, t),
        body = body.lerp(to.body, t),
        rightArm = rightArm.lerp(to.rightArm, t),
        leftArm = leftArm.lerp(to.leftArm, t),
        rightLeg = rightLeg.lerp(to.rightLeg, t),
        leftLeg = leftLeg.lerp(to.leftLeg, t),
        rootYaw = rootYaw + shortestArc(rootYaw, to.rootYaw) * t,
        rootOffset = Vec3(
            rootOffset.x + (to.rootOffset.x - rootOffset.x) * t,
            rootOffset.y + (to.rootOffset.y - rootOffset.y) * t,
            rootOffset.z + (to.rootOffset.z - rootOffset.z) * t,
        ),
    )

    companion object { val IDENTITY = Pose() }
}

private const val TWO_PI = (2.0 * PI).toFloat()

/** Signed shortest rotation from [from] to [to], in (-pi, pi]. */
internal fun shortestArc(from: Float, to: Float): Float {
    var d = (to - from) % TWO_PI
    if (d > PI) d -= TWO_PI
    if (d <= -PI) d += TWO_PI
    return d
}

// Sign conventions the presets rely on (see Projection.kt for the axes):
// arm/leg geometry points down (-y) from its pivot, so pitch < 0 swings a limb
// forward toward the viewer (+z); roll < 0 raises the RIGHT arm outward
// (toward -x), roll > 0 the LEFT arm; head pitch > 0 tilts the face downward.
object Poses {
    /** Neutral stand -- identical to the unposed figure. */
    val Stand: Pose = Pose.IDENTITY

    /** Right hand raised high to the side, a touch of head tilt. */
    val Wave: Pose = Pose(
        rightArm = PartAngles(roll = -2.7f),
        leftArm = PartAngles(roll = 0.06f),
        head = PartAngles(roll = -0.08f),
    )

    /** Seated: legs forward a right angle, arms resting slightly ahead. */
    val Sit: Pose = Pose(
        rightLeg = PartAngles(pitch = -(PI / 2).toFloat()),
        leftLeg = PartAngles(pitch = -(PI / 2).toFloat()),
        rightArm = PartAngles(pitch = -0.35f),
        leftArm = PartAngles(pitch = -0.35f),
        rootOffset = Vec3(0f, -8f, 0f),
    )

    /** Back to the viewer -- the "typing a password" turn. */
    val TurnAway: Pose = Pose(rootYaw = PI.toFloat())

    /** Both hands over the face, head dipped -- eyes covered. */
    val FaceCover: Pose = Pose(
        rightArm = PartAngles(pitch = -2.6f, yaw = -0.35f),
        leftArm = PartAngles(pitch = -2.6f, yaw = 0.35f),
        head = PartAngles(pitch = 0.45f),
    )
}
