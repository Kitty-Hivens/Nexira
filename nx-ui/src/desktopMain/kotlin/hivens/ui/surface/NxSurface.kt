package hivens.ui.surface

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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

/** The slider-independent body floor. Light theme is fully opaque, so a translucent
 *  surface can never muddy over a wallpaper; dark keeps a hair of bleed-through. */
internal fun bodyFloor(dark: Boolean): Float = if (dark) 0.92f else 1.0f

/** A tier's glass coat: its decorative layers minus the body [Fill] (the [Body] owns
 *  the fill) and with any [Edge] border off (the bevel hairline owns the border). */
private fun FrostTier.coatLayers(): List<SurfaceLayer> = toLayers().mapNotNull { layer ->
    when (layer) {
        is Fill -> null
        is Edge -> layer.copy(border = false)
        else    -> layer
    }
}

/**
 * A library-owned surface: an opaque tonal body for [level], an optional glass coat,
 * and a luminance-derived bevel hairline -- composited via [FrostSurface]. The body
 * survives the coat coming off (light theme, no wallpaper, glassIntensity 0), so the
 * plane never collapses into the page.
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
    glass: Boolean = true,
    tier: FrostTier = FrostTier.Frosted,
    hairline: Boolean = true,
    opaque: Boolean = false,
    /**
     * Whether the plane sits above the page and casts a shadow for it. Separate
     * from [tier] on purpose: how much a surface blurs and whether it is lifted
     * are different questions, and a tier that answered both meant a chrome rail
     * could not be plain without also being flat on the page, nor lifted without
     * also being frosted. The elevation itself comes from the active style, so a
     * flat form takes it to nothing.
     */
    elevated: Boolean = false,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Clear is the transparent tier: no body, no bevel, just the thin glass coat.
    // On dark it reads as glass over the wallpaper; on light FrostSurface floors the
    // unbacked Fill to opaque (Rule 4). It tints from the Fill's own Surface role @0.35,
    // NOT the [level] ladder role -- [level] is inert there -- keeping exact parity with
    // the old glassSurfaceAlpha(0.35) chrome. It fades on dark with no wallpaper: the
    // deliberate, chosen cost of Clear.
    //
    // [opaque] overrides that. A surface that must not let content read through is not
    // asking for a coat at all, so Clear plus opaque is the plainest plane the library
    // has: the tonal body, the bevel hairline, and whatever depth the tier casts. No
    // fill over a fill, no blur under something solid, nothing to tune.
    if (tier == FrostTier.Clear && !opaque) {
        val bare = buildList {
            if (glass) addAll(tier.toLayers())
            if (elevated) add(DropShadow())
            // The edge belongs here too. On light the Fill above is floored to
            // opaque, so this branch draws a solid plane -- and it was the only one
            // that drew a solid plane with nothing on its boundary. That covers the
            // left rail and the top bar, which both default to Clear: the whole
            // chrome met the page on a tone step alone. Derived from the Fill's own
            // Surface role, since [level] is inert on this branch.
            if (hairline && glass) add(EdgeBorder(explicitColor = bevelHairline(frostColor(FrostRole.Surface))))
        }
        FrostSurface(bare, modifier, shape, interactionSource, content)
        return
    }

    val role = level.role()
    val bodyColor = frostColor(role)
    val dark = bodyColor.luminance() < 0.5f
    val coat = if (glass) tier.coatLayers() else emptyList()

    val bodyAlpha = if (opaque) 1f else bodyFloor(dark)
    val layers = buildList {
        // A blur under a body nothing can see through is work thrown away: the
        // wallpaper slice gets sampled and blurred every frame and is then
        // covered completely. An opaque surface skips it.
        if (bodyAlpha < 1f) addAll(coat.filterIsInstance<Backdrop>())
        add(Body(role, bodyAlpha))
        // A translucent coat over a solid body is a second fill doing nothing the
        // first did not: it only shifts the tone the ladder already chose.
        if (bodyAlpha < 1f) addAll(coat.filterNot { it is Backdrop })
        else addAll(coat.filterNot { it is Backdrop || it is Fill })
        // Authoritative in both directions. A preset carries its own DropShadow, so
        // testing only for absence meant Frosted and Heavy were always lifted and
        // the flag could add a shadow but never remove one -- half of the split it
        // exists to make.
        removeAll { it is DropShadow }
        if (elevated) add(DropShadow())
        if (hairline) add(EdgeBorder(explicitColor = bevelHairline(bodyColor)))
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
    glass: Boolean = true,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) = NxSurface(level, modifier, shape, glass, FrostTier.Frosted, hairline = true, interactionSource = interactionSource, content = content)
