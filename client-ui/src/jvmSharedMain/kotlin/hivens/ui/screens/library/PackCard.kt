package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import hivens.packs.api.PackKeys
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import hivens.ui.Screen
import hivens.ui.components.SourceBadge
import hivens.ui.components.rememberPackDeleteBlock
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.navigation.NavRequests
import hivens.ui.notifications.IndicationCenter
import hivens.ui.nx.InitialsAvatar
import hivens.ui.nx.NxKebabButton
import hivens.ui.nx.NxMenuItem
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.Dimens
import hivens.ui.theme.familyForText
import hivens.ui.theme.decorativePair
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * One Library row. Same three-layer background as the Browse card: a
 * deterministic pixel-art fill (so an art-less instance is never a flat
 * gradient), the [PackView.bannerUrl] image on top when the install
 * captured one (transparent while loading / on failure, art shows through),
 * then a dark scrim. Avatar + title + metadata chips overlay it, quick actions
 * on the right. Whole card click-routes to [hivens.ui.screens.detail.PackDetailScreen]; the explicit
 * Play / Settings / Overflow buttons short-circuit common actions without the
 * detail hop.
 *
 * Source-badge is the small chip next to the title; it differentiates cards
 * by source at a glance, so one list can hold every source instead of
 * splitting per origin, and says so where a pack's source is not loaded.
 */
@Composable
fun PackCard(
    instance: PackView,
    onOpenDetail: () -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    val (hueA, hueB) = decorativePair(instance.id)
    val art = rememberPackArt(instance)
    PuppetClick("library.pack.${instance.id}") { onOpenDetail() }
    val indications: IndicationCenter = koinInject()
    val indication by indications.launchIndication(instance.id).collectAsState()
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
                PackAvatar(iconUrl = art.iconUrl, displayName = instance.name, hue = hueA)

                Column(
                    modifier              = Modifier.weight(1f),
                    verticalArrangement   = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text       = instance.name,
                            fontFamily = familyForText(instance.name),
                            style      = MaterialTheme.typography.titleMedium,
                            color      = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis,
                            modifier   = Modifier.weight(1f, fill = false),
                        )
                        if (showBadge) SourceBadge(instance.source.origin)
                    }

                    // One line, and a chip is placed at its own width or not at all.
                    // A plain row narrowed its last child to make everything fit, so
                    // a source that publishes long version strings ended the row on a
                    // word chopped in half.
                    //
                    // The cap below is not a yielding: it is a ceiling, so the row is
                    // at most 180 + fork + last-played wide. Narrower than that -- a
                    // small window with the right panel open -- and the last chip is
                    // dropped rather than cut, which is the honest shape but is a
                    // fact going missing with nothing said about it.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                        maxLines              = 1,
                    ) {
                        // Capped, because it is the one chip here with no bound on its
                        // length: a Modrinth pack names a build SNAPSHOT-0.0.0-2026.07.17
                        // and that alone filled the row, pushing "last played" off the
                        // card entirely. Truncated it still says which build; absent, the
                        // other facts say nothing.
                        // A pack with no build of its own, a local one, has no version to
                        // name, and a dash in its place read as a fact rather than as none.
                        instance.source.version?.takeIf { it.isNotBlank() }?.let { version ->
                            NxMetaChip(
                                version,
                                modifier = Modifier.widthIn(max = VERSION_CHIP_MAX),
                                tone = NxMetaChipTone.OnMedia,
                            )
                        }
                        instance.forkedFrom?.let {
                            NxMetaChip("fork", tone = NxMetaChipTone.OnMediaAccent)
                        }
                        LastPlayedChip(instance.lastPlayedEpochSecond)
                    }
                }

                // No Play / Settings on the card: the whole card opens the detail,
                // where launch + every setting live. Card keeps only the overflow
                // (open folder / delete) as out-of-the-way quick actions.
                val deleteBlock = rememberPackDeleteBlock(instance.id)
                NxKebabButton(contentDescription = s.text("packCardMore"), tint = Color.White) { dismiss ->
                    NxMenuItem(
                        label   = s.text("serverSettingsOpenFolder"),
                        icon    = NxIcon.FolderOpen,
                        onClick = { dismiss(); onOpenFolder() },
                    )
                    NxMenuItem(
                        label       = s.text("editorDelete"),
                        icon        = NxIcon.Delete,
                        destructive = true,
                        hint        = deleteBlock,
                        enabled     = deleteBlock == null,
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
            PendingUpdateBadge(instance.id, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
    }
}

/**
 * How much of the row a version string may take before it yields.
 *
 * Leaves room for the fork mark and for when the pack was last played, which
 * are the two facts a long build name used to push off the card.
 */
private val VERSION_CHIP_MAX = 180.dp

/**
 * The pill for a build move waiting on [packId], or nothing when none is. Read from the
 * packs module's update statuses and routed to the versions screen, so every card that
 * carries it says the same thing and leads to the same place. Nothing where that module
 * is not brought in.
 */
@Composable
internal fun PendingUpdateBadge(
    packId: String,
    modifier: Modifier = Modifier,
    /** Over a picture by default; a row on a plane passes [NxMetaChipTone.Surface]. */
    tone: NxMetaChipTone = NxMetaChipTone.OnMedia,
) {
    val nav: NavRequests = koinInject()
    val statuses = rememberScopedSource(PackKeys.Updates).valueOrNull() ?: return
    val pending = statuses[packId] as? PackUpdateView.Pending ?: return
    UpdateBadgePill(
        isRollback = pending.direction == PackUpdateView.Pending.OLDER,
        onClick    = { nav.open(Screen.PackVersions(packId)) },
        modifier   = modifier,
        tone       = tone,
    )
}

/** Compact pill for a pending build move; the card-corner sibling of [LaunchStatusPill]. */
@Composable
private fun UpdateBadgePill(
    isRollback: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NxMetaChipTone = NxMetaChipTone.OnMedia,
) {
    val s = nexiraStrings()
    NxMetaChip(
        text     = if (isRollback) s.text("packVersionRollbackBadge") else s.text("packVersionUpdateBadge"),
        modifier = modifier,
        tone     = tone,
        dot      = if (isRollback) NxColor.status(Status.Warning) else NxColor.lead(),
        onClick  = onClick,
    )
}

/** Compact launch-state pill overlaid on the card while a launch of this pack is in flight. */
@Composable
private fun LaunchStatusPill(indication: IndicationCenter.LaunchIndication, modifier: Modifier = Modifier) {
    val s = nexiraStrings()
    val (dotColor, label) = when (indication) {
        IndicationCenter.LaunchIndication.Preparing -> NxColor.status(Status.Info) to s.text("launchPreparing")
        is IndicationCenter.LaunchIndication.Downloading ->
            NxColor.status(Status.Info) to (indication.progress?.let { "${(it * 100).roundToInt()}%" } ?: s.text("launchDownloading").removeSuffix(":"))
        IndicationCenter.LaunchIndication.Running -> NxColor.status(Status.Success) to s.text("launchRunning")
        IndicationCenter.LaunchIndication.Stopping -> NxColor.status(Status.Info) to s.text("launchStopping")
        IndicationCenter.LaunchIndication.Failed  -> NxColor.status(Status.Error) to s.text("launchFailed")
    }
    NxMetaChip(text = label, modifier = modifier, tone = NxMetaChipTone.OnMedia, dot = dotColor)
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
    NxMetaChip(lastPlayedLabel(lastPlayedEpoch), tone = NxMetaChipTone.OnMedia)
}

/** When a pack was last played, in the words a card uses: "1 h ago", "never played". */
@Composable
internal fun lastPlayedLabel(lastPlayedEpoch: Long): String {
    val s = nexiraStrings()
    if (lastPlayedEpoch <= 0L) return s.text("packCardNeverPlayed")
    val dur = Duration.between(Instant.ofEpochSecond(lastPlayedEpoch), Instant.now())
    return when {
        dur.toMinutes() < 1   -> s.text("packCardPlayedJustNow")
        dur.toHours()   < 1   -> s.text("packCardPlayedMinutesAgo", dur.toMinutes())
        dur.toDays()    < 1   -> s.text("packCardPlayedHoursAgo", dur.toHours())
        dur.toDays()    < 14  -> s.text("packCardPlayedDaysAgo", dur.toDays())
        else                  -> s.text("packCardPlayedLongAgo")
    }
}

/** The card's on-media meta chip, kept as a named alias for other over-banner surfaces. */
@Composable
internal fun PackMetaChip(text: String, emphasis: Boolean = false) {
    NxMetaChip(text, tone = if (emphasis) NxMetaChipTone.OnMediaAccent else NxMetaChipTone.OnMedia)
}
