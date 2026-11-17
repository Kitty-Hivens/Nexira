package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme

/**
 * A place that exists and has nothing behind it yet.
 *
 * Deliberately not an error and deliberately not silence. Silence is what a reader
 * meets when a pane simply fails to draw, and they cannot tell it from something
 * broken; an error says it went wrong, which is a lie about work that was never
 * started. This says the third thing, which is the true one.
 *
 * It jokes, once, and then gets out of the way. A reader who finds three of these
 * in an evening should not be congratulated three times in the same words at the
 * same volume, so the line is short and the detail underneath is the caller's --
 * what specifically is missing is more use than another sentence of charm.
 */
@Composable
fun NxNotBuiltYet(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    /** What is missing here in particular, when the caller can say. */
    detail: String? = null,
    icon: IconKey = NxIcon.Science,
) {
    NxStateBlock(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        // The accent and not the warning colour. Nothing here is wrong, and a
        // reader who has learned that red means trouble should not be told it does
        // when the only news is that we have not got to this yet.
        titleColor = NxColor.lead(),
        titleStyle = MaterialTheme.typography.titleMedium,
        action = detail?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxInk.quiet,
                    textAlign = TextAlign.Center,
                )
            }
        },
    )
}

@Preview
@Composable
private fun NxNotBuiltYetDarkPreview() = NotBuiltYetSamples(dark = true)

@Preview
@Composable
private fun NxNotBuiltYetLightPreview() = NotBuiltYetSamples(dark = false)

@Composable
private fun NotBuiltYetSamples(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            NxNotBuiltYet("Not built yet", "This page exists and has nothing behind it so far.", Modifier.fillMaxWidth())
            NxNotBuiltYet(
                title    = "Coming later",
                message  = "Server lists arrive in a later release.",
                modifier = Modifier.fillMaxWidth(),
                detail  = "Needs the mirror's server registry",
                icon    = NxIcon.Lan,
            )
        }
    }
}
