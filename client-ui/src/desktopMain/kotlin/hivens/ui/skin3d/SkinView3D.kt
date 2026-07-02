package hivens.ui.skin3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import hivens.ui.render3d.Texture
import hivens.ui.render3d.Tri
import hivens.ui.render3d.Vtx
import hivens.ui.render3d.rasterize
import hivens.ui.scene3d.Face
import hivens.ui.scene3d.Vec3
import hivens.ui.scene3d.project
import hivens.ui.scene3d.rotate
import hivens.ui.theme.LocalStyle
import java.awt.image.BufferedImage
import kotlin.math.PI

// Live 3D Minecraft-skin view. Renders the raw skin texture on the box model from
// [buildFigure] using orthographic projection, through the render3d software
// rasterizer: each face becomes two textured triangles drawn with a per-pixel
// depth buffer (NEAREST sampling for crisp texels), so the inner head + the
// alpha-cutout hat/jacket overlay + the coplanar seams order correctly at every
// angle -- no painter's-algorithm see-through. Drag rotates; when idle it auto-spins. The
// rasterized frame is cached (drawWithCache) so a static skin re-rasterizes only
// when its size or texture changes, not every frame. No baked bitmap, no extra dep.

private const val TWO_PI = (2.0 * PI).toFloat()

// Radians of yaw per millisecond when auto-spinning (~one turn per 11s).
private const val SPIN_RATE = 0.00055f

// Pitch clamp so the model never flips fully upside down on a vertical drag.
private const val PITCH_LIMIT = 1.2f

// Depth nudge (model-space z units) that pulls the overlay layer just in front of the
// base so a coplanar seam-flush overlay face wins the depth tie. Tiny next to the 0.5
// overlay inflation, so genuinely separated faces keep their order.
private const val OVERLAY_Z_BIAS = 0.02f

/** How much of the figure to frame: the whole body, or a head-and-torso bust. */
enum class SkinFraming { Full, Bust }

/**
 * @param skin        the raw skin texture (64x64 / 64x32 legacy / HD multiples).
 * @param interactive when true, drag rotates the model and pauses the auto-spin.
 * @param autoSpin    when true, the model slowly turns while not being dragged.
 * @param framing     [SkinFraming.Bust] zooms to head+torso (grid cards); [Full]
 *                    keeps the whole standing figure (the big preview).
 *
 * Slim/Classic and legacy 64x32 are detected from the texture itself.
 */
@Composable
fun SkinView3D(
    skin: ImageBitmap,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    autoSpin: Boolean = true,
    framing: SkinFraming = SkinFraming.Full,
) {
    val legacy = remember(skin) { skin.height <= skin.width / 2 }
    val model = remember(skin) {
        val pixels = skin.toPixelMap()
        guessModel(skin.width, skin.height) { x, y -> (pixels[x, y].alpha * 255f).toInt() }
    }
    val figure = remember(model, legacy) { buildFigure(model, legacy) }
    // Straight-ARGB copy of the skin for the rasterizer's per-texel sampling. Built
    // once per skin via Color.toArgb (so channel order / premultiply match), not the
    // raw PixelMap buffer whose layout is the bitmap's native format.
    val texture = remember(skin) {
        val pm = skin.toPixelMap()
        val arr = IntArray(pm.width * pm.height)
        var i = 0
        for (y in 0 until pm.height) for (x in 0 until pm.width) arr[i++] = pm[x, y].toArgb()
        Texture(arr, pm.width, pm.height)
    }
    // UV rects are in 1x texels; an HD skin (64*k) multiplies them by k.
    val k = remember(skin) { skin.width / 64f }

    // Start with a slight three-quarter turn so the face reads as 3D at rest.
    var yaw by remember { mutableFloatStateOf(0.5f) }
    var pitch by remember { mutableFloatStateOf(0.08f) }
    var dragging by remember { mutableStateOf(false) }

    // Honour the style engine's motion token: Brut sets animationMultiplier = 0
    // ("motion off"), so the idle spin stops there and its speed scales with the
    // multiplier under any custom style. Dragging always works regardless.
    val motion = LocalStyle.current.animationMultiplier
    if (autoSpin && motion > 0f) {
        LaunchedEffect(skin, motion) {
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (last != 0L && !dragging) {
                        yaw = (yaw + (now - last) * SPIN_RATE * motion) % TWO_PI
                    }
                    last = now
                }
            }
        }
    }

    val gestureModifier = if (interactive) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { change, drag ->
                change.consume()
                yaw = (yaw + drag.x * 0.01f) % TWO_PI
                pitch = (pitch - drag.y * 0.01f).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
            }
        }
    } else {
        Modifier
    }

    // drawWithCache re-rasterizes only when the size or a read state (yaw / pitch /
    // figure / texture) changes -- so the auto-spinning hero rebuilds each frame,
    // but a static grid card (fixed yaw/pitch) rasterizes once and just blits.
    Box(
        modifier
            .then(gestureModifier)
            .drawWithCache {
                val w = size.width.toInt()
                val h = size.height.toInt()
                if (w <= 0 || h <= 0) {
                    onDrawBehind { }
                } else {
                    // Full: figure spans ~33 model units tall / ~18 wide once limbs
                    // rotate in; fit to the smaller axis with margin so it never
                    // clips. Bust: zoom in and drop the origin near the bottom so
                    // head+torso fill it and the legs fall off below.
                    val (scale, centerY) = when (framing) {
                        SkinFraming.Full -> minOf(h / 42f, w / 22f) to h / 2f
                        SkinFraming.Bust -> minOf(h / 24f, w / 18f) to h * 0.80f
                    }
                    val tris = facesToTris(figure, yaw, pitch, scale, w / 2f, centerY, k)
                    val bmp = rasterize(tris, texture, w, h).toImageBitmap(w, h)
                    onDrawBehind { drawImage(bmp) }
                }
            },
    )
}

// Projects the figure into textured triangles for the rasterizer: each face's 4
// corners go through rotate/project (screen x,y) with depth = rotated z, and carry
// the face's UV rect (1x texels * k). The implied 4th corner is p0 + (pu-p0) + (pv-p0).
// Every face is emitted DOUBLE-SIDED (no back-face cull): a part is a hollow box, so
// culling the far wall let a cut-out overlay texel (a gap in the hair) show the void
// behind the model. Drawing both sides reveals the inner far wall instead, textured,
// and the depth buffer still keeps the nearest texel per pixel -- so a solid region
// shows only its front faces, while a cut-out reads as solid rather than see-through.
private fun facesToTris(
    faces: List<Face>,
    yaw: Float, pitch: Float, scale: Float, cx: Float, cy: Float, k: Float,
): List<Tri> {
    val tris = ArrayList<Tri>(faces.size * 2)
    for (f in faces) {
        val r0 = rotate(f.p0, yaw, pitch); val s0 = project(r0, scale, cx, cy)
        val ru = rotate(f.pu, yaw, pitch); val su = project(ru, scale, cx, cy)
        val rv = rotate(f.pv, yaw, pitch); val sv = project(rv, scale, cx, cy)
        // No back-face cull -- both sides are drawn (see the function header); the
        // rasterizer normalizes either winding, so a back face keeps its UVs.
        val p3 = Vec3(f.pu.x + f.pv.x - f.p0.x, f.pu.y + f.pv.y - f.p0.y, f.pu.z + f.pv.z - f.p0.z)
        val r3 = rotate(p3, yaw, pitch); val s3 = project(r3, scale, cx, cy)
        val uv = f.uv
        val tu0 = uv.u * k; val tv0 = uv.v * k
        val tu1 = (uv.u + uv.w) * k; val tv1 = (uv.v + uv.h) * k
        // Nudge the overlay a hair nearer so a seam-flush overlay face (the hat bottom
        // kept coplanar with the head bottom) wins the depth tie and draws over the base
        // instead of losing to draw order -- otherwise the hood underside shows the head's
        // texture. Far smaller than the 0.5 inflation, so it never reorders real geometry.
        val zb = if (f.layer) OVERLAY_Z_BIAS else 0f
        val v0 = Vtx(s0.x, s0.y, r0.z + zb, tu0, tv0)    // texture top-left
        val vu = Vtx(su.x, su.y, ru.z + zb, tu1, tv0)    // top-right
        val v3 = Vtx(s3.x, s3.y, r3.z + zb, tu1, tv1)    // bottom-right
        val vv = Vtx(sv.x, sv.y, rv.z + zb, tu0, tv1)    // bottom-left
        // Both layers render as alpha-tested cutout (opaque), matching Minecraft's
        // second layer: a texel is solid or absent, never blended. The overlay is
        // inflated half a texel, so the depth buffer lets it cover the base where
        // solid and reveal it where cut out -- at every angle. Routing the overlay
        // through the translucent pass instead sorted its faces by centroid (the
        // painter's algorithm), so they flickered in and out as the model turned.
        tris.add(Tri(v0, vu, v3, opaque = true))
        tris.add(Tri(v0, v3, vv, opaque = true))
    }
    return tris
}

private fun IntArray.toImageBitmap(w: Int, h: Int): ImageBitmap {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, this, 0, w)
    return img.toComposeImageBitmap()
}
