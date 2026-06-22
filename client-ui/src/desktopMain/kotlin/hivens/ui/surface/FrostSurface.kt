package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import hivens.ui.background.FrostBackdrop
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

/** Hairline border around the surface. */
data class EdgeBorder(val role: FrostRole = FrostRole.Outline, val widthDp: Float = 1f, val alpha: Float = 0.5f) : SurfaceLayer

/** Outer drop shadow / glow -- drawn outside the clip; gated on softGlowEnabled. */
data class EdgeGlow(val role: FrostRole = FrostRole.Primary, val elevationDp: Float = 12f) : SurfaceLayer

/** Subtle texture so large glass areas do not band. */
data class Texture(val grainAlpha: Float = 0.04f) : SurfaceLayer

/** Hover / press state tint; rendered only when an interaction source is given. */
data class StateOverlay(val role: FrostRole = FrostRole.Primary, val hoverAlpha: Float = 0.06f, val pressAlpha: Float = 0.12f) : SurfaceLayer

/** Theme color roles a layer can pull from. Distinct from the persisted
 *  [hivens.ui.customization.ColorRole] string keys -- this maps to live palette
 *  fields for rendering, not to a settings file. */
enum class FrostRole { Surface, SurfaceContainer, SurfaceContainerHigh, Background, Primary, Secondary, Tertiary, Outline }

private fun NxColors.frost(role: FrostRole): Color = when (role) {
    FrostRole.Surface              -> surface
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
enum class FrostTier { Flat, Frosted, Heavy }

fun FrostTier.toLayers(): List<SurfaceLayer> = when (this) {
    FrostTier.Flat    -> listOf(Fill(alpha = 0.35f)) // matches the rail's glassSurfaceAlpha(0.35) for a seamless chrome
    FrostTier.Frosted -> listOf(Backdrop(), Fill(alpha = 0.55f), Edge())
    FrostTier.Heavy   -> listOf(Backdrop(blurRadiusDp = 28f), Fill(alpha = 0.45f), Wash(), Edge(border = true), Texture())
}

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

    var outer = modifier
    if (glow != null) {
        val glowColor = colors.frost(glow.role)
        outer = outer.shadow(glow.elevationDp.dp, shape, ambientColor = glowColor, spotColor = glowColor)
    }

    Box(outer.clip(shape)) {
        atoms.forEach { layer ->
            when (layer) {
                is Backdrop -> FrostBackdrop(extraBlurDp = layer.blurRadiusDp, modifier = Modifier.matchParentSize())

                is Fill -> {
                    val c = colors.frost(layer.role).copy(alpha = (layer.alpha * glassIntensity).coerceIn(0f, 1f))
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

                is EdgeBorder -> {
                    val c = colors.frost(layer.role).copy(alpha = layer.alpha)
                    Box(Modifier.matchParentSize().border(layer.widthDp.dp, c, shape))
                }

                is Edge -> Unit     // already expanded to atoms before this loop
                is EdgeGlow -> Unit // handled as the outer drop shadow above

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
        content()
    }
}
