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
 *   Backdrop (blur of what is beneath) -> Body / Fill -> State -> content ->
 *   EdgeBorder, with DropShadow cast outside the clip.
 *
 * Structure (the layer list) is orthogonal to color (resolved from theme roles),
 * so a seeded palette and a chosen depth compose independently. The old
 * [hivens.ui.customization.glassSurfaceAlpha] is just a single [Fill] of the
 * surface role -- call sites migrate to [FrostSurface] over time, not at once.
 *
 * Six further layer kinds used to live here. Five -- an accent Wash, an Edge group
 * and the three atoms it expanded into -- were constructed nowhere at all. The
 * sixth was Texture, which was not one: a white-to-black diagonal gradient at four
 * percent, documented as keeping large glass areas from banding, which a gradient
 * cannot do. Banding IS a quantised gradient; only noise breaks it, and the shader
 * that does (DitherVeil, in client-ui) was already written and in use elsewhere.
 * All six are gone rather than kept as options nobody could reach or that did not
 * do what they said.
 */
sealed interface SurfaceLayer

/** Blurs whatever lies beneath the surface, clipped to its shape. See [BackdropBlur]
 *  for what "beneath" can and cannot see. */
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

/** Hairline border around the surface. With [explicitColor] set, that color is used
 *  verbatim (the luminance-derived bevel path); otherwise [role] @ [alpha] resolves. */
data class EdgeBorder(
    val role: FrostRole = FrostRole.Outline,
    val widthDp: Float = 1f,
    val alpha: Float = 0.5f,
    val explicitColor: Color? = null,
) : SurfaceLayer

/**
 * A cast shadow: neutral, outside the clip, under the whole plane.
 *
 * [elevationDp] null takes the active style's panel elevation, which is how the
 * form axis gets to decide: Brut sets it to zero and the plane sits flat, with
 * no separate switch to keep in sync.
 */
data class DropShadow(val elevationDp: Float? = null) : SurfaceLayer

/** A [Body] whose colour is given rather than resolved from a role. For a colour that
 *  arrives as data -- a person's own choice in their own layout -- which no palette
 *  can supply. Everything else about the plane behaves the same. */
data class BodyColor(val color: Color, val alpha: Float = 1f) : SurfaceLayer

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
 * The presets carry a body, an optional blur, and a cast shadow, and nothing else.
 */
fun FrostTier.toLayers(): List<SurfaceLayer> = when (this) {
    // Clear is the transparent tier: a lone glass coat, no body. NxSurface renders it
    // bodiless (see NxSurface); a raw FrostSurface just draws the fill, same as Flat.
    FrostTier.Clear   -> listOf(Fill(alpha = 0.35f))
    FrostTier.Flat    -> listOf(Fill(alpha = 0.35f)) // matches the rail's glassSurfaceAlpha(0.35) for a seamless chrome
    FrostTier.Frosted -> listOf(Backdrop(), Fill(alpha = 0.55f), DropShadow())
    FrostTier.Heavy   -> listOf(Backdrop(blurRadiusDp = 28f), Fill(alpha = 0.45f), DropShadow())
}

/** A [Body]'s alpha is the slider-independent floor (Rule 2): the plane must read
 *  even when the glass coat is gone. No glassIntensity term -- that is the point. */
internal fun bodyAlpha(floorAlpha: Float): Float = floorAlpha.coerceIn(0f, 1f)

/** A [Fill]'s alpha is the optional glass coat: it scales with the user's
 *  glass-intensity knob and thins to nothing at intensity 0. */
internal fun coatAlpha(baseAlpha: Float, glassIntensity: Float): Float =
    (baseAlpha * glassIntensity).coerceIn(0f, 1f)

/**
 * Renders [layers] bottom-to-top behind [content]. A [DropShadow] is cast outside
 * the clip and an [EdgeBorder] is stroked over it; everything else draws inside.
 * Pass an [interactionSource] to enable [StateOverlay].
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
    val panelElevation = LocalStyle.current.panelElevation
    val cast = remember(layers) { layers.filterIsInstance<DropShadow>().firstOrNull() }
    // A Fill with nothing opaque or blurred beneath it is a bare glass coat; over a
    // wallpaper on a light palette that lands in mud (Rule 4). "Unbacked" marks that
    // case so such a Fill draws opaque on light -- see the Fill branch below.
    val unbacked = remember(layers) { layers.none { it is Backdrop || it is Body || it is BodyColor } }

    var outer = modifier
    if (cast != null) {
        // Ungated and neutral: this is the plane being above the page, not an
        // emphasis effect. Zero elevation draws nothing, which is what a flat
        // style asks for.
        val elevation = cast.elevationDp?.dp ?: panelElevation
        if (elevation > 0.dp) outer = outer.shadow(elevation, shape, clip = false)
    }

    Box(outer) {
        // Body + emphasis coat, clipped to the shape. matchParentSize so it fills the
        // surface without driving its size -- content (a sibling below) still does
        // that, so wrap-content cards and weighted panels measure exactly as before.
        Box(Modifier.matchParentSize().clip(shape)) {
            layers.forEach { layer ->
                when (layer) {
                    is Backdrop -> BackdropBlur(layer.blurRadiusDp, Modifier.matchParentSize())

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

                    is BodyColor -> {
                        val c = layer.color.copy(alpha = bodyAlpha(layer.alpha))
                        Box(Modifier.matchParentSize().drawBehind { drawRect(c) })
                    }

                    // Stroked below as an overlay outside this clip: inside it the
                    // clip's rounded-corner AA ate the thin stroke at the corners.
                    is EdgeBorder -> Unit
                    is DropShadow -> Unit // cast outside the clip, above

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
        layers.forEach { layer ->
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
