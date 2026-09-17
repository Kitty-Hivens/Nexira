package hivens.module.osusb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Evaluating a sprite at a point in time.
 *
 * Pure arithmetic on the parsed model, deliberately Compose-free: the same code
 * answers "what does this look like at 92.4 seconds" for a frame and for a test,
 * and a test needs no window to ask.
 */

/** osu's easing table, with the curves it actually uses spelled out and the rest linear. */
fun ease(kind: Int, t: Float): Float {
    val p = t.coerceIn(0f, 1f)
    return when (kind) {
        0 -> p
        1, 4 -> 1f - (1f - p).pow(2f)              // out quad
        2, 3 -> p * p                               // in quad
        5 -> if (p < 0.5f) 2f * p * p else 1f - (-2f * p + 2f).pow(2f) / 2f
        6 -> p * p * p
        7 -> 1f - (1f - p).pow(3f)
        8 -> if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).pow(3f) / 2f
        9 -> p.pow(4f)
        10 -> 1f - (1f - p).pow(4f)
        12 -> p.pow(5f)
        13 -> 1f - (1f - p).pow(5f)
        15 -> if (p == 0f) 0f else 2f.pow(10f * p - 10f)
        16 -> if (p == 1f) 1f else 1f - 2f.pow(-10f * p)
        18 -> 1f - sqrt(1f - p * p)
        19 -> sqrt(1f - (p - 1f) * (p - 1f))
        21 -> 1f - cos(p * PI.toFloat() / 2f)
        22 -> sin(p * PI.toFloat() / 2f)
        23 -> -(cos(PI.toFloat() * p) - 1f) / 2f
        else -> p
    }
}

/** Everything a sprite needs to be drawn at one instant. */
class SpriteState {
    var alpha = 1f
    var x = 0f
    var y = 0f
    var scaleX = 1f
    var scaleY = 1f
    var rotation = 0f
    var red = 1f
    var green = 1f
    var blue = 1f
    var additive = false
    var flipH = false
    var flipV = false
    var visible = false
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Applies every tween of one kind at time [now], writing the result through
 * [set].
 *
 * The rule is osu's and is not obvious: before a property's first tween the
 * start value already applies, after its last one the end value persists, and in
 * a gap between two the earlier end value holds. So a property is never
 * undefined once it has been mentioned once, which is why a sprite can be
 * positioned by a single zero-length command at time zero.
 */
private inline fun resolve(
    sprite: SbSprite,
    kind: SbKind,
    now: Int,
    set: (Float, Float, Float) -> Unit,
): Boolean {
    var found = false
    var bestStart = Int.MIN_VALUE
    for (t in sprite.tweens) {
        if (t.kind != kind) continue
        found = true
        if (now < t.start) {
            // The earliest tween of this kind still governs before it begins.
            if (bestStart == Int.MIN_VALUE) {
                bestStart = t.start
                set(t.a0, t.a1, t.a2)
            }
            continue
        }
        if (t.start >= bestStart) {
            bestStart = t.start
            if (now >= t.end) {
                set(t.b0, t.b1, t.b2)
            } else {
                val span = (t.end - t.start).toFloat()
                val p = if (span <= 0f) 1f else ease(t.easing, (now - t.start) / span)
                set(lerp(t.a0, t.b0, p), lerp(t.a1, t.b1, p), lerp(t.a2, t.b2, p))
            }
        }
    }
    return found
}

/** True while any tween of [kind] has begun, which is how a flag parameter reads. */
private fun flagActive(sprite: SbSprite, kind: SbKind, now: Int): Boolean {
    for (t in sprite.tweens) {
        if (t.kind == kind && now >= t.start && now <= maxOf(t.end, t.start)) return true
    }
    return false
}

/** Fills [out] with [sprite]'s state at [now]. Returns false when it should not be drawn. */
fun evaluate(sprite: SbSprite, now: Int, out: SpriteState): Boolean {
    if (now < sprite.start || now > sprite.end) {
        out.visible = false
        return false
    }
    out.x = sprite.x
    out.y = sprite.y
    out.scaleX = 1f
    out.scaleY = 1f
    out.rotation = 0f
    out.red = 1f; out.green = 1f; out.blue = 1f
    out.alpha = 1f

    resolve(sprite, SbKind.Fade, now) { a, _, _ -> out.alpha = a }
    resolve(sprite, SbKind.MoveX, now) { a, _, _ -> out.x = a }
    resolve(sprite, SbKind.MoveY, now) { a, _, _ -> out.y = a }
    val uniform = resolve(sprite, SbKind.Scale, now) { a, _, _ -> out.scaleX = a; out.scaleY = a }
    if (!uniform) {
        resolve(sprite, SbKind.ScaleX, now) { a, _, _ -> out.scaleX = a }
        resolve(sprite, SbKind.ScaleY, now) { a, _, _ -> out.scaleY = a }
    } else {
        resolve(sprite, SbKind.ScaleX, now) { a, _, _ -> out.scaleX = a }
        resolve(sprite, SbKind.ScaleY, now) { a, _, _ -> out.scaleY = a }
    }
    resolve(sprite, SbKind.Rotate, now) { a, _, _ -> out.rotation = a }
    resolve(sprite, SbKind.Colour, now) { r, g, b ->
        out.red = r / 255f; out.green = g / 255f; out.blue = b / 255f
    }
    out.additive = flagActive(sprite, SbKind.Additive, now)
    out.flipH = flagActive(sprite, SbKind.FlipH, now)
    out.flipV = flagActive(sprite, SbKind.FlipV, now)
    out.visible = out.alpha > 0.002f && out.scaleX != 0f && out.scaleY != 0f
    return out.visible
}

/** Fraction of the sprite's own anchor, per origin, in [0,1] of its size. */
fun originFractions(origin: SbOrigin): Pair<Float, Float> = when (origin) {
    SbOrigin.TopLeft -> 0f to 0f
    SbOrigin.TopCentre -> 0.5f to 0f
    SbOrigin.TopRight -> 1f to 0f
    SbOrigin.CentreLeft -> 0f to 0.5f
    SbOrigin.Centre, SbOrigin.Custom -> 0.5f to 0.5f
    SbOrigin.CentreRight -> 1f to 0.5f
    SbOrigin.BottomLeft -> 0f to 1f
    SbOrigin.BottomCentre -> 0.5f to 1f
    SbOrigin.BottomRight -> 1f to 1f
}
