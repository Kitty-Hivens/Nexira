package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.UpdateDirection
import hivens.ui.Screen
import hivens.ui.components.InitialsAvatar
import hivens.ui.components.SourceBadge
import hivens.ui.navigation.NavRequests
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.notifications.IndicationCenter
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.theme.Dimens
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import org.koin.compose.koinInject

/**
 * One Library row. Same three-layer background as the Browse card: a
 * deterministic pixel-art fill (so an art-less instance is never a flat
 * gradient), the [PackInstance.bannerUrl] image on top when the install
 * captured one (transparent while loading / on failure, art shows through),
 * then a dark scrim. Avatar + title + metadata chips overlay it, quick actions
 * on the right. Whole card click-routes to [PackDetailScreen]; the explicit
 * Play / Settings / Overflow buttons short-circuit common actions without the
 * detail hop.
 *
 * Source-badge is the small chip next to the title; it differentiates cards
 * from the four [PackOrigin] values at a glance per
 * [[project_home_library_ia]]'s unified-entity-with-source-badge rule.
 */
@Composable
fun PackCard(
    instance: PackInstance,
    onOpenDetail: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val (hueA, hueB) = NxTheme.colors.decorativePair(instance.id)
    val art = rememberPackArt(instance)
    PuppetClick("library.pack.${instance.id}") { onOpenDetail() }
    val indications: IndicationCenter = koinInject()
    // Remember the flow: launchIndication() ends in a fresh asStateFlow() wrapper each
    // call, so collecting it inline would re-subscribe on every recomposition.
    val indicationFlow = remember(instance.id) { indications.launchIndication(instance.id) }
    val indication by indicationFlow.collectAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.packCardHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpenDetail),
    ) {
        Box(Modifier.fillMaxSize().pixelArtBackground(instance.id, hueA, hueB))
        if (art.bannerUrl != null) {
            AsyncImage(
                model              = art.bannerUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (art.bannerUrl != null) 0.45f else 0.32f)))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Tight card (right panel open + narrow window): drop the source badge
            // before it eats the title.
            val showBadge = maxWidth >= 380.dp

            Row(
                modifier              = Modifier.fillMaxSize().padding(14.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PackAvatar(iconUrl = art.iconUrl, displayName = instance.displayName, hue = hueA)

                Column(
                    modifier              = Modifier.weight(1f),
                    verticalArrangement   = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text       = instance.displayName,
                            style      = MaterialTheme.typography.titleMedium,
                            color      = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            modifier   = Modifier.weight(1f, fill = false),
                        )
                        if (showBadge) SourceBadge(instance.packRef.origin)
                    }

                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NxMetaChip(instance.packRef.version ?: "—", tone = NxMetaChipTone.OnMedia)
                        instance.forkedFrom?.let {
                            NxMetaChip("fork", tone = NxMetaChipTone.OnMediaAccent)
                        }
                        LastPlayedChip(instance.lastPlayedEpochOrZero)
                    }
                }

                // No Play / Settings on the card: the whole card opens the detail,
                // where launch + every setting live. Card keeps only the overflow
                // (open folder / delete) as out-of-the-way quick actions.
                NxKebabButton(contentDescription = s.packCardMore, tint = Color.White) { dismiss ->
                    NxMenuItem(
                        label   = s.serverSettingsOpenFolder,
                        icon    = NxIcon.FolderOpen,
                        onClick = { dismiss(); onOpenFolder() },
                    )
                    NxMenuItem(
                        label       = s.editorDelete,
                        icon        = NxIcon.Delete,
                        destructive = true,
                        onClick     = { dismiss(); onDelete() },
                    )
                }
            }
        }

        // Launch state for this pack, produced by LaunchDriver -- a corner pill so
        // launching a pack reflects on its card instead of only in a transient toast.
        indication?.let {
            LaunchStatusPill(it, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        // Update-available badge from the status hub. The launch pill owns the
        // corner while a launch is in flight; clicking routes straight to the
        // versions screen through the nav mediator.
        if (indication == null) {
            val hub: PackUpdateStatusHub = koinInject()
            val nav: NavRequests = koinInject()
            val statuses by hub.statuses.collectAsState()
            (statuses[instance.id] as? PackUpdateStatus.Pending)?.let { pending ->
                UpdateBadgePill(
                    isRollback = pending.direction == UpdateDirection.Older,
                    onClick    = { nav.open(Screen.PackVersions(instance.id)) },
                    modifier   = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )
            }
        }
    }
}

/** Compact pill for a pending build move; the card-corner sibling of [LaunchStatusPill]. */
@Composable
private fun UpdateBadgePill(isRollback: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(7.dp).clip(RoundedCornerShape(50))
                .background(if (isRollback) NxTheme.colors.warnAccent else NxTheme.colors.primary),
        )
        Text(
            text       = if (isRollback) s.packVersionRollbackBadge else s.packVersionUpdateBadge,
            style      = MaterialTheme.typography.labelSmall,
            color      = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
        )
    }
}

/** Compact launch-state pill overlaid on the card while a launch of this pack is in flight. */
@Composable
private fun LaunchStatusPill(indication: IndicationCenter.LaunchIndication, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val (dotColor, label) = when (indication) {
        IndicationCenter.LaunchIndication.Preparing -> NxTheme.colors.progressAccent to s.launchPreparing
        is IndicationCenter.LaunchIndication.Downloading ->
            NxTheme.colors.progressAccent to (indication.progress?.let { "${(it * 100).roundToInt()}%" } ?: s.launchDownloading.removeSuffix(":"))
        IndicationCenter.LaunchIndication.Running -> NxTheme.colors.success to s.launchRunning
        IndicationCenter.LaunchIndication.Failed  -> NxTheme.colors.error to s.launchFailed
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dotColor))
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelSmall,
            color      = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines   = 1,
        )
    }
}

@Composable
private fun PackAvatar(iconUrl: String?, displayName: String, hue: Color) {
    SubcomposeAsyncImage(
        model              = iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.size(Dimens.packAvatar).clip(RoundedCornerShape(Dimens.packAvatarCorner)),
        loading            = { Box(Modifier.fillMaxSize().background(hue)) },
        error              = { InitialsAvatar(displayName, hue) },
    )
}

@Composable
private fun LastPlayedChip(lastPlayedEpoch: Long) {
    val s = LocalStrings.current
    if (lastPlayedEpoch <= 0L) {
        NxMetaChip(s.packCardNeverPlayed, tone = NxMetaChipTone.OnMedia)
        return
    }
    val now = Instant.now()
    val then = Instant.ofEpochSecond(lastPlayedEpoch)
    val dur = Duration.between(then, now)
    val label = when {
        dur.toMinutes() < 1   -> s.packCardPlayedJustNow
        dur.toHours()   < 1   -> s.packCardPlayedMinutesAgo(dur.toMinutes())
        dur.toDays()    < 1   -> s.packCardPlayedHoursAgo(dur.toHours())
        dur.toDays()    < 14  -> s.packCardPlayedDaysAgo(dur.toDays())
        else                  -> s.packCardPlayedLongAgo
    }
    NxMetaChip(label, tone = NxMetaChipTone.OnMedia)
}

/** The card's on-media meta chip, kept as a named alias for other over-banner surfaces. */
@Composable
internal fun PackMetaChip(text: String, emphasis: Boolean = false) {
    NxMetaChip(text, tone = if (emphasis) NxMetaChipTone.OnMediaAccent else NxMetaChipTone.OnMedia)
}
