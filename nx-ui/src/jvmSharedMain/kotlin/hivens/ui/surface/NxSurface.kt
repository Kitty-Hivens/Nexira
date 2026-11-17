package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.effects.pixelArtBackground
import hivens.ui.theme.Backdrop
import hivens.ui.theme.LocalPlane
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.theme.Plane
import hivens.ui.theme.bevelHairline
import hivens.look.colour.api.Colour

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
 * through it. [SurfaceKind.Chrome] frames what it holds in the step above it, solid
 * by default, over a picture as much as over the page: it carries the navigation and
 * the title, and text on a thin coat over a picture reads only as well as the picture
 * lets it. Named an opacity under one, it turns to glass and blurs what is behind it.
 *
 * Content is handed a [Plane] for this surface, so anything inside knows the colour it
 * is really on, and the theme's main ink as its content colour.
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
    /** Body opacity, 0..1. Null is the kind's own: solid. */
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
    val answers = Colour.local.current
    val parent = LocalPlane.current ?: Plane(0, answers.groundDepth(0), Backdrop.Page)
    val glass = kind == SurfaceKind.Chrome
    val step = surfaceStep(kind, parent.step, answers.groundFloating)

    // Chrome adds no depth to what it holds, but is drawn in the step above it, which
    // is what keeps a rail and a title bar apart from the page they frame.
    val material = fillColor ?: answers.groundDepth(if (glass) parent.step + 1 else step)
    val alpha = (opacity ?: kind.defaultOpacity()).coerceIn(0f, 1f)
    val body = material.copy(alpha = material.alpha * alpha)
    // A blur under a body nothing can see through is work thrown away: the filter runs
    // every frame and is then covered completely.
    val blur = blurDp ?: if (glass) DEFAULT_BLUR_DP else 0f
    val backdrop = rememberBackdropBlur(if (body.alpha < 1f) blur else 0f)
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
    val stateTint = answers.paintLead

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
                backdrop?.draw(this)
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
            LocalContentColor provides answers.inkMain,
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

/**
 * The opacity a surface of this kind draws at when it names none. Solid for every
 * kind: nx-ui cannot see the picture behind a translucent plane, so it cannot promise
 * that anything on one reads, and see-through is a choice a person makes in their own
 * layout. Public because an editor showing a surface's values has to open on this one
 * too: a control that starts at a number the renderer never used is the same defect as
 * a control that moves nothing.
 */
fun SurfaceKind.defaultOpacity(): Float = 1f

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

/** How far glass blurs what is behind it when it names no radius. */
private const val DEFAULT_BLUR_DP = 18f

@Preview
@Composable
private fun NxSurfaceDarkPreview() = SurfaceKinds(dark = true)

@Preview
@Composable
private fun NxSurfaceLightPreview() = SurfaceKinds(dark = false)

@Composable
private fun SurfaceKinds(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(640.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            NxSurface(SurfaceKind.Panel) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SurfaceLabel("Panel on the page")
                    NxCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SurfaceLabel("Card in the panel")
                            NxSurface(SurfaceKind.Field, shape = MaterialTheme.shapes.small) {
                                Box(Modifier.padding(10.dp)) { SurfaceLabel("Field in the card") }
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (kind in listOf(SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice, SurfaceKind.Chrome)) {
                    NxSurface(kind, Modifier.size(132.dp, 64.dp)) { Box(Modifier.padding(10.dp)) { SurfaceLabel(kind.name) } }
                }
            }
            Row(
                modifier              = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .pixelArtBackground("surface", Color(0xFF3B6EA8), Color(0xFFB8608E))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NxSurface(SurfaceKind.Chrome, Modifier.size(132.dp, 64.dp)) { Box(Modifier.padding(10.dp)) { SurfaceLabel("Solid chrome") } }
                NxSurface(SurfaceKind.Chrome, Modifier.size(132.dp, 64.dp), opacity = 0.5f) { Box(Modifier.padding(10.dp)) { SurfaceLabel("Glass chrome") } }
                NxSurface(SurfaceKind.Card, Modifier.size(132.dp, 64.dp), borderColor = NxColor.lead(), borderWidthDp = 2f) {
                    Box(Modifier.padding(10.dp)) { SurfaceLabel("Own border") }
                }
                NxSurface(SurfaceKind.Card, Modifier.size(132.dp, 64.dp), fillColor = Color(0xFF2E6B4F), borderWidthDp = 0f, shadowDp = 8f) {
                    Box(Modifier.padding(10.dp)) { Text("Own fill", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun SurfaceLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = NxInk.main)
}
