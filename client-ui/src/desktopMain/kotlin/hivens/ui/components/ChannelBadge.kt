package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.ReleaseChannel
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

// git sits between alpha-yellow and dev-red on the heat scale.
private val GIT_ORANGE = Color(0xFFE8743B)

/** Channel accent: release green, beta blue, alpha yellow, git orange,
 *  dev monochrome (plain white text). */
@Composable
fun channelColor(channel: ReleaseChannel): Color = when (channel) {
    ReleaseChannel.Release -> CelestiaTheme.colors.success
    ReleaseChannel.Beta    -> CelestiaTheme.colors.progressAccent
    ReleaseChannel.Alpha   -> CelestiaTheme.colors.warnAccent
    ReleaseChannel.Git     -> GIT_ORANGE
    ReleaseChannel.Dev     -> CelestiaTheme.colors.textPrimary
}

/**
 * Channel pill, colour-coded per the user's scheme: release green, beta blue,
 * alpha yellow, dev gradient, git monochrome. Reads its corner from the style
 * engine so it follows Celestia/Brut.
 */
@Composable
fun ChannelBadge(channel: ReleaseChannel, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    val shape = RoundedCornerShape(style.cardCorner)

    val label = when (channel) {
        ReleaseChannel.Release -> s.updateChannelRelease
        ReleaseChannel.Beta    -> s.updateChannelBeta
        ReleaseChannel.Alpha   -> s.updateChannelAlpha
        ReleaseChannel.Dev     -> s.updateChannelDev
        ReleaseChannel.Git     -> s.updateChannelGit
    }
    val accent = channelColor(channel)
    // Dev is monochrome -- a greyscale gradient (a single achromatic ramp, not a
    // flat colour); every other channel is a flat tint of its accent.
    val fill = if (channel == ReleaseChannel.Dev) {
        Modifier.background(
            Brush.linearGradient(
                listOf(
                    CelestiaTheme.colors.textPrimary.copy(alpha = 0.24f),
                    CelestiaTheme.colors.textSecondary.copy(alpha = 0.07f),
                ),
            ),
        )
    } else {
        Modifier.background(accent.copy(alpha = 0.18f))
    }
    Box(modifier.clip(shape).then(fill).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.SemiBold)
    }
}
