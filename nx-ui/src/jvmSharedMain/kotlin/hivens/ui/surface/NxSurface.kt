package hivens.ui.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Plane
import hivens.look.colour.api.Colour
import hivens.look.colour.api.LocalGround
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackTarget
import hivens.look.surface.api.Surface
import hivens.look.surface.api.drawPlaneBody
import hivens.look.shape.api.Shape as ShapeAxis
import hivens.ui.theme.Previewed

/**
 * A library-owned surface: [kind] is asked of the surface axis, which answers its step,
 * its material, its opacity and its edge, and so what its content reads against. Every number it draws with can still be named by a caller that has a
 * reason, which is what a user's own widget plane is, and a value named is stronger
 * than the look. The shadow and the blur are the surface's own, not the look's: a
 * caller names them, the floating kinds cast a shadow where none is named, and chrome
 * made glass blurs where no radius is.
 *
 * A blur is drawn only where the body lets something through, since a filter under a
 * body nothing can see through is work thrown away.
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
    shape: Shape = ShapeAxis.card,
    /** Body opacity, 0..1. Null is the look's answer for the kind. */
    opacity: Float? = null,
    /** How far the surface blurs what is behind it. Null is the kind's own: none but for chrome. */
    blurDp: Float? = null,
    /** Edge width. Null is the look's answer for the kind, zero removes it. */
    borderWidthDp: Float? = null,
    /** Edge colour. Null is the colour the look's material makes from the body. */
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
    val look = Surface.plane(kind, parent, answers.groundFloating)

    val colour = fillColor ?: answers.groundDepth(look.depth)
    val alpha = (opacity ?: look.opacity).coerceIn(0f, 1f)
    val body = colour.copy(alpha = colour.alpha * alpha)
    val blur = blurDp ?: kind.defaultBlurDp
    val backdrop = rememberBackdropBlur(if (body.alpha < 1f) blur else 0f)
    val edgeWidth = borderWidthDp ?: look.edgeDp
    val edge = if (edgeWidth > 0f) borderColor ?: look.material.edge(colour) else null
    val shadow = shadowDp ?: kind.defaultShadowDp

    // What content actually sits on: the body over its holder, as far as the body lets
    // the holder through. Chrome frames what is behind it rather than covering it, so
    // what it holds is still over that.
    val under = lerp(parent.color.copy(alpha = 1f), colour.copy(alpha = 1f), body.alpha)
    val over = if (kind == SurfaceKind.Chrome) parent.over else Backdrop.Plane
    val plane = Plane(look.contentDepth, under, over)

    // An indication draws from the interactions themselves, so hovering a card does
    // not recompose it and everything it contains to change one rectangle.
    val feedback = interactionSource?.let { Feedback.indication(FeedbackTarget.Plane, tint = answers.paintLead) }

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
                drawPlaneBody(look.material, body, backdrop?.let { blurred -> { blurred.draw(this) } })
            }
            .then(if (interactionSource != null && feedback != null) Modifier.indication(interactionSource, feedback) else Modifier),
    ) {
        CompositionLocalProvider(
            LocalPlane provides plane,
            LocalGround provides under,
            LocalContentColor provides answers.inkMain,
        ) {
            content()
        }
    }
}

/** How far a surface of this kind blurs what is behind it when it names no radius. */
private val SurfaceKind.defaultBlurDp: Float
    get() = if (this == SurfaceKind.Chrome) 18f else 0f

private val SurfaceKind.defaultShadowDp: Float
    get() = when (this) {
        SurfaceKind.Popup, SurfaceKind.Dialog, SurfaceKind.Notice -> 6f
        else -> 0f
    }


/**
 * The opacity a surface of this kind draws at when it names none, as the look answers it
 * on the plane this is asked from. Public because an editor showing a surface's values
 * has to open on this one too: a control that starts at a number the renderer never
 * used is the same defect as a control that moves nothing.
 */
@Composable
fun SurfaceKind.defaultOpacity(): Float {
    val answers = Colour.local.current
    val holder = LocalPlane.current ?: Plane(0, answers.groundDepth(0), Backdrop.Page)
    return Surface.plane(this, holder, answers.groundFloating).opacity
}

/** Card-shaped surface: [SurfaceKind.Card] at the card corner. */
@Composable
fun NxCard(
    modifier: Modifier = Modifier,
    shape: Shape = ShapeAxis.card,
    interactionSource: InteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) = NxSurface(
    kind = SurfaceKind.Card,
    modifier = modifier,
    shape = shape,
    interactionSource = interactionSource,
    content = content,
)

@Preview
@Composable
private fun NxSurfaceDarkPreview() = SurfaceKinds(dark = true)

@Preview
@Composable
private fun NxSurfaceLightPreview() = SurfaceKinds(dark = false)

@Composable
private fun SurfaceKinds(dark: Boolean) {
    Previewed(dark = dark) {
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
                            NxSurface(SurfaceKind.Field, shape = ShapeAxis.control) {
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
                    .clip(RoundedCornerShape(12.dp))
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
