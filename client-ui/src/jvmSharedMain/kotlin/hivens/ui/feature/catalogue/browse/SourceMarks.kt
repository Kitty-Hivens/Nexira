package hivens.ui.feature.catalogue.browse

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.unit.Dp
import hivens.catalogue.api.SourceIcon
import hivens.catalogue.api.SourceView
import hivens.i18n.api.strings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol

/** What a source is called, in the words of the module that brings it. */
@Composable
internal fun sourceLabel(source: SourceView): String = strings(source.module).text(source.label)

/**
 * A source's mark in [tint]: the glyph it names, its own SVG, or the plain mark of a
 * source that names neither or whose SVG does not draw here.
 */
@Composable
internal fun SourceMark(icon: SourceIcon?, tint: Color, size: Dp, modifier: Modifier = Modifier, fill: Float = 0f) {
    val svg = (icon as? SourceIcon.Svg)?.let { svgPainter(it.markup) }
    if (svg != null) {
        Image(svg, contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
    } else {
        Symbol(glyphOf(icon), contentDescription = null, tint = tint, fill = fill, size = size, modifier = modifier)
    }
}

private fun glyphOf(icon: SourceIcon?): IconKey = (icon as? SourceIcon.Glyph)?.let { IconKey(it.codepoint) } ?: NxIcon.Language
