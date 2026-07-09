package hivens.ui.nx

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.NxTheme

/**
 * Title above a group of controls. [muted] picks between the two roles that
 * were reimplemented across screens: the default accent header (titleSmall,
 * primary, bold) that leads a settings block, and the muted subheader
 * (textSecondary, normal weight) used where the header should recede.
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
        color      = if (muted) NxTheme.colors.textSecondary else NxTheme.colors.primary,
        // Muted leaves the weight to titleSmall (Medium); the accent header bolds it.
        fontWeight = if (muted) null else FontWeight.Bold,
        modifier   = modifier,
    )
}
