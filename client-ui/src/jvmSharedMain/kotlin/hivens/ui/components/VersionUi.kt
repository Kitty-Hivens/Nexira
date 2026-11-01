package hivens.ui.components

import androidx.compose.runtime.Composable
import hivens.core.update.VersionChannel
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BUILD_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Release-channel badge with the shared colour vocabulary: release reads
 * neutral, beta warns, alpha alarms. Used by the versions screen and the pack
 * settings header so a channel looks the same everywhere.
 */
@Composable
fun ChannelChip(channel: VersionChannel) {
    val s = LocalStrings.current
    val (label, tone) = when (channel) {
        VersionChannel.Release -> s.packVersionsChannelRelease to NxMetaChipTone.Surface
        VersionChannel.Beta    -> s.packVersionsChannelBeta to NxMetaChipTone.Warning
        VersionChannel.Alpha   -> s.packVersionsChannelAlpha to NxMetaChipTone.Error
    }
    NxMetaChip(label, tone = tone)
}

/** An instant as every build time in the launcher reads: local `yyyy-MM-dd HH:mm`. */
fun formatBuildTime(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(BUILD_TIME)

/** RFC 3339 -> local `yyyy-MM-dd HH:mm`; the raw string when it does not parse. */
fun formatBuildTimestamp(raw: String?): String? = raw?.let {
    runCatching { formatBuildTime(Instant.parse(it)) }.getOrDefault(it)
}
