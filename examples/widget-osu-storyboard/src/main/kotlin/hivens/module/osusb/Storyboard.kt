package hivens.module.osusb

import java.io.File

/**
 * The storyboard format, reduced to what it actually is: a list of sprites, each
 * carrying typed tweens over time.
 *
 * Parsed from the `.osb` beside a beatmap (and from the `[Events]` section of a
 * `.osu`, which uses the same grammar). Everything here is plain Kotlin with no
 * Compose in it, so the whole timeline can be built and tested off the UI thread
 * and without a window.
 */

enum class SbLayer { Background, Fail, Pass, Foreground, Overlay }

enum class SbOrigin {
    TopLeft, Centre, CentreLeft, TopRight, BottomCentre,
    TopCentre, Custom, CentreRight, BottomLeft, BottomRight,
}

/** Which property a tween drives. */
enum class SbKind { Fade, MoveX, MoveY, Scale, ScaleX, ScaleY, Rotate, Colour, Additive, FlipH, FlipV }

/**
 * One tween. [a] and [b] hold up to three components so a colour and a vector
 * scale fit the same record as a fade, which keeps evaluation to one loop
 * instead of one per property type.
 */
class SbTween(
    val kind: SbKind,
    val easing: Int,
    val start: Int,
    val end: Int,
    val a0: Float, val a1: Float, val a2: Float,
    val b0: Float, val b1: Float, val b2: Float,
)

class SbSprite(
    val layer: SbLayer,
    val origin: SbOrigin,
    val path: String,
    val x: Float,
    val y: Float,
    val tweens: List<SbTween>,
) {
    val start: Int = tweens.minOfOrNull { it.start } ?: 0
    val end: Int = tweens.maxOfOrNull { it.end } ?: 0
}

class Storyboard(val sprites: List<SbSprite>) {
    val duration: Int = sprites.maxOfOrNull { it.end } ?: 0

    companion object {
        /** Commands past this are dropped, so a pathological loop cannot exhaust the heap. */
        const val MAX_TWEENS = 400_000
    }
}

private fun originOf(name: String): SbOrigin = when (name.trim()) {
    "0", "TopLeft" -> SbOrigin.TopLeft
    "1", "Centre", "Center" -> SbOrigin.Centre
    "2", "CentreLeft", "CenterLeft" -> SbOrigin.CentreLeft
    "3", "TopRight" -> SbOrigin.TopRight
    "4", "BottomCentre", "BottomCenter" -> SbOrigin.BottomCentre
    "5", "TopCentre", "TopCenter" -> SbOrigin.TopCentre
    "6", "Custom" -> SbOrigin.Custom
    "7", "CentreRight", "CenterRight" -> SbOrigin.CentreRight
    "8", "BottomLeft" -> SbOrigin.BottomLeft
    "9", "BottomRight" -> SbOrigin.BottomRight
    else -> SbOrigin.TopLeft
}

private fun layerOf(name: String): SbLayer = when (name.trim()) {
    "0", "Background" -> SbLayer.Background
    "1", "Fail" -> SbLayer.Fail
    "2", "Pass" -> SbLayer.Pass
    "3", "Foreground" -> SbLayer.Foreground
    "4", "Overlay" -> SbLayer.Overlay
    else -> SbLayer.Background
}

private fun String.unquote(): String = trim().removeSurrounding("\"")

private fun f(parts: List<String>, i: Int, fallback: Float): Float =
    parts.getOrNull(i)?.trim()?.toFloatOrNull() ?: fallback

private fun i(parts: List<String>, idx: Int, fallback: Int): Int =
    parts.getOrNull(idx)?.trim()?.toFloatOrNull()?.toInt() ?: fallback

/** How deep a command line is nested: one level is a sprite command, two is inside a loop. */
private fun depthOf(line: String): Int {
    var d = 0
    for (c in line) {
        if (c == ' ' || c == '_') d++ else break
    }
    return d
}

/**
 * Reads a storyboard out of [text].
 *
 * Tolerant by construction: an unknown verb, a short line or a malformed number
 * is skipped rather than thrown, because a storyboard is third-party content and
 * one bad line should cost that line and nothing else.
 */
fun parseStoryboard(text: String): Storyboard {
    val sprites = ArrayList<SbSprite>()
    var pending: MutableList<SbTween>? = null
    var head: Triple<SbLayer, SbOrigin, String>? = null
    var headX = 0f
    var headY = 0f
    var total = 0

    // Loop state: while inside an L block, commands are collected and then
    // replayed once per iteration with their times shifted.
    var loopStart = 0
    var loopCount = 0
    var loopBody: MutableList<SbTween>? = null

    fun closeLoop() {
        val body = loopBody ?: return
        val into = pending
        loopBody = null
        if (into == null || body.isEmpty()) return
        val span = body.maxOf { it.end }
        repeat(loopCount) { n ->
            val shift = loopStart + n * span
            for (t in body) {
                if (total >= Storyboard.MAX_TWEENS) return
                into.add(
                    SbTween(t.kind, t.easing, t.start + shift, t.end + shift, t.a0, t.a1, t.a2, t.b0, t.b1, t.b2),
                )
                total++
            }
        }
    }

    fun flush() {
        closeLoop()
        val h = head ?: return
        val cmds = pending ?: return
        if (cmds.isNotEmpty()) sprites.add(SbSprite(h.first, h.second, h.third, headX, headY, cmds))
        head = null
        pending = null
    }

    for (raw in text.lineSequence()) {
        if (raw.isBlank()) continue
        val trimmed = raw.trim()
        if (trimmed.startsWith("//")) continue
        val depth = depthOf(raw)

        if (depth == 0) {
            // A new object header ends whatever came before it.
            flush()
            val parts = trimmed.split(',')
            val verb = parts.getOrNull(0)?.trim().orEmpty()
            if (verb == "Sprite" || verb == "Animation") {
                head = Triple(
                    layerOf(parts.getOrNull(1).orEmpty()),
                    originOf(parts.getOrNull(2).orEmpty()),
                    parts.getOrNull(3).orEmpty().unquote().replace('\\', '/'),
                )
                headX = f(parts, 4, 320f)
                headY = f(parts, 5, 240f)
                pending = ArrayList()
            }
            continue
        }

        val cmds = pending ?: continue
        val parts = trimmed.split(',')
        val verb = parts.getOrNull(0)?.trim().orEmpty()

        if (verb == "L") {
            closeLoop()
            loopStart = i(parts, 1, 0)
            loopCount = i(parts, 2, 0).coerceIn(0, 10_000)
            loopBody = ArrayList()
            continue
        }
        // A command at depth 1 after a loop block closes that block.
        if (loopBody != null && depth <= 1) closeLoop()

        val target = loopBody ?: cmds
        // Inside a loop the times are relative to the loop's own origin, so they
        // are parsed the same way and shifted when the block closes.
        val easing = i(parts, 1, 0)
        val s = i(parts, 2, 0)
        val e = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }?.toFloatOrNull()?.toInt() ?: s

        fun add(kind: SbKind, a0: Float, a1: Float, a2: Float, b0: Float, b1: Float, b2: Float) {
            if (total >= Storyboard.MAX_TWEENS) return
            target.add(SbTween(kind, easing, s, e, a0, a1, a2, b0, b1, b2))
            total++
        }

        when (verb) {
            "F" -> {
                val a = f(parts, 4, 1f); add(SbKind.Fade, a, 0f, 0f, f(parts, 5, a), 0f, 0f)
            }
            "S" -> {
                val a = f(parts, 4, 1f); add(SbKind.Scale, a, 0f, 0f, f(parts, 5, a), 0f, 0f)
            }
            "R" -> {
                val a = f(parts, 4, 0f); add(SbKind.Rotate, a, 0f, 0f, f(parts, 5, a), 0f, 0f)
            }
            "MX" -> {
                val a = f(parts, 4, 0f); add(SbKind.MoveX, a, 0f, 0f, f(parts, 5, a), 0f, 0f)
            }
            "MY" -> {
                val a = f(parts, 4, 0f); add(SbKind.MoveY, a, 0f, 0f, f(parts, 5, a), 0f, 0f)
            }
            "M" -> {
                val x0 = f(parts, 4, 0f); val y0 = f(parts, 5, 0f)
                add(SbKind.MoveX, x0, 0f, 0f, f(parts, 6, x0), 0f, 0f)
                add(SbKind.MoveY, y0, 0f, 0f, f(parts, 7, y0), 0f, 0f)
            }
            "V" -> {
                val x0 = f(parts, 4, 1f); val y0 = f(parts, 5, 1f)
                add(SbKind.ScaleX, x0, 0f, 0f, f(parts, 6, x0), 0f, 0f)
                add(SbKind.ScaleY, y0, 0f, 0f, f(parts, 7, y0), 0f, 0f)
            }
            "C" -> {
                val r0 = f(parts, 4, 255f); val g0 = f(parts, 5, 255f); val b0 = f(parts, 6, 255f)
                add(
                    SbKind.Colour, r0, g0, b0,
                    f(parts, 7, r0), f(parts, 8, g0), f(parts, 9, b0),
                )
            }
            "P" -> {
                when (parts.getOrNull(4)?.trim()) {
                    "A" -> add(SbKind.Additive, 1f, 0f, 0f, 1f, 0f, 0f)
                    "H" -> add(SbKind.FlipH, 1f, 0f, 0f, 1f, 0f, 0f)
                    "V" -> add(SbKind.FlipV, 1f, 0f, 0f, 1f, 0f, 0f)
                }
            }
            // T (trigger) needs hit events the launcher has no notion of, so it
            // is read and dropped rather than guessed at.
            else -> Unit
        }
    }
    flush()
    return Storyboard(sprites)
}

/**
 * Finds the storyboard for a beatmap folder: the `.osb` if there is one, else the
 * `[Events]` section of the first difficulty, which is where a beatmap-specific
 * storyboard lives.
 */
fun loadStoryboard(dir: File): Storyboard? {
    if (!dir.isDirectory) return null
    val osb = dir.listFiles { f -> f.isFile && f.name.endsWith(".osb", ignoreCase = true) }?.minByOrNull { it.name }
    val text = buildString {
        osb?.let { append(runCatching { it.readText() }.getOrDefault("")) }
        if (isEmpty()) {
            val osu = dir.listFiles { f -> f.isFile && f.name.endsWith(".osu", ignoreCase = true) }
                ?.minByOrNull { it.name }
            osu?.let { append(runCatching { it.readText() }.getOrDefault("")) }
        }
    }
    if (text.isBlank()) return null
    val sb = parseStoryboard(text)
    return if (sb.sprites.isEmpty()) null else sb
}
