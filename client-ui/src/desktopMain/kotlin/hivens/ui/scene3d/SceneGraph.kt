package hivens.ui.scene3d

import hivens.ui.render3d.Texture
import hivens.ui.render3d.Tri
import hivens.ui.render3d.TriBatch
import hivens.ui.render3d.Vtx

// A minimal mutable scene graph over the shared geometry: nodes carry a local
// Transform3 and meshes, a traversal flattens the tree into screen-space
// triangle batches for the rasterizer. Deliberately plain Kotlin (no snapshot
// state): the Compose host reads a handful of snapshot inputs and re-runs the
// traversal in its draw block; nothing here needs to invalidate composition.

/**
 * Faces sampling one [texture]. [uvScale] converts the faces' 1x-texel UV
 * rects to the texture's native texels (a 64*k HD skin passes k).
 */
class Mesh(
    val faces: List<Face>,
    val texture: Texture,
    val uvScale: Float = 1f,
)

/**
 * A scene node: local [transform], [meshes] drawn in this node's space, and
 * children inheriting the accumulated transform. Geometry may be authored
 * already-placed (transform stays identity; the skin rig bakes its boxes in
 * model space) or pivot-local and placed by the transform (the cape).
 */
class Node(
    var transform: Transform3 = Transform3.IDENTITY,
    meshes: List<Mesh> = emptyList(),
) {
    val meshes: MutableList<Mesh> = meshes.toMutableList()

    var parent: Node? = null
        private set
    private val _children = mutableListOf<Node>()
    val children: List<Node> get() = _children

    /** Adds [child], reparenting it away from any current parent. */
    fun attach(child: Node) {
        require(child !== this && !hasAncestor(child)) { "attach would create a cycle" }
        child.parent?._children?.remove(child)
        child.parent = this
        _children.add(child)
    }

    fun detach(child: Node) {
        if (_children.remove(child)) child.parent = null
    }

    private fun hasAncestor(candidate: Node): Boolean {
        var n: Node? = this
        while (n != null) {
            if (n === candidate) return true
            n = n.parent
        }
        return false
    }
}

// Depth nudge (view-space z units) that pulls overlay-layer faces just in
// front of the base so a coplanar seam-flush overlay face wins the depth tie.
// Tiny next to the 0.5 overlay inflation, so genuinely separated faces keep
// their order.
private const val OVERLAY_Z_BIAS = 0.02f

/**
 * Flattens [root] into rasterizer batches for one frame: accumulates node
 * transforms depth-first, view-rotates by [yaw]/[pitch] (the sequential
 * [rotate] form, not a composed matrix -- see Projection.kt on why parity
 * matters), orthographic-projects, and emits two triangles per face. Batches
 * group by texture identity in first-seen order; within a batch triangles
 * keep traversal order, which decides exact depth ties the same way the flat
 * emission always has.
 *
 * Every face is emitted DOUBLE-SIDED (no back-face cull): a part is a hollow
 * box, so culling the far wall would let a cut-out overlay texel (a gap in
 * the hair) show the void behind the model. Drawing both sides reveals the
 * inner far wall instead, textured, and the depth buffer still keeps the
 * nearest texel per pixel -- so a solid region shows only its front faces,
 * while a cut-out reads as solid rather than see-through.
 *
 * Both layers render as alpha-tested cutout (opaque), matching Minecraft's
 * second layer: a texel is solid or absent, never blended. The overlay is
 * inflated half a texel, so the depth buffer lets it cover the base where
 * solid and reveal it where cut out -- at every angle. [OVERLAY_Z_BIAS] keeps
 * the seam-flush overlay faces (kept coplanar with their base at the neck /
 * hips) winning the depth tie instead of losing to draw order.
 */
fun collectTriBatches(
    root: Node,
    yaw: Float,
    pitch: Float,
    scale: Float,
    centerX: Float,
    centerY: Float,
): List<TriBatch> {
    val batches = LinkedHashMap<Texture, MutableList<Tri>>()

    fun visit(node: Node, parentWorld: Transform3) {
        val world = parentWorld * node.transform
        for (mesh in node.meshes) {
            val tris = batches.getOrPut(mesh.texture) { ArrayList() }
            val k = mesh.uvScale
            for (f in mesh.faces) {
                val w0 = world.apply(f.p0)
                val wu = world.apply(f.pu)
                val wv = world.apply(f.pv)
                // Implied 4th corner in world space; affine transforms keep the
                // quad a parallelogram, so this equals transforming the model-
                // space implied corner.
                val w3 = Vec3(wu.x + wv.x - w0.x, wu.y + wv.y - w0.y, wu.z + wv.z - w0.z)

                val r0 = rotate(w0, yaw, pitch); val s0 = project(r0, scale, centerX, centerY)
                val ru = rotate(wu, yaw, pitch); val su = project(ru, scale, centerX, centerY)
                val rv = rotate(wv, yaw, pitch); val sv = project(rv, scale, centerX, centerY)
                val r3 = rotate(w3, yaw, pitch); val s3 = project(r3, scale, centerX, centerY)

                val uv = f.uv
                val tu0 = uv.u * k; val tv0 = uv.v * k
                val tu1 = (uv.u + uv.w) * k; val tv1 = (uv.v + uv.h) * k
                val zb = if (f.layer) OVERLAY_Z_BIAS else 0f
                val v0 = Vtx(s0.x, s0.y, r0.z + zb, tu0, tv0)    // texture top-left
                val vu = Vtx(su.x, su.y, ru.z + zb, tu1, tv0)    // top-right
                val v3 = Vtx(s3.x, s3.y, r3.z + zb, tu1, tv1)    // bottom-right
                val vv = Vtx(sv.x, sv.y, rv.z + zb, tu0, tv1)    // bottom-left
                tris.add(Tri(v0, vu, v3, opaque = true))
                tris.add(Tri(v0, v3, vv, opaque = true))
            }
        }
        for (child in node.children) visit(child, world)
    }

    visit(root, Transform3.IDENTITY)
    return batches.map { (texture, tris) -> TriBatch(tris, texture) }
}
