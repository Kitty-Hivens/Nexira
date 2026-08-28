package hivens.ui.surface

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * The layered-surface system. A frosted surface is NOT one translucent pane --
 * it is an ordered stack of atomic layers drawn behind content, so transparency
 * always has separation and the surface reads as a distinct plane:
 *
 *   Backdrop (real blur of our wallpaper) -> Fill (scrim) -> Wash (tint) ->
 *   Edge (highlight + shade + border + glow) -> Texture / State -> content.
 *
 * Structure (the layer list) is orthogonal to color (resolved from theme roles),
 * so a wallpaper-seeded palette and a chosen depth compose independently. The
 * old [hivens.ui.customization.glassSurfaceAlpha] is just a single [Fill] of the
 * surface role -- call sites migrate to [FrostSurface] over time, not at once.
 */
sealed interface SurfaceLayer

/** Blurred slice of the active wallpaper, clipped to the surface. */
data class Backdrop(val blurRadiusDp: Float = 18f) : SurfaceLayer

/** The "solid panel": a managed-alpha color fill that gives the surface body
 *  and the contrast text needs over a mutable blur. Alpha scales with the
 *  user's glass-intensity knob, like [hivens.ui.customization.glassSurfaceAlpha]. */
data class Fill(val role: FrostRole = FrostRole.Surface, val alpha: Float = 0.6f) : SurfaceLayer

/** The opaque tonal BODY of a surface. Unlike [Fill], its alpha is the floor that
 *  keeps a plane from collapsing into the background when the glass coat thins
 *  (light theme / no wallpaper / glassIntensity 0) -- it is INDEPENDENT of the
 *  glass-intensity knob. Glass is a coat over the body, never instead of it. */
data class Body(val role: FrostRole = FrostRole.Surface, val floorAlpha: Float = 1f) : SurfaceLayer

/** Optional tint/gradient wash over the fill for mood and depth. */
data class Wash(
    val role: FrostRole = FrostRole.Primary,
    val startAlpha: Float = 0.10f,
    val endAlpha: Float = 0f,
    val vertical: Boolean = true,
) : SurfaceLayer

/**
 * Emphasis group -- what lifts the surface off the blurred background. Without
 * it a frosted panel melts into the wallpaper (same luminance, no edge). A named
 * group for ergonomics; [toAtoms] expands it so the power user can address each
 * piece (shadow without border, glow without highlight, ...) individually.
 */
data class Edge(
    val highlight: Boolean = true,
    val shadow: Boolean = true,
    val border: Boolean = false,
    val glow: Boolean = false,
    val highlightAlpha: Float = 0.16f,
    val shadowAlpha: Float = 0.22f,
    val borderRole: FrostRole = FrostRole.Outline,
    val borderWidthDp: Float = 1f,
    val borderAlpha: Float = 0.5f,
    val glowRole: FrostRole = FrostRole.Primary,
    val glowElevationDp: Float = 12f,
) : SurfaceLayer {
    /** Expand to atoms in back-to-front draw order (glow is the outer shadow). */
    fun toAtoms(): List<SurfaceLayer> = buildList {
        if (glow) add(EdgeGlow(glowRole, glowElevationDp))
        if (shadow) add(EdgeShadow(shadowAlpha))
        if (highlight) add(EdgeHighlight(highlightAlpha))
        if (border) add(EdgeBorder(borderRole, borderWidthDp, borderAlpha))
    }
}

/** Top inner highlight -- light catching the upper edge of the slab. */
data class EdgeHighlight(val alpha: Float = 0.16f) : SurfaceLayer

/** Bottom inner shade -- the slab's lower edge falling into shadow. */
data class EdgeShadow(val alpha: Float = 0.22f) : SurfaceLayer

/** Hairline border around the surface. With [explicitColor] set, that color is used
 *  verbatim (the luminance-derived bevel path); otherwise [role] @ [alpha] resolves. */
data class EdgeBorder(
    val role: FrostRole = FrostRole.Outline,
    val widthDp: Float = 1f,
    val alpha: Float = 0.5f,
    val explicitColor: Color? = null,
) : SurfaceLayer

/** Outer drop shadow / glow -- drawn outside the clip; gated on softGlowEnabled. */
data class EdgeGlow(val role: FrostRole = FrostRole.Primary, val elevationDp: Float = 12f) : SurfaceLayer

/**
 * A cast shadow: neutral, outside the clip, under the whole plane.
 *
 * Distinct from [EdgeGlow], which is an accent-coloured bloom for emphasis, and
 * from [EdgeShadow], which is not a shadow at all -- that one darkens a band
 * inside the body, so it reads as the colour dropping rather than as light being
 * blocked, and on a heavily rounded plane its straight band cuts across the
 * corner arcs.
 *
 * [elevationDp] null takes the active style's panel elevation, which is how the
 * form axis gets to decide: Brut sets it to zero and the plane sits flat, with
 * no separate switch to keep in sync.
 */
data class DropShadow(val elevationDp: Float? = null) : SurfaceLayer

/** Subtle texture so large glass areas do not band. */
data class Texture(val grainAlpha: Float = 0.04f) : SurfaceLayer

/** Hover / press state tint; rendered only when an interaction source is given. */
data class StateOverlay(val role: FrostRole = FrostRole.Primary, val hoverAlpha: Float = 0.06f, val pressAlpha: Float = 0.12f) : SurfaceLayer

/** Theme color roles a layer can pull from -- maps to live palette fields for
 *  rendering. */
enum class FrostRole { Surface, SurfaceContainerLow, SurfaceContainer, SurfaceContainerHigh, Background, Primary, Secondary, Tertiary, Outline }

private fun NxColors.frost(role: FrostRole): Color = when (role) {
    FrostRole.Surface              -> surface
    FrostRole.SurfaceContainerLow  -> surfaceContainerLow
    FrostRole.SurfaceContainer     -> surfaceContainer
    FrostRole.SurfaceContainerHigh -> surfaceContainerHigh
    FrostRole.Background           -> background
    FrostRole.Primary              -> primary
    FrostRole.Secondary            -> secondary
    FrostRole.Tertiary             -> tertiary
    FrostRole.Outline              -> outline
}

@Composable
fun frostColor(role: FrostRole): Color = NxTheme.colors.frost(role)

/** Named depth presets -- each is just a saved layer list. Serializable so it
 *  can be a widget prop field (renders as a dropdown in the editor). */
@kotlinx.serialization.Serializable
enum class FrostTier { Clear, Flat, Frosted, Heavy }

/**
 * The presets carry a body, an optional blur, and a cast shadow. What they no
 * longer carry is the inner bevel ([EdgeHighlight] / [EdgeShadow]) or the accent
 * [Wash].
 *
 * The bevel painted a white band along the top and a black band along the bottom
 * of every panel. Neither is light: they are the fill getting lighter and darker
 * in two places, in pure white and pure black rather than anything from the
 * palette, so a plane lost saturation for depth it never actually gained. Both
 * bands drew as straight rectangles too, which is invisible at a card's corner
 * radius and obvious at a pill's.
 *
 * The wash tinted every heavy plane with the primary at ten percent, so surfaces
 * picked up an accent nobody asked them for and every nested one multiplied it.
 *
 * All three types still exist. A caller who wants them assembles its own layer
 * list; they are simply not what a plane is by default.
 */
fun FrostTier.toLayers(): List<SurfaceLayer> = when (this) {
    // Clear is the transparent tier: a lone glass coat, no body. NxSurface renders it
    // bodiless (see NxSurface); a raw FrostSurface just draws the fill, same as Flat.
    FrostTier.Clear   -> listOf(Fill(alpha = 0.35f))
    FrostTier.Flat    -> listOf(Fill(alpha = 0.35f)) // matches the rail's glassSurfaceAlpha(0.35) for a seamless chrome
    FrostTier.Frosted -> listOf(Backdrop(), Fill(alpha = 0.55f), DropShadow())
    FrostTier.Heavy   -> listOf(Backdrop(blurRadiusDp = 28f), Fill(alpha = 0.45f), DropShadow(), Texture())
}

/** A [Body]'s alpha is the slider-independent floor (Rule 2): the plane must read
 *  even when the glass coat is gone. No glassIntensity term -- that is the point. */
internal fun bodyAlpha(floorAlpha: Float): Float = floorAlpha.coerceIn(0f, 1f)

/** A [Fill]'s alpha is the optional glass coat: it scales with the user's
 *  glass-intensity knob and thins to nothing at intensity 0. */
internal fun coatAlpha(baseAlpha: Float, glassIntensity: Float): Float =
    (baseAlpha * glassIntensity).coerceIn(0f, 1f)

/**
 * Renders [layers] bottom-to-top behind [content]. [Edge] groups expand to their
 * atoms; an [EdgeGlow] is lifted out as an outer drop shadow (so it sits beyond
 * the clip). Pass an [interactionSource] to enable [StateOverlay].
 */
@Composable
fun FrostSurface(
    layers: List<SurfaceLayer>,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = NxTheme.colors
    val glassIntensity = LocalCustomization.current.glassIntensity
    val softGlow = LocalStyle.current.softGlowEnabled

    val atoms = remember(layers) { layers.flatMap { if (it is Edge) it.toAtoms() else listOf(it) } }
    val glow = remember(atoms, softGlow) { if (softGlow) atoms.filterIsInstance<EdgeGlow>().firstOrNull() else null }
    val panelElevation = LocalStyle.current.panelElevation
    val cast = remember(atoms) { atoms.filterIsInstance<DropShadow>().firstOrNull() }
    // A Fill with nothing opaque or blurred beneath it is a bare glass coat; over a
    // wallpaper on a light palette that lands in mud (Rule 4). "Unbacked" marks that
    // case so such a Fill draws opaque on light -- see the Fill branch below.
    val unbacked = remember(atoms) { atoms.none { it is Backdrop || it is Body } }

    var outer = modifier
    if (cast != null) {
        // Ungated and neutral: this is the plane being above the page, not an
        // emphasis effect. Zero elevation draws nothing, which is what a flat
        // style asks for.
        val elevation = cast.elevationDp?.dp ?: panelElevation
        if (elevation > 0.dp) outer = outer.shadow(elevation, shape, clip = false)
    }
    if (glow != null) {
        val glowColor = colors.frost(glow.role)
        // clip = false so the unclipped hairline overlay below is not re-clipped by
        // the shadow node; the coat's own clip still bounds the body.
        outer = outer.shadow(glow.elevationDp.dp, shape, clip = false, ambientColor = glowColor, spotColor = glowColor)
    }

    Box(outer) {
        // Body + emphasis coat, clipped to the shape. matchParentSize so it fills the
        // surface without driving its size -- content (a sibling below) still does
        // that, so wrap-content cards and weighted panels measure exactly as before.
        Box(Modifier.matchParentSize().clip(shape)) {
            atoms.forEach { layer ->
                when (layer) {
                    is Backdrop -> LocalBackdropPainter.current?.invoke(layer.blurRadiusDp, Modifier.matchParentSize())

                    is Fill -> {
                        val base = colors.frost(layer.role)
                        // Bare + light -> opaque body (no good alpha for a light coat over a busy
                        // wallpaper); otherwise the glass coat, thinning with the intensity knob.
                        // Mirrors glassSurfaceAlpha so a Flat top bar and the rail stay in lockstep.
                        val a = if (unbacked && base.luminance() > 0.5f) 1f else coatAlpha(layer.alpha, glassIntensity)
                        Box(Modifier.matchParentSize().drawBehind { drawRect(base.copy(alpha = a)) })
                    }

                    is Body -> {
                        val c = colors.frost(layer.role).copy(alpha = bodyAlpha(layer.floorAlpha))
                        Box(Modifier.matchParentSize().drawBehind { drawRect(c) })
                    }

                    is Wash -> {
                        val base = colors.frost(layer.role)
                        val stops = listOf(base.copy(alpha = layer.startAlpha), base.copy(alpha = layer.endAlpha))
                        Box(Modifier.matchParentSize().drawBehind {
                            val brush = if (layer.vertical) Brush.verticalGradient(stops) else Brush.horizontalGradient(stops)
                            drawRect(brush)
                        })
                    }

                    is EdgeHighlight -> Box(Modifier.matchParentSize().drawBehind {
                        val band = 6.dp.toPx().coerceAtMost(size.height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = layer.alpha), Color.Transparent),
                                startY = 0f, endY = band,
                            ),
                            size = Size(size.width, band),
                        )
                    })

                    is EdgeShadow -> Box(Modifier.matchParentSize().drawBehind {
                        val band = 8.dp.toPx().coerceAtMost(size.height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = layer.alpha)),
                                startY = size.height - band, endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - band),
                            size = Size(size.width, band),
                        )
                    })

                    // Stroked below as an overlay outside this clip: inside it the
                    // clip's rounded-corner AA ate the thin stroke at the corners.
                    is EdgeBorder -> Unit

                    is Edge -> Unit       // already expanded to atoms before this loop
                    is EdgeGlow -> Unit   // handled as the outer bloom above
                    is DropShadow -> Unit // cast outside the clip, above

                    is Texture -> Box(Modifier.matchParentSize().drawBehind {
                        // Stand-in depth break: a faint diagonal wash. True film grain
                        // wants a Skia RuntimeShader -- a localized future swap.
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = layer.grainAlpha), Color.Transparent, Color.Black.copy(alpha = layer.grainAlpha)),
                            ),
                        )
                    })

                    is StateOverlay -> if (interactionSource != null) {
                        val hovered by interactionSource.collectIsHoveredAsState()
                        val pressed by interactionSource.collectIsPressedAsState()
                        val a = when {
                            pressed -> layer.pressAlpha
                            hovered -> layer.hoverAlpha
                            else    -> 0f
                        }
                        if (a > 0f) {
                            val c = colors.frost(layer.role).copy(alpha = a)
                            Box(Modifier.matchParentSize().drawBehind { drawRect(c) })
                        }
                    }
                }
            }
        }

        content()

        // Hairline last and OUTSIDE the coat's clip, stroked with drawOutline so the
        // full rounded outline (corners included) is drawn once, not trimmed by the
        // container's corner AA that ate it when it lived as a clipped child.
        atoms.forEach { layer ->
            if (layer is EdgeBorder) {
                val c = layer.explicitColor ?: colors.frost(layer.role).copy(alpha = layer.alpha)
                Box(Modifier.matchParentSize().drawBehind {
                    drawOutline(
                        outline = shape.createOutline(size, layoutDirection, this),
                        color   = c,
                        style   = Stroke(width = layer.widthDp.dp.toPx()),
                    )
                })
            }
        }
    }
}
