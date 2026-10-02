package hivens.ui.surface

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hivens.ui.customization.LocalCustomization
import hivens.ui.theme.Backdrop
import hivens.ui.theme.LocalPlane
import hivens.ui.theme.LocalScheme
import hivens.ui.theme.Plane
import hivens.ui.theme.bevelHairline
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Canvas as SkCanvas

/**
 * Where [kind] sits on the ladder, given the step of the plane holding it.
 *
 * Relative for everything that lives inside something, so a card is above its panel
 * whatever step the panel is on. Floating kinds go to the top of what the theme
 * authored, or one above their holder if that is higher.
 */
internal fun surfaceStep(kind: SurfaceKind, parentStep: Int, topStep: Int): Int = when (kind) {
    SurfaceKind.Page -> 0
    SurfaceKind.Chrome -> parentStep
    SurfaceKind.Panel, SurfaceKind.Card -> parentStep + 1
    SurfaceKind.Field -> parentStep - 1
    SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice -> maxOf(topStep, parentStep + 1)
}

/**
 * A library-owned surface: [kind] decides its step, its material and what its content
 * reads against, and every number it draws with can still be named by a caller that
 * has a reason, which is what a user's own widget plane is.
 *
 * A body is opaque by default and never blurs what it covers, since nothing can see
 * through it. [SurfaceKind.Chrome] frames what it holds in the step above it: glass
 * over a picture, a thin coat of that step with a blur when one is asked for, and a
 * solid body over the bare page, where glass would show nothing and so could not be
 * told from the page at all.
 *
 * Content is handed a [Plane] for this surface and the main ink for it, so anything
 * inside reads against the colour it is really on.
 *
 * ONE layout node. Drawing it in order -- backdrop, body, state, content, edge -- puts
 * content inside the clip, which is what a rounded plane means. The hairline is
 * stroked outside that clip, over everything: a stroke is centred on the outline, so
 * half of it falls outside the shape, and clipping it leaves a half-width line the
 * corner antialiasing then eats.
 */
@Composable
fun NxSurface(
    kind: SurfaceKind,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    /** Body opacity, 0..1. Null is the kind's own: solid for a body, a thin coat for glass. */
    opacity: Float? = null,
    /** How far the surface blurs what is behind it. Null is the kind's own: none for a body. */
    blurDp: Float? = null,
    /** Hairline width. Null is the kind's own; zero removes it. */
    borderWidthDp: Float? = null,
    /** Hairline colour. Null derives a bevel from the body's own luminance. */
    borderColor: Color? = null,
    /** Cast-shadow elevation. Null is the kind's own: only floating kinds cast one. */
    shadowDp: Float? = null,
    /**
     * An explicit body colour, for a colour that is DATA: one a person typed into their
     * own layout, which no theme can supply. Null keeps [kind] deciding, which is what
     * every call site inside the app does.
     */
    fillColor: Color? = null,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = LocalScheme.current
    val parent = LocalPlane.current ?: Plane(0, scheme.step(0), Backdrop.Page)
    val glass = kind == SurfaceKind.Chrome
    val step = surfaceStep(kind, parent.step, scheme.topStep)

    // Chrome adds no depth to what it holds, but is drawn in the step above it, which
    // is what keeps a rail and a title bar apart from the page they frame.
    val material = fillColor ?: scheme.step(if (glass) parent.step + 1 else step)
    val alpha = (opacity ?: kind.defaultOpacity(parent.over)).coerceIn(0f, 1f)
    val body = material.copy(alpha = material.alpha * alpha)
    // A blur under a body nothing can see through is work thrown away: the filter runs
    // every frame and is then covered completely.
    val blur = blurDp ?: if (glass) DEFAULT_BLUR_DP else 0f
    val backdrop = rememberBackdropFilter(if (body.alpha < 1f) blur else 0f)
    val edgeWidth = borderWidthDp ?: kind.defaultEdgeDp
    val edge = if (edgeWidth > 0f) borderColor ?: bevelHairline(material) else null
    val shadow = shadowDp ?: kind.defaultShadowDp

    // What content actually sits on: the body over its holder, as far as the body lets
    // the holder through.
    val under = lerp(parent.color.copy(alpha = 1f), material.copy(alpha = 1f), body.alpha)
    val plane = if (glass) Plane(parent.step, under, parent.over) else Plane(step, under, Backdrop.Plane)

    // Held as state and read in the draw lambda, not here: hovering a card would
    // otherwise recompose it and everything it contains, to change one rectangle.
    val hovered = interactionSource?.collectIsHoveredAsState()
    val pressed = interactionSource?.collectIsPressedAsState()
    val stateTint = scheme.lead

    Box(
        modifier
            .then(if (shadow > 0f) Modifier.shadow(shadow.dp, shape, clip = false) else Modifier)
            .then(
                if (edge == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        drawOutline(
                            outline = shape.createOutline(size, layoutDirection, this),
                            color = edge,
                            style = Stroke(width = edgeWidth.dp.toPx()),
                        )
                    }
                },
            )
            .clip(shape)
            .drawBehind {
                if (backdrop != null) drawBackdrop(backdrop)
                drawRect(body)
                val stateAlpha = when {
                    pressed?.value == true -> PRESS_ALPHA
                    hovered?.value == true -> HOVER_ALPHA
                    else -> 0f
                }
                if (stateAlpha > 0f) drawRect(stateTint.copy(alpha = stateAlpha))
            },
    ) {
        CompositionLocalProvider(
            LocalPlane provides plane,
            LocalContentColor provides scheme.inkMain(plane.color),
        ) {
            content()
        }
    }
}

private val SurfaceKind.defaultEdgeDp: Float
    get() = when (this) {
        SurfaceKind.Page, SurfaceKind.Chrome -> 0f
        else -> 1f
    }

private val SurfaceKind.defaultShadowDp: Float
    get() = when (this) {
        SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice -> 6f
        else -> 0f
    }

private const val HOVER_ALPHA = 0.06f
private const val PRESS_ALPHA = 0.12f

/** How much of its own colour glass lays over what is behind it when nothing names a number. */
private const val GLASS_COAT = 0.35f

/**
 * The opacity a surface of this kind draws at when it names none, over [over]: solid
 * for a body, and for chrome a thin coat over a picture and solid over the bare page.
 * Public because an editor showing a surface's values has to open on this one too: a
 * control that starts at a number the renderer never used is the same defect as a
 * control that moves nothing.
 */
fun SurfaceKind.defaultOpacity(over: Backdrop = Backdrop.Page): Float =
    if (this == SurfaceKind.Chrome && over == Backdrop.Wallpaper) GLASS_COAT else 1f

/** Card-shaped surface: [SurfaceKind.Card] at the card corner. */
@Composable
fun NxCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) = NxSurface(
    kind = SurfaceKind.Card,
    modifier = modifier,
    shape = shape,
    interactionSource = interactionSource,
    content = content,
)

/**
 * The Skia filter that blurs whatever is already on the canvas beneath the surface.
 *
 * `saveLayer` with a backdrop filter seeds a new layer with a filtered copy of the
 * destination, then composites it straight back. Compose has no modifier for this;
 * Skia does, and skiko exposes it, so the whole operation is one call in the draw phase
 * and needs no knowledge of what it is blurring.
 *
 * A backdrop filter reads the CURRENT layer. Any ancestor with alpha below 1 puts the
 * surface in an offscreen layer of its own, and the filter then finds it empty and
 * draws nothing. Alpha exactly 1 creates no layer, and scale, rotation and clipping do
 * not isolate; only alpha does.
 *
 * The result is not cacheable, because the destination it filters can change every
 * frame, which is why [hivens.ui.customization.CustomizationSettings.surfaceBlur]
 * switches the whole thing off in one place instead of per surface.
 */
@Composable
private fun rememberBackdropFilter(radiusDp: Float): ImageFilter? {
    if (radiusDp <= 0f || !LocalCustomization.current.surfaceBlur) return null
    val density = LocalDensity.current
    // One native filter per radius per surface, not one per frame.
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

/** How far glass blurs what is behind it when it names no radius. */
private const val DEFAULT_BLUR_DP = 18f
