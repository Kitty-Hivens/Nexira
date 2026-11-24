package hivens.ui.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.look.icon.api.Icon
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Previewed

/**
 * Draws [icon] the way the look's icon library draws it. Drop-in for Material's `Icon`: the same argument
 * order, and it honours `Modifier.size(...)`, 24.dp like `Icon` where none is given. A size above that
 * needs the explicit [size] parameter.
 *
 * - [fill] 0f outlined .. 1f filled (animatable for the usual toggle effect)
 * - [weight] 100..700 stroke weight.
 */
@Composable
fun Symbol(
    icon: IconKey,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    fill: Float = 0f,
    weight: Int = 400,
    size: Dp? = null,
) {
    // Caller modifier before the default size: the reverse order lets size(24)
    // clamp a smaller caller size up (size enforces incoming constraints).
    val base = modifier.then(Modifier.size(size ?: 24.dp))
    Icon.Draw(
        key      = icon,
        modifier = if (contentDescription != null) {
            base.semantics { this.contentDescription = contentDescription }
        } else {
            base
        },
        tint     = tint.takeOrElse { LocalContentColor.current },
        fill     = fill,
        weight   = weight,
    )
}

@Preview
@Composable
private fun SymbolDarkPreview() = SymbolCatalogue(dark = true)

@Preview
@Composable
private fun SymbolLightPreview() = SymbolCatalogue(dark = false)

/**
 * Every icon [NxIcon] holds, read off the object itself so the sheet follows the generated
 * list, then the ways one icon can be drawn.
 */
@Composable
private fun SymbolCatalogue(dark: Boolean) {
    val catalogue = remember {
        NxIcon::class.java.declaredFields
            .filter { it.type == IconKey::class.java }
            .onEach { it.isAccessible = true }
            .map { it.name to it.get(null) as IconKey }
            .sortedBy { it.first }
    }
    Previewed(dark = dark) {
        Column(
            modifier            = Modifier.width(760.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (row in catalogue.chunked(7)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((name, icon) in row) {
                        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Symbol(icon, null, tint = NxInk.main)
                            Text(name, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Symbol(NxIcon.Favorite, null, tint = NxColor.lead())
                Symbol(NxIcon.Favorite, null, tint = NxColor.lead(), fill = 1f)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 200)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 400)
                Symbol(NxIcon.Settings, null, tint = NxInk.main, weight = 700)
                Symbol(NxIcon.Star, null, tint = NxInk.main, size = 16.dp)
                Symbol(NxIcon.Star, null, tint = NxInk.main, size = 40.dp)
                Symbol(NxIcon.Star, null, tint = NxInk.main, modifier = Modifier.size(32.dp))
            }
        }
    }
}
