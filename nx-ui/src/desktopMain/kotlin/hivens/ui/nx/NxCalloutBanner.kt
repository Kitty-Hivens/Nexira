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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/** Severity of a [NxCalloutBanner]; drives its accent colour and default glyph. */
enum class NxCalloutTone { Info, Warning, Error }

/**
 * Boxed notice with a leading icon: a tinted fill + hairline in the [tone]'s
 * accent, an optional [title] and [body], and a [content] slot for actions
 * (buttons, prompts) that stacks under the text. Replaces the warning-banner
 * recipe that was copy-pasted across the login and server-detail surfaces.
 *
 * [onDismiss] adds a close on the trailing edge, for a banner that reports
 * something that HAPPENED rather than a standing condition: an outcome the
 * reader has taken in has no reason to keep occupying the screen, and a
 * standing one must not be dismissable into thinking it went away.
 */
@Composable
fun NxCalloutBanner(
    title: String? = null,
    body: String? = null,
    modifier: Modifier = Modifier,
    tone: NxCalloutTone = NxCalloutTone.Info,
    icon: IconKey? = null,
    onDismiss: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val status = when (tone) {
        NxCalloutTone.Info    -> Status.Info
        NxCalloutTone.Warning -> Status.Warning
        NxCalloutTone.Error   -> Status.Error
    }
    val accent = NxColor.status(status)
    // Opaque, so the banner reads the same over a wallpaper as over the page, and its
    // words are fitted to the banner rather than to the plane under it.
    val fill = NxColor.wash(accent, 0.12f)
    val glyph = icon ?: when (tone) {
        NxCalloutTone.Info    -> NxIcon.Info
        NxCalloutTone.Warning -> NxIcon.Warning
        NxCalloutTone.Error   -> NxIcon.Warning
    }
    val shape = MaterialTheme.shapes.medium
    OnFill(fill) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(1.dp, NxColor.wash(accent, 0.4f), shape)
                .padding(Spacing.s14),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Symbol(glyph, contentDescription = null, tint = NxColor.status(status), modifier = Modifier.size(20.dp))
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = NxColor.status(status, text = true), fontWeight = FontWeight.Bold)
                }
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodySmall, color = NxInk.main)
                }
                content?.invoke(this)
            }
            if (onDismiss != null) {
                NxIconButton(
                    icon               = NxIcon.Close,
                    contentDescription = null,
                    onClick            = onDismiss,
                    tint               = NxInk.quiet,
                    iconSize           = 16.dp,
                )
            }
        }
    }
}
