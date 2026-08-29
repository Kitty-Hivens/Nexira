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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle

/**
 * The layered-surface system. A surface is NOT one translucent pane -- it is an
 * ordered stack of atomic layers drawn behind content, so transparency always has
 * separation and the surface reads as a distinct plane:
 *
 *   Backdrop (blur of what is beneath) -> Body -> State -> content -> EdgeBorder,
 *   with DropShadow cast outside the clip.
 *
 * Structure (the layer list) is orthogonal to color (resolved from theme roles),
 * so a seeded palette and a chosen depth compose independently.
 *
 * Eight further layer kinds used to live here. Five -- an accent Wash, an Edge
 * group and the three atoms it expanded into -- were constructed nowhere at all.
 * The sixth was Texture, which was not one: a white-to-black diagonal gradient at
 * four percent, documented as keeping large glass areas from banding, which a
 * gradient cannot do. Banding IS a quantised gradient; only noise breaks it, and
 * the shader that does (DitherVeil, in client-ui) was already written and in use
 * elsewhere.
 *
 * The last two were Fill and the tier that built it: a second translucent coat
 * over the body, whose alpha rode the same knob as the blur. That coupling is the
 * bug this rewrite exists to remove -- opacity and blur are separate values now,
 * and one body carries the whole of the first.
 */
sealed interface SurfaceLayer

/** Blurs whatever lies beneath the surface, clipped to its shape. See [BackdropBlur]
 *  for what "beneath" can and cannot see. */
data class Backdrop(val blurRadiusDp: Float = 18f) : SurfaceLayer

/** The tonal BODY of a surface: the one fill a plane has, at exactly the opacity
 *  it was given. A surface that names nothing gets the level's default rather than
 *  a floor, so turning it down reaches the pixel. */
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

/** A [Body]'s alpha, clamped and otherwise untouched. Nothing scales it: a number
 *  a caller wrote is the number that reaches the pixel. */
internal fun bodyAlpha(floorAlpha: Float): Float = floorAlpha.coerceIn(0f, 1f)

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
    val panelElevation = LocalStyle.current.panelElevation
    val cast = remember(layers) { layers.filterIsInstance<DropShadow>().firstOrNull() }

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
