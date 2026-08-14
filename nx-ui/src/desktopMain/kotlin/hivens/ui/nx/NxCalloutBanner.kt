package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/** Severity of a [NxCalloutBanner]; drives its accent colour and default glyph. */
enum class NxCalloutTone { Info, Warning, Error }

/**
 * Boxed notice with a leading icon: a tinted fill + hairline in the [tone]'s
 * accent, an optional [title] and [body], and a [content] slot for actions
 * (buttons, prompts) that stacks under the text. Replaces the warning-banner
 * recipe that was copy-pasted across the login and server-detail surfaces.
 */
@Composable
fun NxCalloutBanner(
    title: String? = null,
    body: String? = null,
    modifier: Modifier = Modifier,
    tone: NxCalloutTone = NxCalloutTone.Info,
    icon: IconKey? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = NxTheme.colors
    val accent = when (tone) {
        NxCalloutTone.Info    -> colors.primary
        NxCalloutTone.Warning -> colors.warnAccent
        NxCalloutTone.Error   -> colors.error
    }
    val glyph = icon ?: when (tone) {
        NxCalloutTone.Info    -> NxIcon.Info
        NxCalloutTone.Warning -> NxIcon.Warning
        NxCalloutTone.Error   -> NxIcon.Warning
    }
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.4f), shape)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
    ) {
        Symbol(glyph, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        Column(
            modifier            = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
            }
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = colors.textPrimary.copy(alpha = 0.85f))
            }
            content?.invoke(this)
        }
    }
}
