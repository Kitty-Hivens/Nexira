package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme

/**
 * Title above a group of controls. [muted] picks between the two roles that
 * were reimplemented across screens: the default accent header (titleSmall,
 * the lead colour, bold) that leads a settings block, and the muted subheader
 * (the quiet ink, normal weight) used where the header should recede.
 */
@Composable
fun NxSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
) {
    Text(
        text       = text,
        style      = MaterialTheme.typography.titleSmall,
        color      = if (muted) NxInk.quiet else NxColor.lead(),
        // Muted leaves the weight to titleSmall (Medium); the accent header bolds it.
        fontWeight = if (muted) null else FontWeight.Bold,
        modifier   = modifier,
    )
}

@Preview
@Composable
private fun NxSectionHeaderDarkPreview() = SectionHeaders(dark = true)

@Preview
@Composable
private fun NxSectionHeaderLightPreview() = SectionHeaders(dark = false)

@Composable
private fun SectionHeaders(dark: Boolean) {
    NxTheme(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(320.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxSectionHeader("Graphics")
            NxSectionHeader("Advanced", muted = true)
        }
    }
}
