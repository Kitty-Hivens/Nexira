package hivens.ui.skin3d

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.pointerInput
import hivens.ui.render3d.Texture
import hivens.ui.scene3d.OrthoCamera
import hivens.ui.scene3d.Scene3DState
import hivens.ui.scene3d.Scene3DView
import hivens.ui.theme.LocalStyle
import kotlin.math.PI

// Live 3D Minecraft-skin view. Builds the posable rig from [buildRig] and
// hosts it in a Scene3DView: the scene traversal (double-sided alpha-tested
// cutout emission with the overlay depth bias) plus the depth-buffered
// rasterizer keep the inner head + hat/jacket overlays + coplanar seams
// ordered at every angle. Drag rotates; the hoisted [SkinViewState] carries
// the orbit angles and the pose playback head, so surfaces can turn the model
// or play pose animations from outside. The frame is cached and re-renders
// only when a snapshot input changes: yaw, pitch, or the pose clock -- a
// static pose at a fixed angle rasterizes once.

private const val TWO_PI = (2.0 * PI).toFloat()

// Radians of yaw per millisecond when auto-spinning (~one turn per 11s).
private const val SPIN_RATE = 0.00055f

/** How much of the figure to frame: the whole body, or a head-and-torso bust. */
enum class SkinFraming { Full, Bust }

/**
 * @param skin        the raw skin texture (64x64 / 64x32 legacy / HD multiples).
 * @param interactive when true, drag rotates the model and pauses the auto-spin.
 * @param autoSpin    when true, the model slowly turns while not being dragged.
 * @param framing     [SkinFraming.Bust] zooms to head+torso (grid cards); [Full]
 *                    keeps the whole standing figure (the big preview).
 * @param state       hoisted orbit + pose state; pass your own to drive the
 *                    model (SkinViewState.play / animateYawTo) from outside.
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
    state: SkinViewState = rememberSkinViewState(),
) {
    val legacy = remember(skin) { skin.height <= skin.width / 2 }
    val model = remember(skin) {
        val pixels = skin.toPixelMap()
        guessModel(skin.width, skin.height) { x, y -> (pixels[x, y].alpha * 255f).toInt() }
    }
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
    val rig = remember(skin) { buildRig(model, legacy, texture, uvScale = skin.width / 64f) }
    val scene = remember(rig) { Scene3DState(rig.root) }

    var dragging by remember { mutableStateOf(false) }

    // Honour the style engine's motion token: Brut sets animationMultiplier = 0
    // ("motion off"), so the idle spin stops and pose retargets snap to their
    // end state. Dragging always works regardless.
    val motion = LocalStyle.current.animationMultiplier
    SideEffect { state.motionMultiplier = motion }

    // One frame loop drives both the auto-spin and the pose clock. It exits as
    // soon as nothing needs frames (static settled pose, no spin) and is
    // relaunched by the animationRevision bump of the next play()/setPose() --
    // so a Bust grid card or an idle static hero costs zero per-frame work.
    // The settled checks read state.timeMs inside the effect, not composition,
    // so advancing time never recomposes -- it only invalidates the draw.
    if (motion > 0f) {
        LaunchedEffect(skin, motion, autoSpin, state.animationRevision) {
            if (!autoSpin && state.animator.isSettled(state.timeMs)) return@LaunchedEffect
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (last != 0L) {
                        val delta = now - last
                        if (autoSpin && !dragging) {
                            state.yaw = (state.yaw + delta * SPIN_RATE * motion) % TWO_PI
                        }
                        if (!state.animator.isSettled(state.timeMs)) {
                            state.timeMs += (delta * motion).toLong()
                        }
                    }
                    last = now
                }
                if (!autoSpin && state.animator.isSettled(state.timeMs)) break
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
                state.yaw = (state.yaw + drag.x * 0.01f) % TWO_PI
                state.pitch += -drag.y * 0.01f
            }
        }
    } else {
        Modifier
    }

    // The scene host draws; SkinViewState stays the single orbit/pose source
    // of truth, so the built-in scene camera controls are off and the camera
    // reads this state instead (snapshot reads inside cameraFor/prepareFrame
    // invalidate the draw like any other).
    Scene3DView(
        state = scene,
        modifier = modifier.then(gestureModifier),
        interactive = false,
        prepareFrame = { rig.apply(state.animator.poseAt(state.timeMs)) },
        cameraFor = { w, h ->
            // Full: figure spans ~33 model units tall / ~18 wide once limbs
            // rotate in; fit to the smaller axis with margin so it never
            // clips. Bust: zoom in and drop the origin near the bottom so
            // head+torso fill it and the legs fall off below.
            val (scale, centerY) = when (framing) {
                SkinFraming.Full -> minOf(h / 42f, w / 22f) to h / 2f
                SkinFraming.Bust -> minOf(h / 24f, w / 18f) to h * 0.80f
            }
            OrthoCamera(state.yaw, state.pitch, scale, w / 2f, centerY)
        },
    )
}
