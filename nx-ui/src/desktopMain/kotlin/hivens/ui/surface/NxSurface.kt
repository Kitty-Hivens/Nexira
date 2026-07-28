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
 * Tonal-ladder role per level, in the colour science's own order rather than the one
 * the hand-written palettes happened to have. `surface` is the DIMMEST plane there
 * and the containers climb away from it, so mapping the lowest level onto a
 * container put a sunken plane above a base one -- invisible while the palette was
 * hand-picked to our ordering, and inverted the moment it was generated.
 *
 * Monotonic by construction now, and every level except the sunken one sits ABOVE
 * the ground: `surface` is what the background aliases, so a base plane mapped onto
 * it would be flush and therefore invisible -- correct by the specification and
 * useless for cards, which is what our base level actually carries.
 */
fun NxSurfaceLevel.role(): FrostRole = when (this) {
    NxSurfaceLevel.Sunken   -> FrostRole.SurfaceContainerLowest
    NxSurfaceLevel.Base     -> FrostRole.SurfaceContainerLow
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
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    // Clear is the transparent tier: no opaque body, no bevel -- just the thin glass coat.
    // On dark it reads as glass over the wallpaper; on light FrostSurface floors the
    // unbacked Fill to opaque (Rule 4). It tints from the Fill's own Surface role @0.35,
    // NOT the [level] ladder role -- [level] is inert here -- keeping exact parity with the
    // old glassSurfaceAlpha(0.35) chrome. It fades on dark with no wallpaper: the
    // deliberate, chosen cost of Clear.
    if (tier == FrostTier.Clear) {
        FrostSurface(if (glass) tier.toLayers() else emptyList(), modifier, shape, interactionSource, content)
        return
    }

    val role = level.role()
    val bodyColor = frostColor(role)
    val dark = bodyColor.luminance() < 0.5f
    val coat = if (glass) tier.coatLayers() else emptyList()

    val layers = buildList {
        addAll(coat.filterIsInstance<Backdrop>())          // blurred wallpaper, under the body
        add(Body(role, if (opaque) 1f else bodyFloor(dark)))
        addAll(coat.filterNot { it is Backdrop })          // wash / texture / edge bands, over the body
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
