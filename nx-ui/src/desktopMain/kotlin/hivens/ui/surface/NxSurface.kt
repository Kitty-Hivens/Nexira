package hivens.ui.surface

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.theme.Form
import hivens.ui.theme.NxColors
import hivens.ui.theme.NxTheme
import hivens.ui.theme.bevelHairline
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Canvas as SkCanvas

/**
 * Depth of a plane relative to the page. A caller assigns a level; nx-ui maps it to a
 * tonal colour, a luminance-derived bevel hairline, and the body floor. A screen never
 * picks an alpha or a colour -- only a level.
 */
enum class NxSurfaceLevel { Sunken, Base, Raised, Floating }

/**
 * Tonal-ladder colour per level. Adjacent levels always carry a tone step; the bevel
 * hairline supplies the second separation signal regardless of its size.
 *
 * Monotonic on dark (L* 8.2 / 11.3 / 13.2 / 17.1 -- deeper reads darker all the way
 * up) and monotonic on light in the same direction (97.0 / 94.1 / 91.3 / 87.8), which
 * is the thing to know about it: the light page is white at L* 100 and every rung
 * descends from it, so Sunken is the LIGHTEST plane there rather than the deepest.
 * A field at Sunken inside a section at Floating is a well on dark and a plate on
 * light. Nesting still steps -- each rung is 3 L* or so from the next, in one
 * direction -- so a plane inside a plane reads correctly on both; what does not carry
 * over is the word: on light the levels are rungs, not depths.
 *
 * The figures are the palette's, and they move when it does. They were carried
 * forward once without being recomputed and were out by up to 3 L*.
 *
 * A level names a colour directly. There used to be an enum of theme ROLES in between,
 * so a level resolved to a role and a role to a field -- two hops and a vocabulary of
 * nine roles, of which the five that were not ladder rungs existed only for layer
 * kinds that no longer exist.
 */
fun NxSurfaceLevel.color(colors: NxColors): Color = when (this) {
    NxSurfaceLevel.Sunken   -> colors.surfaceContainerLow
    NxSurfaceLevel.Base     -> colors.surface
    NxSurfaceLevel.Raised   -> colors.surfaceContainer
    NxSurfaceLevel.Floating -> colors.surfaceContainerHigh
}

/**
 * The body opacity a surface gets when it asks for none.
 *
 * A translucent light plane over a wallpaper lands in mud, so light defaults to
 * solid and dark keeps a hair of bleed-through. This is a DEFAULT, not a clamp: a
 * caller that names an opacity gets the one it named on either theme. It stopped
 * being a clamp because as one it silently overrode every knob above it -- the
 * glass slider moved nothing, and the layer that was supposed to show the
 * wallpaper through was covered by 92% of solid body before it ever drew.
 *
 * Public because an editor showing a surface's values has to show this one too. A
 * control that opens at a number the renderer never used is the same defect as a
 * control that moves nothing.
 */
fun bodyFloor(dark: Boolean): Float = if (dark) 0.92f else 1.0f

/**
 * A library-owned surface: a tonal body for [level], an optional blur of what is behind
 * it, a cast shadow and a luminance-derived bevel hairline.
 *
 * Every value it draws with is a number. It used to take a preset as well, which moved
 * the body's opacity, the blur radius and the cast shadow together, so no one of the
 * three could be set without the other two; and three booleans beside them that each
 * stood for a number the caller could not otherwise write -- solid, lifted, edged.
 *
 * ONE layout node. It used to be five: a compositor took a `List<SurfaceLayer>` and
 * gave every layer a `Box(matchParentSize().drawBehind {})` of its own -- an Android
 * layer-list drawable transliterated into Compose. The list was a private handshake
 * between this function and that one, allocated per recomposition, and nothing else
 * ever built a layer. What it cost was not only the nodes: content had to be a SIBLING
 * of the box that carried the clip, so a surface clipped its own fill to its shape and
 * let the widget inside it overflow the corners. Drawing it here in order -- backdrop,
 * body, state, content, edge -- puts content inside the clip, which is what a rounded
 * plane means.
 *
 * The hairline is stroked outside that clip, over everything. A stroke is centred on
 * the outline, so half of it falls outside the shape; clipping it leaves a half-width
 * line that the corner antialiasing then eats entirely.
 */
@Composable
fun NxSurface(
    level: NxSurfaceLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Form.cardCorner),
    /**
     * Body opacity, 0..1. Null takes [bodyFloor], which is what an unconfigured
     * surface has always drawn; 1 is a plane nothing reads through, which is what an
     * overlay like [hivens.ui.nx.NxContextMenu] needs over arbitrary content. A named
     * value reaches the body unchanged on both themes -- including values light used
     * to refuse, because whether a light plane can afford to be translucent depends
     * on what is behind it and on what the widget buys legibility with, and neither
     * is knowable here.
     */
    opacity: Float? = null,
    /**
     * How far the plane blurs what is behind it. Null takes the active style's
     * [hivens.ui.theme.StyleSpec.surfaceBlur], zero turns it off. It was a preset's
     * private constant before, so the two values in use were unreachable from
     * anywhere and indistinguishable from each other on screen.
     */
    blurDp: Float? = null,
    /** Hairline width. Zero removes it. */
    borderWidthDp: Float = 1f,
    /** Hairline colour. Null derives one from the body's own luminance, which reads as
     *  a bevelled edge of the same material rather than a frame in a foreign colour. */
    borderColor: Color? = null,
    /**
     * Cast-shadow elevation. Zero is a plane flat on the page, which is what most
     * of them are. A caller that wants the depth its style chose for floating panels
     * passes [hivens.ui.theme.StyleSpec.panelElevation], so a flat form still takes
     * it to nothing without a second switch to keep in step.
     */
    shadowDp: Float = 0f,
    /**
     * An explicit body colour, for a colour that is DATA rather than a guess.
     *
     * [NxColorSurface] withholds this and says why: a screen picking its own tone is
     * the per-screen drift #351 closed, and it stays withheld for that. What arrives
     * here is a colour a person typed into their own layout, which no palette can
     * supply and no tonal ladder should override. Null keeps [level] deciding, which
     * is what every call site inside the app does.
     */
    fillColor: Color? = null,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = NxTheme.colors
    val bodyColor = fillColor ?: level.color(colors)
    val dark = bodyColor.luminance() < 0.5f
    val body = bodyColor.copy(alpha = opacity?.coerceIn(0f, 1f) ?: bodyFloor(dark))
    // A named radius wins; absent one, the active style decides. A blur under a body
    // nothing can see through is work thrown away -- the filter runs every frame and
    // is then covered completely -- so an opaque surface asks for none.
    val blur = blurDp ?: Form.surfaceBlur.value
    val backdrop = rememberBackdropFilter(if (body.alpha < 1f) blur else 0f)
    val edge = if (borderWidthDp > 0f) borderColor ?: bevelHairline(bodyColor) else null

    // Held as state and read in the draw lambda, not here: hovering a card would
    // otherwise recompose it and everything it contains, to change one rectangle.
    val hovered = interactionSource?.collectIsHoveredAsState()
    val pressed = interactionSource?.collectIsPressedAsState()
    val stateTint = colors.primary

    Box(
        modifier
            .then(if (shadowDp > 0f) Modifier.shadow(shadowDp.dp, shape, clip = false) else Modifier)
            // Outside the clip, above the content: see the note on the hairline above.
            .then(
                if (edge == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = edge,
                            style = Stroke(width = borderWidthDp.dp.toPx()),
                        )
                    }
                },
            )
            .clip(shape)
            .drawBehind {
                if (backdrop != null) drawBackdrop(backdrop)
                drawRect(body)
                val alpha = when {
                    pressed?.value == true -> PRESS_ALPHA
                    hovered?.value == true -> HOVER_ALPHA
                    else -> 0f
                }
                if (alpha > 0f) drawRect(stateTint.copy(alpha = alpha))
            },
        content = content,
    )
}

private const val HOVER_ALPHA = 0.06f
private const val PRESS_ALPHA = 0.12f

/** Card-shaped plane (defaults to [NxSurfaceLevel.Raised] + the card-corner token).
 *  The default surface for content cards. */
@Composable
fun NxCard(
    modifier: Modifier = Modifier,
    level: NxSurfaceLevel = NxSurfaceLevel.Raised,
    shape: Shape = RoundedCornerShape(Form.cardCorner),
    /** Body opacity, as [NxSurface.opacity]. */
    opacity: Float? = null,
    /** Blur radius, as [NxSurface.blurDp]. Zero is what a card inside an already
     *  blurred panel wants: a second blur of the first buys nothing but the cost. */
    blurDp: Float? = null,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) = NxSurface(
    level = level,
    modifier = modifier,
    shape = shape,
    opacity = opacity,
    blurDp = blurDp,
    interactionSource = interactionSource,
    content = content,
)

/**
 * The Skia filter that blurs whatever is already on the canvas beneath the surface.
 *
 * `saveLayer` with a backdrop filter seeds a new layer with a filtered copy of the
 * destination, then composites it straight back. Compose has no modifier for this;
 * Skia does, and skiko exposes it, so the whole operation is one call in the draw phase
 * and needs no knowledge of what it is blurring. That last part is the point: it used
 * to be answered by redrawing the wallpaper at the surface's offset, an answer narrower
 * than the question, which is why a plane over another plane showed the wallpaper it
 * could not see rather than the plane it covered.
 *
 * Two things are worth knowing before relying on it.
 *
 * A backdrop filter reads the CURRENT layer. Any ancestor with alpha below 1 puts the
 * surface in an offscreen layer of its own, and the filter then finds it empty and
 * draws nothing. Alpha exactly 1 creates no layer, and scale, rotation and clipping do
 * not isolate; only alpha does. In this shell that means chrome always blurs, and
 * content inside a screen being swapped loses its blur for the length of the fade it is
 * already disappearing into.
 *
 * The result is not cacheable, because the destination it filters can change every
 * frame and Skia has no way to know that it did not. That is inherent to the operation
 * rather than a property of this implementation, which is why
 * [hivens.ui.customization.CustomizationSettings.surfaceBlur] switches the whole thing
 * off in one place instead of per surface.
 */
@Composable
private fun rememberBackdropFilter(radiusDp: Float): ImageFilter? {
    if (radiusDp <= 0f || !LocalCustomization.current.surfaceBlur) return null
    val density = LocalDensity.current
    // One native filter per radius per surface, not one per frame: building it inside
    // the draw lambda allocates a Skia object on every pass and charges the technique
    // for the caller's mistake.
    val filter = remember(radiusDp, density) {
        val sigma = with(density) { radiusDp.dp.toPx() }
        ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
    }
    DisposableEffect(filter) { onDispose { filter.close() } }
    return filter
}

private fun DrawScope.drawBackdrop(filter: ImageFilter) {
    drawIntoCanvas { canvas ->
        val native = canvas.skiaCanvas
        native.saveLayer(SkCanvas.SaveLayerRec(bounds = Rect.makeWH(size.width, size.height), backdrop = filter))
        native.restore()
    }
}
