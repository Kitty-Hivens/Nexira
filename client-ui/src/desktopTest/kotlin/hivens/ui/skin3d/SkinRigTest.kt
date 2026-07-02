package hivens.ui.skin3d

import hivens.ui.render3d.Texture
import hivens.ui.scene3d.UvRect
import hivens.ui.scene3d.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkinRigTest {

    private fun tex() = Texture(IntArray(64 * 64) { -0x1 }, width = 64, height = 64)

    private fun rigFaces(rig: SkinRig): List<hivens.ui.scene3d.Face> =
        BodyPart.entries.flatMap { rig.node(it).meshes.single().faces }

    private fun close(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps

    @Test fun `rig carries exactly the flat figure's faces`() {
        for (legacy in listOf(false, true)) {
            for (model in SkinModel.entries) {
                val rig = buildRig(model, legacy, tex())
                assertEquals(
                    buildFigure(model, legacy).toSet(),
                    rigFaces(rig).toSet(),
                    "model=$model legacy=$legacy",
                )
            }
        }
    }

    @Test fun `each part mesh holds its base faces before its overlay faces`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex())
        for (part in BodyPart.entries) {
            val layers = rig.node(part).meshes.single().faces.map { it.layer }
            assertEquals(6, layers.count { !it }, "$part base box")
            assertEquals(6, layers.count { it }, "$part overlay box")
            assertTrue(layers.take(6).none { it } && layers.drop(6).all { it },
                "$part draws base before overlay")
        }
    }

    @Test fun `part rotation keeps its pivot fixed`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex())
        val pivots = mapOf(
            BodyPart.Head to Vec3(0f, 8f, 0f),
            BodyPart.Body to Vec3(0f, 8f, 0f),
            BodyPart.RightArm to Vec3(-5f, 6f, 0f),
            BodyPart.LeftArm to Vec3(5f, 6f, 0f),
            BodyPart.RightLeg to Vec3(-2f, -4f, 0f),
            BodyPart.LeftLeg to Vec3(2f, -4f, 0f),
        )
        val angles = PartAngles(pitch = -0.9f, yaw = 0.6f, roll = 1.3f)
        for ((part, pivot) in pivots) {
            rig.apply(
                Pose(
                    head = if (part == BodyPart.Head) angles else PartAngles.ZERO,
                    body = if (part == BodyPart.Body) angles else PartAngles.ZERO,
                    rightArm = if (part == BodyPart.RightArm) angles else PartAngles.ZERO,
                    leftArm = if (part == BodyPart.LeftArm) angles else PartAngles.ZERO,
                    rightLeg = if (part == BodyPart.RightLeg) angles else PartAngles.ZERO,
                    leftLeg = if (part == BodyPart.LeftLeg) angles else PartAngles.ZERO,
                ),
            )
            val mapped = rig.node(part).transform.apply(pivot)
            assertTrue(
                close(mapped.x, pivot.x) && close(mapped.y, pivot.y) && close(mapped.z, pivot.z),
                "$part pivot moved to $mapped",
            )
        }
    }

    @Test fun `overlay follows its base rigidly under posing`() {
        // Hat bottom (UV 48,8,8,-8) and head bottom (UV 16,8,8,-8) share the
        // head node, so one transform moves both: the distance between their
        // centres must survive any head rotation (rigid motion).
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex())
        val faces = rig.node(BodyPart.Head).meshes.single().faces
        val headBottom = faces.single { !it.layer && it.uv == UvRect(16f, 8f, 8f, -8f) }
        val hatBottom = faces.single { it.layer && it.uv == UvRect(48f, 8f, 8f, -8f) }

        fun center(f: hivens.ui.scene3d.Face, t: hivens.ui.scene3d.Transform3): Vec3 {
            val p0 = t.apply(f.p0); val pu = t.apply(f.pu); val pv = t.apply(f.pv)
            return Vec3(
                p0.x + (pu.x - p0.x + pv.x - p0.x) * 0.5f,
                p0.y + (pu.y - p0.y + pv.y - p0.y) * 0.5f,
                p0.z + (pu.z - p0.z + pv.z - p0.z) * 0.5f,
            )
        }
        fun dist(a: Vec3, b: Vec3): Float {
            val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
            return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        }

        rig.apply(Pose.IDENTITY)
        val restDist = dist(
            center(headBottom, rig.node(BodyPart.Head).transform),
            center(hatBottom, rig.node(BodyPart.Head).transform),
        )
        rig.apply(Pose(head = PartAngles(pitch = 0.8f, yaw = -0.5f, roll = 0.3f)))
        val posedDist = dist(
            center(headBottom, rig.node(BodyPart.Head).transform),
            center(hatBottom, rig.node(BodyPart.Head).transform),
        )
        assertTrue(close(restDist, posedDist), "rest=$restDist posed=$posedDist")
    }

    @Test fun `root pose turns and offsets the whole figure`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex())
        rig.apply(Pose(rootYaw = (Math.PI / 2).toFloat(), rootOffset = Vec3(0f, 5f, 0f)))
        // +z rotates to +x under yaw 90, then the offset lifts by 5.
        val p = rig.root.transform.apply(Vec3(0f, 0f, 1f))
        assertTrue(close(p.x, 1f) && close(p.y, 5f) && close(p.z, 0f), "p=$p")
    }

    @Test fun `identity pose resets transforms to the identity fast path`() {
        val rig = buildRig(SkinModel.Classic, legacy = false, texture = tex())
        rig.apply(Poses.Wave)
        rig.apply(Pose.IDENTITY)
        assertEquals(hivens.ui.scene3d.Transform3.IDENTITY, rig.root.transform)
        for (part in BodyPart.entries) {
            assertEquals(hivens.ui.scene3d.Transform3.IDENTITY, rig.node(part).transform, "$part")
        }
    }

    @Test fun `slim rig narrows the arms`() {
        fun armFrontWidth(model: SkinModel): Float {
            val rig = buildRig(model, legacy = false, texture = tex())
            return rig.node(BodyPart.RightArm).meshes.single()
                .faces.first { it.uv.u == 44f && it.uv.v == 20f }.uv.w
        }
        assertEquals(4f, armFrontWidth(SkinModel.Classic))
        assertEquals(3f, armFrontWidth(SkinModel.Slim))
    }
}
