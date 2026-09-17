package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import hivens.widget.api.rememberProps
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A surface assembled from a beatmap's art, rather than a player of its timeline.
 *
 * The storyboard widget beside this one replays somebody else's authored motion.
 * This one authors its own: the same images become a layered scene that breathes,
 * parallaxes under the pointer, and carries a working clock, which is what a
 * launcher surface would actually want from art like this.
 *
 * It is here for the same reason as its neighbour -- to find where the kernel
 * stops. The drawing needed nothing the kernel does not give. Binding the scene
 * to anything the launcher knows is where it ran out, and the README says so.
 */

@Serializable
data class AltraVitaProps(
    /** Beatmap folder whose `sb/` directory holds the art. */
    val folder: String = "",
    /** How far the layers slide under the pointer, in fractions of the surface. */
    val parallax: Float = 0.02f,
    /** Breathing rate of the glow, in seconds per cycle. */
    val breathSeconds: Float = 6f,
    /** Rotate the moon dial with the real clock rather than holding it still. */
    val liveClock: Boolean = true,
    /** Scanline and vignette overlays. */
    val overlays: Boolean = true,
)

/** One image plus how strongly it answers the pointer. Back layers move least. */
internal class Plate(
    val image: ImageBitmap,
    val depth: Float,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val additive: Boolean = false,
    val cover: Boolean = false,
)

internal class Scene(
    val back: List<Plate>,
    val figure: Plate?,
    val dial: Plate?,
    val piece: Plate?,
    val glow: Plate?,
    val overlays: List<Plate>,
)

private fun pick(dir: File, vararg names: String): ImageBitmap? {
    for (n in names) {
        val f = File(dir, n)
        if (f.isFile) {
            runCatching {
                return org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
            }
        }
    }
    return null
}

internal suspend fun loadScene(folder: String): Scene? = withContext(Dispatchers.IO) {
    val sb = File(folder, "sb")
    if (!sb.isDirectory) return@withContext null
    val back = listOfNotNull(
        pick(sb, "glowbg.png")?.let { Plate(it, depth = 0.15f, alpha = 0.30f, cover = true) },
        pick(sb, "grid.png")?.let { Plate(it, depth = 0.35f, alpha = 0.12f, additive = true, cover = true) },
        pick(sb, "chess_bw.png")?.let { Plate(it, depth = 0.5f, alpha = 0.14f, cover = true) },
    )
    val overlays = listOfNotNull(
        pick(sb, "tvline.jpg")?.let { Plate(it, depth = 0f, alpha = 0.06f, additive = true, cover = true) },
        pick(sb, "vignette.png")?.let { Plate(it, depth = 0f, alpha = 0.9f, cover = true) },
        pick(sb, "border.png")?.let { Plate(it, depth = 0f, alpha = 0.5f, cover = true) },
    )
    Scene(
        back = back,
        figure = pick(sb, "chara.png", "charabw.png")?.let { Plate(it, depth = 1.0f, scale = 0.92f) },
        dial = pick(sb, "moon_clock.png")?.let { Plate(it, depth = 0.7f, scale = 0.55f, alpha = 0.9f) },
        piece = pick(sb, "chess_solid.png", "chess_glow.png")?.let { Plate(it, depth = 1.4f, scale = 0.30f) },
        glow = pick(sb, "light.png")?.let { Plate(it, depth = 1.4f, scale = 0.7f, additive = true) },
        overlays = if (back.isEmpty()) emptyList() else overlays,
    )
}

@Widget(
    id = "osusb.altravita",
    displayName = "osusb.altravita",
    propsClass = AltraVitaProps::class,
    surface = """{"fill":"","padding":{"all":0.0}}""",
    drawsOwnSurface = true,
)
@Composable
fun AltraVitaSurfaceWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<AltraVitaProps>()
    var scene by remember(props.folder) { mutableStateOf<Scene?>(null) }
    var pointer by remember { mutableStateOf(Offset.Unspecified) }
    var phase by remember { mutableFloatStateOf(0f) }
    var clockTurn by remember { mutableLongStateOf(0L) }

    LaunchedEffect(props.folder) {
        scene = if (props.folder.isBlank()) null else loadScene(props.folder)
    }

    LaunchedEffect(props.breathSeconds, props.liveClock) {
        val period = (props.breathSeconds.coerceIn(0.5f, 60f) * 1_000_000_000L).toLong()
        var start = 0L
        while (true) {
            withFrameNanos { frame ->
                if (start == 0L) start = frame
                phase = ((frame - start) % period).toFloat() / period
                if (props.liveClock) clockTurn = System.currentTimeMillis()
            }
        }
    }

    val s = scene
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07060A))
            // Observed, never consumed: the scene answers the pointer and
            // everything underneath still gets the event.
            .pointerInput(Unit) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pointer = event.changes.lastOrNull()?.position ?: pointer
                    }
                }
            },
    ) {
        if (s == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            drawScene(s, props, pointer, phase, clockTurn)
        }
    }
}

private fun DrawScope.plate(p: Plate, centre: Offset, shift: Offset, extraScale: Float, alpha: Float) {
    val w = p.image.width.toFloat()
    val h = p.image.height.toFloat()
    val base = if (p.cover) maxOf(size.width / w, size.height / h) else size.height / 480f
    val k = base * p.scale * extraScale
    withTransform({
        translate(centre.x + shift.x, centre.y + shift.y)
        scale(k, k, Offset.Zero)
    }) {
        drawImage(
            image = p.image,
            dstOffset = IntOffset((-w / 2f).toInt(), (-h / 2f).toInt()),
            dstSize = IntSize(p.image.width, p.image.height),
            alpha = (p.alpha * alpha).coerceIn(0f, 1f),
            blendMode = if (p.additive) BlendMode.Plus else BlendMode.SrcOver,
        )
    }
}

internal fun DrawScope.drawScene(
    scene: Scene,
    props: AltraVitaProps,
    pointer: Offset,
    phase: Float,
    clockMillis: Long,
) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    // Pointer offset from the centre, normalised, so the parallax is the same
    // gesture on any window size.
    val rel = if (pointer == Offset.Unspecified) Offset.Zero else Offset(
        (pointer.x - centre.x) / size.width,
        (pointer.y - centre.y) / size.height,
    )
    val amount = props.parallax.coerceIn(0f, 0.25f) * size.width
    fun shiftFor(depth: Float) = Offset(-rel.x * amount * depth, -rel.y * amount * depth)

    val breath = 0.5f + 0.5f * sin(phase * 2f * PI.toFloat())

    for (p in scene.back) plate(p, centre, shiftFor(p.depth), 1f, 1f)

    scene.figure?.let { plate(it, centre, shiftFor(it.depth), 1f, 1f) }

    // The dial is the one piece bound to something real: the art is a clock, so
    // it is used as one.
    scene.dial?.let { dial ->
        val now = LocalTime.now()
        val turns = if (props.liveClock) {
            (now.hour % 12 + now.minute / 60f) / 12f
        } else {
            0f
        }
        val shift = shiftFor(dial.depth)
        val w = dial.image.width.toFloat()
        val h = dial.image.height.toFloat()
        val k = (size.height / 480f) * dial.scale
        withTransform({
            translate(centre.x + shift.x, centre.y + shift.y)
            rotate(turns * 360f, Offset.Zero)
            scale(k, k, Offset.Zero)
        }) {
            drawImage(
                image = dial.image,
                dstOffset = IntOffset((-w / 2f).toInt(), (-h / 2f).toInt()),
                dstSize = IntSize(dial.image.width, dial.image.height),
                alpha = dial.alpha,
            )
        }
        // A mark riding the minute hand, drawn rather than shipped.
        val ang = (now.minute + now.second / 60f) / 60f * 2f * PI.toFloat() - PI.toFloat() / 2f
        val r = k * h * 0.32f
        drawCircle(
            color = Color(1f, 1f, 1f, 0.85f),
            radius = 3.5f + 2f * breath,
            center = Offset(centre.x + shift.x + cos(ang) * r, centre.y + shift.y + sin(ang) * r),
            blendMode = BlendMode.Plus,
        )
    }

    scene.glow?.let { plate(it, centre, shiftFor(it.depth), 0.85f + 0.25f * breath, 0.18f + 0.22f * breath) }
    scene.piece?.let { plate(it, centre, shiftFor(it.depth), 0.98f + 0.04f * breath, 1f) }

    for (p in scene.overlays) plate(p, centre, Offset.Zero, 1f, 1f)

    // A hairline that tracks the breath, so the frame is alive even when the
    // pointer is not moving.
    drawRect(
        color = Color(0.55f, 0.6f, 0.8f, 0.02f + 0.03f * breath),
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
        blendMode = BlendMode.Plus,
    )
}
