package hivens.module.osusb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A playable round, not a picture.
 *
 * The beatmap beside the art carries real timing, so this reads it and runs it:
 * notes approach, the pointer answers them, judgements land, a combo builds and
 * breaks. It is the same question as the two widgets beside it, asked at the one
 * place a widget system usually cannot follow -- something that takes over,
 * keeps its own clock, consumes input and has a result.
 *
 * What it could not get from the kernel is drawn on its own HUD rather than
 * hidden, because that is the point of the exercise.
 */

@Serializable
data class RhythmProps(
    val folder: String = "",
    /** Which difficulty, by index into the folder's sorted list. */
    val difficulty: Int = 0,
    /** Where the round starts, in milliseconds. */
    val startMs: Int = 0,
    /** Slows everything down without changing the beatmap. */
    val speed: Float = 1f,
    /** Draw the beatmap's own background behind the round. */
    val backdrop: Boolean = true,
)

internal enum class Judgement { Perfect, Good, Meh, Miss }

internal class Judged(val note: Note, val judgement: Judgement, val at: Int)

internal class Round(
    val map: Beatmap,
    val backdrop: androidx.compose.ui.graphics.ImageBitmap?,
)

internal suspend fun loadRound(folder: String, index: Int, wantBackdrop: Boolean): Round? =
    withContext(Dispatchers.IO) {
        val dir = File(folder)
        val maps = loadBeatmaps(dir)
        val map = maps.getOrNull(index.coerceIn(0, (maps.size - 1).coerceAtLeast(0))) ?: return@withContext null
        val bg = if (!wantBackdrop) null else {
            dir.listFiles { f -> f.isFile && f.name.equals("background.png", true) }
                ?.firstOrNull()
                ?.let { f ->
                    runCatching {
                        org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap()
                    }.getOrNull()
                }
        }
        Round(map, bg)
    }

@Widget(
    id = "osusb.rhythm",
    displayName = "osusb.rhythm",
    propsClass = RhythmProps::class,
    surface = """{"fill":"","padding":{"all":0.0}}""",
    drawsOwnSurface = true,
)
@Composable
fun RhythmRoundWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<RhythmProps>()
    var round by remember(props.folder, props.difficulty) { mutableStateOf<Round?>(null) }
    var now by remember(props.folder, props.difficulty) { mutableIntStateOf(props.startMs) }
    var cursor by remember { mutableStateOf(Offset.Unspecified) }

    val judged = remember(round) { ArrayList<Judged>() }
    var nextIndex by remember(round) { mutableIntStateOf(0) }
    var combo by remember(round) { mutableIntStateOf(0) }
    var best by remember(round) { mutableIntStateOf(0) }
    var hits by remember(round) { mutableIntStateOf(0) }
    var total by remember(round) { mutableIntStateOf(0) }
    var flash by remember(round) { mutableStateOf<Judged?>(null) }

    LaunchedEffect(props.folder, props.difficulty, props.backdrop) {
        round = if (props.folder.isBlank()) null else loadRound(props.folder, props.difficulty, props.backdrop)
    }

    val r = round
    LaunchedEffect(r, props.speed) {
        if (r == null) return@LaunchedEffect
        var last = 0L
        val rate = props.speed.coerceIn(0.1f, 4f)
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L) now += ((frame - last) / 1_000_000f * rate).toInt()
                last = frame
                // Anything whose window has closed unhit is a miss, and the
                // combo dies with it.
                val map = r.map
                while (nextIndex < map.notes.size && now > map.notes[nextIndex].time + map.window50) {
                    val note = map.notes[nextIndex]
                    judged.add(Judged(note, Judgement.Miss, now))
                    flash = judged.last()
                    combo = 0
                    total++
                    nextIndex++
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07060A))
            .pointerInput(r) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.lastOrNull() ?: continue
                        cursor = change.position
                        if (!change.pressed || !change.previousPressed) {
                            if (change.pressed && r != null) {
                                val judgement = judge(r.map, judged, nextIndex, now, change.position, size.width, size.height)
                                if (judgement != null) {
                                    judged.add(judgement)
                                    flash = judgement
                                    total++
                                    if (judgement.judgement == Judgement.Miss) {
                                        combo = 0
                                    } else {
                                        hits++
                                        combo++
                                        if (combo > best) best = combo
                                    }
                                    // The judged note leaves the queue.
                                    if (judgement.note === r.map.notes.getOrNull(nextIndex)) nextIndex++
                                }
                                change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        if (r == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            drawRound(r, now, cursor, combo, best, hits, total, flash)
        }
    }
}

/** Maps a playfield point into the widget, letterboxed to the field's 4:3. */
private fun fieldTransform(w: Float, h: Float): Triple<Float, Float, Float> {
    val scale = min(w / Beatmap.FIELD_W, h / Beatmap.FIELD_H) * 0.92f
    val ox = (w - Beatmap.FIELD_W * scale) / 2f
    val oy = (h - Beatmap.FIELD_H * scale) / 2f
    return Triple(scale, ox, oy)
}

private fun judge(
    map: Beatmap,
    judged: List<Judged>,
    nextIndex: Int,
    now: Int,
    at: Offset,
    w: Int,
    h: Int,
): Judged? {
    val (scale, ox, oy) = fieldTransform(w.toFloat(), h.toFloat())
    val radius = map.radius * scale
    // Only the note at the front of the queue can be hit, which is what makes a
    // rhythm game a rhythm game rather than a field of targets.
    val note = map.notes.getOrNull(nextIndex) ?: return null
    if (judged.any { it.note === note }) return null
    val dt = now - note.time
    if (abs(dt) > map.window50) return null
    val cx = ox + note.x * scale
    val cy = oy + note.y * scale
    val dx = at.x - cx
    val dy = at.y - cy
    if (sqrt(dx * dx + dy * dy) > radius) return null
    val j = when {
        abs(dt) <= map.window300 -> Judgement.Perfect
        abs(dt) <= map.window100 -> Judgement.Good
        else -> Judgement.Meh
    }
    return Judged(note, j, now)
}

internal fun DrawScope.drawRound(
    round: Round,
    now: Int,
    cursor: Offset,
    combo: Int,
    best: Int,
    hits: Int,
    total: Int,
    flash: Judged?,
) {
    val map = round.map
    round.backdrop?.let { bg ->
        val k = maxOf(size.width / bg.width, size.height / bg.height)
        drawImage(
            image = bg,
            dstOffset = IntOffset(
                ((size.width - bg.width * k) / 2f).roundToInt(),
                ((size.height - bg.height * k) / 2f).roundToInt(),
            ),
            dstSize = IntSize((bg.width * k).roundToInt(), (bg.height * k).roundToInt()),
            alpha = 0.18f,
        )
    }

    val (scale, ox, oy) = fieldTransform(size.width, size.height)
    val radius = map.radius * scale

    // The field's own frame, so the play area is legible on any slot shape.
    drawRect(
        color = Color(1f, 1f, 1f, 0.06f),
        topLeft = Offset(ox, oy),
        size = androidx.compose.ui.geometry.Size(Beatmap.FIELD_W * scale, Beatmap.FIELD_H * scale),
        style = Stroke(width = 1f),
    )

    // Draw the approaching notes back to front, so the nearest sits on top.
    val window = map.preempt
    for (i in map.notes.indices.reversed()) {
        val note = map.notes[i]
        val dt = note.time - now
        if (dt > window || dt < -map.window50) continue
        val cx = ox + note.x * scale
        val cy = oy + note.y * scale
        val appear = ((window - dt) / window).coerceIn(0f, 1f)
        val alpha = if (dt < 0) (1f + dt / map.window50).coerceIn(0f, 1f) else appear

        val accent = when (note.kind) {
            NoteKind.SliderHead -> Color(0.55f, 0.85f, 1f)
            else -> Color(1f, 0.85f, 0.45f)
        }
        // The approach ring collapses onto the note at its moment, which is the
        // whole timing cue.
        val ring = radius * (1f + 2.2f * (dt.toFloat() / window).coerceAtLeast(0f))
        if (dt > 0) {
            drawCircle(
                color = accent.copy(alpha = 0.55f * alpha),
                radius = ring,
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
            )
        }
        drawCircle(color = Color(0f, 0f, 0f, 0.55f * alpha), radius = radius, center = Offset(cx, cy))
        drawCircle(
            color = accent.copy(alpha = alpha),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 3f),
        )
        drawCircle(color = accent.copy(alpha = 0.18f * alpha), radius = radius * 0.72f, center = Offset(cx, cy))
    }

    // Judgement flash at the note that caused it.
    flash?.let { j ->
        val age = (now - j.at).toFloat()
        if (age in 0f..320f) {
            val a = 1f - age / 320f
            val cx = ox + j.note.x * scale
            val cy = oy + j.note.y * scale
            val colour = when (j.judgement) {
                Judgement.Perfect -> Color(0.45f, 0.95f, 1f)
                Judgement.Good -> Color(0.6f, 1f, 0.6f)
                Judgement.Meh -> Color(1f, 0.85f, 0.4f)
                Judgement.Miss -> Color(1f, 0.35f, 0.4f)
            }
            drawCircle(
                color = colour.copy(alpha = 0.7f * a),
                radius = radius * (1f + 1.4f * (1f - a)),
                center = Offset(cx, cy),
                style = Stroke(width = 2f),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // Combo, as a bar rather than a number: the kernel gives a widget no text
    // style and this module has no font of its own, so the HUD is drawn.
    val comboWidth = (combo.coerceAtMost(100) / 100f) * size.width * 0.3f
    drawRect(
        color = Color(1f, 0.85f, 0.45f, 0.75f),
        topLeft = Offset(24f, size.height - 34f),
        size = androidx.compose.ui.geometry.Size(comboWidth, 6f),
    )
    val acc = if (total == 0) 0f else hits.toFloat() / total
    drawRect(
        color = Color(0.45f, 0.95f, 1f, 0.65f),
        topLeft = Offset(24f, size.height - 22f),
        size = androidx.compose.ui.geometry.Size(acc * size.width * 0.3f, 4f),
    )
    for (n in 0 until best.coerceAtMost(40)) {
        drawRect(
            color = Color(1f, 1f, 1f, 0.35f),
            topLeft = Offset(24f + n * 5f, size.height - 44f),
            size = androidx.compose.ui.geometry.Size(3f, 3f),
        )
    }

    if (cursor != Offset.Unspecified) {
        drawCircle(color = Color(1f, 1f, 1f, 0.5f), radius = 5f, center = cursor, style = Stroke(width = 1.5f))
    }
}
