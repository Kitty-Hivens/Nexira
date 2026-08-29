package hivens.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import hivens.ui.surface.FrostTier
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import hivens.ui.theme.LocalStyle
import hivens.widget.model.FillSource
import hivens.widget.model.SurfaceShape
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.parseFill

/**
 * Paints a widget's own surface: the seven values of a [SurfaceSpec], rendered by the
 * library surface rather than by a second mechanism beside it.
 *
 * This is the one place the persisted description meets the renderer. It was two
 * places, and neither described a whole plane: the kernel's backing drew a glass card
 * with its own corner and its own opacity, while a widget that wanted a card of its own
 * drew one directly. Turning the backing up therefore added a second plane behind the
 * first rather than changing the one on screen, which is the shape of "the knob moves
 * something other than what I am looking at".
 *
 * Padding is an OUTER inset applied before the plane, so the rounding hugs the widget's
 * own view rather than the padded footprint. That is what lets a panel be inset from a
 * window edge without its corners detaching onto the padding.
 */
@Composable
fun WidgetSurface(spec: SurfaceSpec, content: @Composable () -> Unit) {
    val style = LocalStyle.current
    val fill = parseFill(spec.fill)
    val padding = spec.padding
    Box(
        Modifier.padding(
            PaddingValues(
                start = padding.start(0f).dp,
                top = padding.top(0f).dp,
                end = padding.end(0f).dp,
                bottom = padding.bottom(0f).dp,
            ),
        ),
    ) {
        NxSurface(
            level = fill.level(),
            shape = spec.shape.toShape(style),
            // Tiers are gone from this path: every value the surface draws with arrives
            // named. Flat carries no blur of its own, so a null radius here means none
            // rather than a preset's constant.
            tier = FrostTier.Flat,
            hairline = false,
            opacity = spec.opacity,
            blurDp = spec.blurDp,
            borderWidthDp = spec.border.widthDp,
            borderColor = borderColor(spec),
            shadowDp = spec.shadowDp,
            fillColor = (fill as? FillSource.Literal)?.let { Color(it.argb) },
        ) { content() }
    }
}

/**
 * Which rung of the tonal ladder a fill names. A literal colour still passes a level,
 * because the level also decides nothing else once [NxSurface.fillColor] is given.
 */
private fun FillSource.level(): NxSurfaceLevel = when (this) {
    is FillSource.Rung -> when (name) {
        "sunken" -> NxSurfaceLevel.Sunken
        "base" -> NxSurfaceLevel.Base
        "floating" -> NxSurfaceLevel.Floating
        else -> NxSurfaceLevel.Raised
    }
    // A widget's own plane sits on the page, so Raised is where an unnamed one belongs.
    else -> NxSurfaceLevel.Raised
}

/** The hairline's colour, when the spec names one. Null lets the bevel derive its own. */
@Composable
private fun borderColor(spec: SurfaceSpec): Color? {
    val opacity = spec.border.opacity
    val base = when (val source = parseFill(spec.border.color)) {
        is FillSource.Literal -> Color(source.argb)
        is FillSource.Rung -> NxTheme.colors.outline
        FillSource.Inherit -> if (opacity != null) NxTheme.colors.outline else return null
    }
    return if (opacity != null) base.copy(alpha = opacity.coerceIn(0f, 1f)) else base
}

/**
 * The outline a shape record describes.
 *
 * Blank and "round" both take the per-corner values, which fall back to the style's
 * card corner one field at a time -- that is what lets a plane be square on one side
 * and rounded on the other without a second set of fields. A kind this does not answer
 * lands on the same card shape rather than on a rectangle, because an unknown kind is
 * a newer file being read by an older build and the card shape is the safer guess.
 */
private fun SurfaceShape.toShape(style: StyleSpec): Shape {
    val fallback = style.cardCorner.value
    return when (kind.trim().lowercase()) {
        "circle" -> CircleShape
        "pill" -> RoundedCornerShape(percent = 50)
        "rect" -> RoundedCornerShape(0.dp)
        else -> RoundedCornerShape(
            topStart = corners.topStart(fallback).dp,
            topEnd = corners.topEnd(fallback).dp,
            bottomEnd = corners.bottomEnd(fallback).dp,
            bottomStart = corners.bottomStart(fallback).dp,
        )
    }
}
