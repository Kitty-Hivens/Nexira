package hivens.ui.surface

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.bevelHairline

/**
 * Depth of a plane relative to the page. A caller assigns a level; nx-ui maps it to a
 * tonal role, a luminance-derived bevel hairline, and the body floor. A screen never
 * picks an alpha or a color -- only a level.
 */
enum class NxSurfaceLevel { Sunken, Base, Raised, Floating }

/**
 * Tonal-ladder role per level. Adjacent levels always carry a tone step; the bevel
 * hairline supplies the second separation signal regardless of its size.
 *
 * Monotonic on dark only (L* 8.3 / 11.3 / 13.2 / 17.1 -- deeper reads darker all the
 * way up). The light ladder cannot be: its page IS the lightest surface there is
 * (Base is white, L* 100), so every other level descends from it and depth runs the
 * same direction on both sides -- Sunken 95.5, Raised 93.4, Floating 90.9. A recessed
 * plane and a lifted one therefore land within 2 L* of each other and are told apart
 * by the hairline rather than by tone. Nesting still steps correctly, which is what
 * the ladder is mostly asked for; two sibling planes at different depths do not.
 */
fun NxSurfaceLevel.role(): FrostRole = when (this) {
    NxSurfaceLevel.Sunken   -> FrostRole.SurfaceContainerLow
    NxSurfaceLevel.Base     -> FrostRole.Surface
    NxSurfaceLevel.Raised   -> FrostRole.SurfaceContainer
    NxSurfaceLevel.Floating -> FrostRole.SurfaceContainerHigh
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
 */
internal fun bodyFloor(dark: Boolean): Float = if (dark) 0.92f else 1.0f

/**
 * A library-owned surface: a tonal body for [level], an optional blur of what is
 * behind it, and a luminance-derived bevel hairline -- composited via [FrostSurface].
 *
 * Every value it draws with is named. It used to take a preset as well, which moved
 * the body's opacity, the blur radius and the cast shadow together, so no one of the
 * three could be set without the other two. They are separate parameters now.
 *
 * [opaque] forces a fully solid body on every theme (dark otherwise keeps a hair of
 * bleed-through, [bodyFloor]) -- for overlays like [hivens.ui.nx.NxContextMenu] that
 * float over arbitrary content and must not let it read through.
 */
@Composable
fun NxSurface(
    level: NxSurfaceLevel,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalStyle.current.cardCorner),
    hairline: Boolean = true,
    opaque: Boolean = false,
    /**
     * Whether the plane sits above the page and casts a shadow for it. A separate
     * question from how much it blurs, which one preset used to answer for both:
     * a chrome rail could not be plain without also being flat on the page, nor
     * lifted without also being frosted. The elevation itself comes from the active
     * style, so a flat form takes it to nothing.
     */
    elevated: Boolean = false,
    /**
     * Body opacity, 0..1. Null takes [bodyFloor], which is what an unconfigured
     * surface has always drawn. A named value reaches the body unchanged on both
     * themes -- including values light used to refuse, because whether a light
     * plane can afford to be translucent depends on what is behind it and on what
     * the widget buys legibility with, and neither is knowable here.
     */
    opacity: Float? = null,
    /**
     * How far the plane blurs what is behind it. Null takes the active style's
     * [hivens.ui.theme.StyleSpec.surfaceBlur], zero turns it off. It was a preset's
     * private constant before, so the two values in use were unreachable from
     * anywhere and indistinguishable from each other on screen.
     */
    blurDp: Float? = null,
    /** Hairline width. Null defers to [hairline], zero removes it. */
    borderWidthDp: Float? = null,
    /** Hairline colour. Null derives one from the body's own luminance, which reads as
     *  a bevelled edge of the same material rather than a frame in a foreign colour. */
    borderColor: Color? = null,
    /** Cast-shadow elevation. Null defers to [elevated], zero flattens the plane. */
    shadowDp: Float? = null,
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
    val role = level.role()
    val bodyColor = fillColor ?: frostColor(role)
    val dark = bodyColor.luminance() < 0.5f
    val bodyAlpha = when {
        opaque          -> 1f
        opacity != null -> opacity.coerceIn(0f, 1f)
        else            -> bodyFloor(dark)
    }
    // A named radius wins; absent one, the active style decides.
    val blur = blurDp ?: LocalStyle.current.surfaceBlur.value
    val layers = buildList {
        // A blur under a body nothing can see through is work thrown away: the
        // filter runs every frame and is then covered completely. An opaque
        // surface skips it.
        if (bodyAlpha < 1f && blur > 0f) add(Backdrop(blur))
        if (fillColor != null) add(BodyColor(fillColor, bodyAlpha)) else add(Body(role, bodyAlpha))
        when {
            shadowDp != null && shadowDp > 0f -> add(DropShadow(shadowDp))
            shadowDp != null                  -> Unit // a named zero is a flat plane
            elevated                          -> add(DropShadow())
        }
        when {
            borderWidthDp != null && borderWidthDp > 0f ->
                add(EdgeBorder(widthDp = borderWidthDp, explicitColor = borderColor ?: bevelHairline(bodyColor)))
            borderWidthDp != null -> Unit // a named zero is an edgeless plane
            hairline              -> add(EdgeBorder(explicitColor = borderColor ?: bevelHairline(bodyColor)))
        }
        if (interactionSource != null) add(StateOverlay())
    }

    FrostSurface(layers, modifier, shape, interactionSource, content)
}

/** Card-shaped plane (defaults to [NxSurfaceLevel.Raised] + the card-corner token).
 *  The default surface for content cards. */
@Composable
fun NxCard(
    modifier: Modifier = Modifier,
    level: NxSurfaceLevel = NxSurfaceLevel.Raised,
    shape: Shape = RoundedCornerShape(LocalStyle.current.cardCorner),
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
    hairline = true,
    opacity = opacity,
    blurDp = blurDp,
    interactionSource = interactionSource,
    content = content,
)
