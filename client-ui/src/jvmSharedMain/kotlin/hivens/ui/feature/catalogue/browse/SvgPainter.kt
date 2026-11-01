package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * The catalogue's SVG as a painter, or null when it does not parse. Drawn in the
 * row's ink through a tint, since the catalogue strokes its marks in currentColor.
 */
@Composable
internal expect fun svgPainter(source: String): Painter?
