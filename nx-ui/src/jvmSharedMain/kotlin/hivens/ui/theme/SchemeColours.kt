package hivens.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.math.max

/**
 * Answers the requests nx-ui makes of a [Scheme]. The scheme is the author's and is
 * never changed. Everything here is read from it or computed beside it.
 *
 * One instance per active scheme, held by [NxTheme], so the computed sets are worked
 * out once rather than on every recomposition that asks.
 */
@Stable
class SchemeColours internal constructor(val scheme: Scheme) {

    val isDark: Boolean = scheme.isDark

    /** The last step the author gave. Floating surfaces sit here. */
    val topStep: Int = scheme.steps.lastIndex

    /**
     * Step [index] of the ladder. Past either end the ladder carries on: the nearest
     * authored step moved in tone by the ladder's own mean step, in the ladder's own
     * direction, with its hue and chroma. A theme that gave two steps still has a
     * fifth, and it is the same material as the first two.
     */
    fun step(index: Int): Color {
        val steps = scheme.steps
        if (index in steps.indices) return steps[index]
        val first = steps.first().hct()
        val last = steps.last().hct()
        val mean = (last.tone - first.tone) / steps.lastIndex
        return if (index > steps.lastIndex) {
            val n = index - steps.lastIndex
            Hct.from(last.hue, last.chroma, (last.tone + mean * n).coerceIn(0.0, 100.0)).toColor()
        } else {
            Hct.from(first.hue, first.chroma, (first.tone + mean * index).coerceIn(0.0, 100.0)).toColor()
        }
    }

    /** The main ink, made readable on [ground]. */
    fun inkMain(ground: Color): Color = fit(scheme.inks[0], ground, TEXT)

    /** The quiet ink, made readable on [ground]. */
    fun inkQuiet(ground: Color): Color = fit(scheme.inks[1], ground, TEXT)

    /** A hairline on [ground]: a rule of the ground's own material toward its quiet ink. */
    fun line(ground: Color): Color = lerp(ground, scheme.inks[1], LINE_MIX)

    /** The theme's first colour. */
    val lead: Color get() = scheme.colors.first()

    /**
     * The colour for [status]: the theme's own colour nearest the conventional hue
     * when it has one near enough, otherwise one made at that hue in the theme's
     * typical chroma and tone.
     */
    fun status(status: Status): Color = statusCache.getOrPut(status) {
        val target = status.hue
        val own = scheme.colors
            .map { it to it.hct() }
            .filter { (_, h) -> h.chroma >= MIN_STATUS_CHROMA && hueDistance(h.hue, target) <= STATUS_TOLERANCE }
            .minByOrNull { (_, h) -> hueDistance(h.hue, target) }
            ?.first
        own ?: Hct.from(target, max(typicalChroma, MIN_DERIVED_CHROMA), typicalTone).toColor()
    }

    /**
     * [n] colours that read as different from each other. The theme's own colours
     * first, in its order, skipping any too close to one already taken. Then, if the
     * theme has fewer than [n], new ones at the middle of the widest gap in hue, in
     * the theme's typical chroma and tone. Deterministic, so one request always gets
     * the same answer.
     */
    fun distinct(n: Int): List<Color> = distinctCache.getOrPut(n) {
        val taken = mutableListOf<Color>()
        for (c in scheme.colors) {
            if (taken.size == n) break
            if (taken.none { deltaE(it, c) < MIN_DISTINCT_DE }) taken += c
        }
        val chroma = max(typicalChroma, MIN_DERIVED_CHROMA)
        while (taken.size < n) {
            val hues = taken.map { it.hct().hue }.sorted()
            val hue = if (hues.isEmpty()) 0.0 else widestGapMiddle(hues)
            taken += Hct.from(hue, chroma, typicalTone).toColor()
        }
        taken
    }

    /** One of a fixed spread of distinct colours, chosen by [key]: stable variety for avatars and tags. */
    fun hashed(key: String): Color = distinct(HASH_SPREAD)[abs(key.hashCode() % HASH_SPREAD)]

    /** Ink for content laid on [fill]: whichever end of the scheme reads, or black or white if neither does. */
    fun on(fill: Color): Color {
        val opaque = fill.copy(alpha = 1f)
        val best = listOf(scheme.inks[0], scheme.steps[0]).maxBy { contrast(it, opaque) }
        if (contrast(best, opaque) >= TEXT) return best
        return if (contrast(Color.White, opaque) >= contrast(Color.Black, opaque)) Color.White else Color.Black
    }

    private val typicalChroma: Double = scheme.colors.map { it.hct().chroma }.median()
    private val typicalTone: Double = scheme.colors.map { it.hct().tone }.median().coerceIn(35.0, 80.0)
    private val statusCache = HashMap<Status, Color>()
    private val distinctCache = HashMap<Int, List<Color>>()

    internal companion object {
        /** Body text against what it is read on. */
        const val TEXT = 4.5

        /** Marks, lines, icons and filled controls against what they sit on. */
        const val MARK = 3.0

        private const val LINE_MIX = 0.28f
        private const val STATUS_TOLERANCE = 28.0
        private const val MIN_STATUS_CHROMA = 24.0
        private const val MIN_DERIVED_CHROMA = 40.0
        private const val MIN_DISTINCT_DE = 18.0
        private const val HASH_SPREAD = 8
    }
}

/** The conventional hue of a status, in HCT degrees. */
private val Status.hue: Double
    get() = when (this) {
        Status.Error -> 25.0
        Status.Warning -> 80.0
        Status.Success -> 145.0
        Status.Info -> 265.0
    }

private fun widestGapMiddle(sortedHues: List<Double>): Double {
    var bestStart = sortedHues.last()
    var bestGap = 360.0 - sortedHues.last() + sortedHues.first()
    for (i in 0 until sortedHues.lastIndex) {
        val gap = sortedHues[i + 1] - sortedHues[i]
        if (gap > bestGap) {
            bestGap = gap
            bestStart = sortedHues[i]
        }
    }
    return (bestStart + bestGap / 2) % 360.0
}
