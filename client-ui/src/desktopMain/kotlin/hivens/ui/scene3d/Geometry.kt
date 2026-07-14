package hivens.ui.scene3d

// Shared geometry vocabulary for the CPU 3D pipeline: the scene graph, the
// skin rig, and the rasterizer feed all speak these types. Pure Kotlin and
// Compose-free so every consumer stays unit-testable without a canvas.

data class Vec3(val x: Float, val y: Float, val z: Float)

/** A 2D point in screen pixels (own type so the core carries no Compose dep). */
data class Pt2(val x: Float, val y: Float)

/** Texture sub-rectangle in 1x texels, top-left origin. */
data class UvRect(val u: Float, val v: Float, val w: Float, val h: Float)

/**
 * One textured quad. [p0] is the corner that maps to the texture rect's
 * top-left (u, v); [pu] maps to the top-right (u+w, v); [pv] maps to the
 * bottom-left (u, v+h). The fourth corner is implied (p0 + (pu-p0) + (pv-p0)),
 * so the quad is always a parallelogram -- exact under orthographic projection,
 * and preserved by any affine transform. [layer] is the paint group: false =
 * base geometry, true = an inflated overlay (hat / jacket / sleeves / pants),
 * which carries transparency and receives the seam-tie depth bias at draw time.
 */
data class Face(val p0: Vec3, val pu: Vec3, val pv: Vec3, val uv: UvRect, val layer: Boolean)
