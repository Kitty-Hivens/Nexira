package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import hivens.core.data.ReleaseChannel
import hivens.ui.theme.NxTheme

// git sits between alpha-yellow and dev-red on the heat scale.
private val GIT_ORANGE = Color(0xFFE8743B)
// nightly is the rawest tier -- a distinct edge colour, hotter than dev.
private val NIGHTLY_PURPLE = Color(0xFFB56BFF)

/**
 * Channel accent: release green, beta blue, alpha yellow, git orange, dev
 * monochrome (plain text), nightly purple. Drives the About version colour.
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
