package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.core.data.ReleaseChannel
import hivens.core.update.VersionChannel
import hivens.ui.theme.NxTheme

// git sits past alpha-yellow on the heat scale, hotter than the plain text dev
// is drawn in.
private val GIT_ORANGE = Color(0xFFE8743B)
// nightly is the rawest tier -- a distinct edge colour, hotter than git.
private val NIGHTLY_PURPLE = Color(0xFFB56BFF)

/**
 * Channel accent: release green, beta blue, alpha yellow, git orange, dev
 * monochrome (plain text), nightly purple. Follows [ReleaseChannel]'s own
 * ordering from stable to bleeding-edge, so the colour carries the same ranking
 * the enum does. Drives the About version colour.
 * (The channel pill that also lived here went with the update-manager window.)
 */
@Composable
fun channelColor(channel: ReleaseChannel): Color = when (channel) {
    ReleaseChannel.Release -> NxTheme.colors.success
    ReleaseChannel.Beta    -> NxTheme.colors.progressAccent
    ReleaseChannel.Alpha   -> NxTheme.colors.warnAccent
    ReleaseChannel.Git     -> GIT_ORANGE
    ReleaseChannel.Dev     -> NxTheme.colors.textPrimary
    ReleaseChannel.Nightly -> NIGHTLY_PURPLE
}

/**
 * A pack build's channel, on the same scale.
 *
 * [VersionChannel] is a separate three-value vocabulary -- it mirrors the
 * mirror's wire format, where Dev, Git and Nightly have no counterpart -- but the
 * user reads the same three words. Giving them the same three colours costs
 * nothing and stops a second scale from drifting: Beta was blue here and yellow
 * in the version picker, Alpha yellow here and red there, and only Release
 * agreeing is why nobody noticed.
 */
@Composable
fun channelColor(channel: VersionChannel): Color = channelColor(
    when (channel) {
        VersionChannel.Release -> ReleaseChannel.Release
        VersionChannel.Beta    -> ReleaseChannel.Beta
        VersionChannel.Alpha   -> ReleaseChannel.Alpha
    },
)
