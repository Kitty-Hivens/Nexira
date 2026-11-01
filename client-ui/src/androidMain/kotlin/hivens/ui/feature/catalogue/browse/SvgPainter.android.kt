package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

// Compose resources decodes SVG on the desktop only, so the marks that come as SVG are
// left out until the host has a decoder of its own.
@Composable
internal actual fun svgPainter(source: String): Painter? = null
