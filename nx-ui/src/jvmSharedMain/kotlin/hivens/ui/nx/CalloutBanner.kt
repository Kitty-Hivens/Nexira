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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.look.shape.api.Shape
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/** Severity of a [CalloutBanner]; drives its accent colour and default glyph. */
enum class CalloutTone { Info, Warning, Error }

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
fun CalloutBanner(
    title: String? = null,
    body: String? = null,
    modifier: Modifier = Modifier,
    tone: CalloutTone = CalloutTone.Info,
    icon: IconKey? = null,
    onDismiss: (() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val status = when (tone) {
        CalloutTone.Info    -> Status.Info
        CalloutTone.Warning -> Status.Warning
        CalloutTone.Error   -> Status.Error
    }
    val accent = NxColor.status(status)
    // Opaque, so the banner reads the same over a wallpaper as over the page.
    val fill = NxColor.wash(accent, 0.12f)
    val glyph = icon ?: when (tone) {
        CalloutTone.Info    -> NxIcon.Info
        CalloutTone.Warning -> NxIcon.Warning
        CalloutTone.Error   -> NxIcon.Warning
    }
    val shape = Shape.card
    OnFill(fill) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(fill)
                .border(1.dp, NxColor.wash(accent, 0.4f), shape)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Symbol(glyph, contentDescription = null, tint = NxColor.status(status), modifier = Modifier.size(20.dp))
            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = NxColor.status(status), fontWeight = FontWeight.Bold)
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

@Preview
@Composable
private fun CalloutBannerDarkPreview() = CalloutBannerTones(dark = true)

@Preview
@Composable
private fun CalloutBannerLightPreview() = CalloutBannerTones(dark = false)

@Composable
private fun CalloutBannerTones(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CalloutBanner(title = "Info", body = "A standing condition the reader should know about.")
            CalloutBanner(title = "Warning", body = "Something may go wrong.", tone = CalloutTone.Warning, onDismiss = {})
            CalloutBanner(body = "An error with no title.", tone = CalloutTone.Error) {
                NxButton("Retry", onClick = {})
            }
        }
    }
}
