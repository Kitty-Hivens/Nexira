package hivens.widget.model

import kotlinx.serialization.Serializable

/**
 * Everything a surface is, as seven values.
 *
 * The editor panel shows these seven rows and the rendering API takes the same seven
 * names in the same units, so reading one shows the other. What it replaces is a
 * vocabulary where neither side described a whole surface: a tier stood for five
 * numbers of which three were discarded before they reached a pixel, the blur radius
 * was not a setting at all, and the opacity knob was overridden by a constant.
 *
 * Nothing here is an enum and every field has a default, so the persisted form only
 * ever grows. A new field is absent from an old file and reads as its default; a
 * retired one is ignored; a renamed shape kind is a change to a parser rather than a
 * break in the format. That property is the point: the layout is wiped once for this,
 * and it must not need wiping again.
 *
 * Null means "inherit": take the active style's token for this. It is not the same as
 * zero, which is a decision. Both must be expressible, which is why the numbers are
 * nullable rather than sentinel-valued.
 */
@Serializable
data class SurfaceSpec(
    /** See [FillSource]: blank, a named theme rung, or a literal colour. */
    val fill: String = "",
    val opacity: Float? = null,
    val blurDp: Float? = null,
    val shape: SurfaceShape = SurfaceShape(),
    val border: SurfaceBorder = SurfaceBorder(),
    val shadowDp: Float? = null,
    val padding: SurfaceInsets = SurfaceInsets(),
)

/**
 * Where a surface's fill colour comes from.
 *
 * One field carries both a value and a name, so there is no second control to keep in
 * step with the first and a hand-edited config reads plainly. A literal cannot follow
 * a palette, which is why the theme rungs stay reachable by name and are the default:
 * a surface that names nothing tracks whatever palette is active.
 */
sealed interface FillSource {
    /** Take the rung the call site would have picked anyway. */
    object Inherit : FillSource

    /** A rung of the theme's tonal ladder, named. The names are mirrored rather than
     *  shared because the design system is a leaf module and stays one. */
    data class Rung(val name: String) : FillSource

    /** An explicit colour, alpha included. */
    data class Literal(val argb: Int) : FillSource
}

/** The rung names [FillSource.Rung] accepts, shallowest first. */
val SURFACE_RUNGS: List<String> = listOf("sunken", "base", "raised", "floating")

/**
 * Reads a [SurfaceSpec.fill] value. Anything unrecognised is [FillSource.Inherit]:
 * this parses a file a human may have typed into, so a mistake has to degrade to the
 * theme's own colour rather than to a crash or to black.
 */
fun parseFill(value: String): FillSource {
    val v = value.trim()
    if (v.isEmpty()) return FillSource.Inherit
    if (v.startsWith("#")) return parseHexFill(v) ?: FillSource.Inherit
    val lower = v.lowercase()
    return if (lower in SURFACE_RUNGS) FillSource.Rung(lower) else FillSource.Inherit
}

private fun parseHexFill(v: String): FillSource.Literal? {
    val digits = v.removePrefix("#")
    if (digits.any { it !in "0123456789abcdefABCDEF" }) return null
    val argb = when (digits.length) {
        6 -> 0xFF000000L or digits.toLong(16)
        8 -> digits.toLong(16)
        else -> return null
    }
    return FillSource.Literal(argb.toInt())
}

/**
 * The outline of a surface.
 *
 * [kind] blank takes the style's card shape. Fields that belong to one kind are
 * ignored by the others, which keeps the whole thing flat: a discriminated union would
 * need a serializer per arm and would make adding a kind a format change rather than a
 * new string.
 *
 * [smoothing] is the squircle amount rather than a rounding radius: continuous
 * curvature, available to every kind.
 *
 * [points], [innerRadius] and [pointRounding] belong to the star and polygon kinds and
 * are ignored by the rest -- which is the flatness working as intended. They are
 * fractions rather than lengths because a star has no straight edge to measure a
 * corner against: it is stretched to whatever footprint it lands in.
 */
@Serializable
data class SurfaceShape(
    /** "" | rect | round | circle | pill | star | polygon. A kind a renderer does not
     *  answer falls back to the card shape, so a newer file read by an older build
     *  loses the outline rather than the plane. */
    val kind: String = "",
    val corners: SurfaceCorners = SurfaceCorners(),
    val smoothing: Float? = null,
    /** Spikes on a star, sides on a polygon. Below three there is no shape; the
     *  renderer holds it there rather than refusing to draw. */
    val points: Int? = null,
    /** How far a star's notches fall in, as a fraction of its reach. Ignored by
     *  polygon, which has no notches. */
    val innerRadius: Float? = null,
    /** Rounding on a star's or polygon's corners, as a fraction. Separate from
     *  [corners] because those are dp against a straight edge and this is not. */
    val pointRounding: Float? = null,
)

/**
 * Per-corner rounding, in the same baseline-plus-override shape the insets use.
 *
 * Named start and end rather than left and right so a mirrored layout does not need
 * a second set of fields later.
 */
@Serializable
data class SurfaceCorners(
    val all: Float? = null,
    val topStart: Float? = null,
    val topEnd: Float? = null,
    val bottomEnd: Float? = null,
    val bottomStart: Float? = null,
) {
    fun topStart(fallback: Float): Float = topStart ?: all ?: fallback
    fun topEnd(fallback: Float): Float = topEnd ?: all ?: fallback
    fun bottomEnd(fallback: Float): Float = bottomEnd ?: all ?: fallback
    fun bottomStart(fallback: Float): Float = bottomStart ?: all ?: fallback

    /** True when every corner resolves to the same number, whatever it is. */
    fun isUniform(fallback: Float): Boolean =
        topStart(fallback) == topEnd(fallback) &&
            topEnd(fallback) == bottomEnd(fallback) &&
            bottomEnd(fallback) == bottomStart(fallback)
}

/** A hairline around the surface. [color] follows [SurfaceSpec.fill]'s grammar. */
@Serializable
data class SurfaceBorder(
    val widthDp: Float? = null,
    val color: String = "",
    val opacity: Float? = null,
)

/** Inner spacing, baseline plus per-side overrides. */
@Serializable
data class SurfaceInsets(
    val all: Float? = null,
    val top: Float? = null,
    val end: Float? = null,
    val bottom: Float? = null,
    val start: Float? = null,
) {
    fun top(fallback: Float): Float = top ?: all ?: fallback
    fun end(fallback: Float): Float = end ?: all ?: fallback
    fun bottom(fallback: Float): Float = bottom ?: all ?: fallback
    fun start(fallback: Float): Float = start ?: all ?: fallback
}
