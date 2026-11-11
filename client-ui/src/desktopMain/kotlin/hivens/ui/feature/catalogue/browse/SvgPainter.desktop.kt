package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.decodeToSvgPainter

@Composable
internal actual fun svgPainter(source: String): Painter? {
    val density = LocalDensity.current
    return remember(source, density) { runCatching { source.encodeToByteArray().decodeToSvgPainter(density) }.getOrNull() }
}
