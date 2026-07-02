package hivens.ui.skin3d

import hivens.ui.render3d.Texture
import hivens.ui.scene3d.Mesh
import hivens.ui.scene3d.Node
import hivens.ui.scene3d.Transform3
import hivens.ui.scene3d.Vec3

// The posable player figure as a two-level scene: a root node carrying the
// whole-figure turn/offset, with one child node per body part (base box +
// overlay box in one mesh, so the hat/jacket/sleeves/pants follow their part
// exactly and the seam-flush overlay planes rotate with it).
//
// Node order is BodyPart.entries (Head, Body, RightArm, LeftArm, RightLeg,
// LeftLeg): the traversal order decides exact depth ties at the coplanar
// seams, and this order keeps every historical tie winner -- Body still draws
// before the limbs abutting it, RightLeg before LeftLeg at the shared x=0
// plane, and overlay-vs-base ties are order-independent (the overlay depth
// bias decides them). Pinned by RenderParityTest.

// Rotation points, model space (Y up, feet y=-16, head top y=+16). Arms use
// vanilla's x = +-5, y = 6 (one texel in from the inner edge, two below the
// shoulder top) for both Classic and Slim; head and body both bend at the
// neck plane; legs swing from the hip plane.
private val PIVOTS: Map<BodyPart, Vec3> = mapOf(
    BodyPart.Head     to Vec3(0f, 8f, 0f),
    BodyPart.Body     to Vec3(0f, 8f, 0f),
    BodyPart.RightArm to Vec3(-5f, 6f, 0f),
    BodyPart.LeftArm  to Vec3(5f, 6f, 0f),
    BodyPart.RightLeg to Vec3(-2f, -4f, 0f),
    BodyPart.LeftLeg  to Vec3(2f, -4f, 0f),
)

private val NO_OFFSET = Vec3(0f, 0f, 0f)

class SkinRig internal constructor(
    val root: Node,
    private val parts: Map<BodyPart, Node>,
) {
    /** The part's node -- the attachment point for accessories (cape on Body). */
    fun node(part: BodyPart): Node = parts.getValue(part)

    /**
     * Writes [pose] into the node transforms. Per part, vanilla's rotation
     * order about the pivot: roll (Z), then yaw (Y), then pitch (X). A zero
     * channel set takes the identity fast path, so the unposed rig renders
     * bit-identical to the flat figure.
     */
    fun apply(pose: Pose) {
        root.transform = if (pose.rootYaw == 0f && pose.rootOffset == NO_OFFSET) {
            Transform3.IDENTITY
        } else {
            Transform3.translate(pose.rootOffset.x, pose.rootOffset.y, pose.rootOffset.z) *
                Transform3.rotateY(pose.rootYaw)
        }
        for ((part, node) in parts) {
            val a = pose[part]
            node.transform = if (a == PartAngles.ZERO) {
                Transform3.IDENTITY
            } else {
                Transform3.aboutPivot(
                    PIVOTS.getValue(part),
                    Transform3.rotateZ(a.roll) * Transform3.rotateY(a.yaw) * Transform3.rotateX(a.pitch),
                )
            }
        }
    }
}

/**
 * Builds the rig for one skin: [texture] is the decoded skin, [uvScale] its
 * HD multiple (width / 64). Starts at the identity pose.
 */
fun buildRig(
    model: SkinModel = SkinModel.Classic,
    legacy: Boolean = false,
    texture: Texture,
    uvScale: Float = 1f,
): SkinRig {
    val boxes = partBoxes(model, legacy)
    val root = Node()
    val parts = LinkedHashMap<BodyPart, Node>()
    for (part in BodyPart.entries) {
        val faces = boxes.getValue(part).flatMap { it.faces() }
        val node = Node(meshes = listOf(Mesh(faces, texture, uvScale)))
        root.attach(node)
        parts[part] = node
    }
    return SkinRig(root, parts)
}
