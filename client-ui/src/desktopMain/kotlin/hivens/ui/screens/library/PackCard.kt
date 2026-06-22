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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import hivens.ui.theme.origin
import java.time.Duration
import java.time.Instant

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
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
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
                        MetaChip(instance.packRef.version ?: "—")
                        instance.forkedFrom?.let {
                            MetaChip("fork", emphasis = true)
                        }
                        LastPlayedChip(instance.lastPlayedEpochOrZero)
                    }
                }

                // No Play / Settings on the card: the whole card opens the detail,
                // where launch + every setting live. Card keeps only the overflow
                // (open folder / delete) as out-of-the-way quick actions.
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Symbol(NxIcon.MoreVert, contentDescription = s.packCardMore, tint = Color.White)
                    }
                    NxContextMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        NxMenuItem(
                            label   = s.serverSettingsOpenFolder,
                            icon    = NxIcon.FolderOpen,
                            onClick = { menuOpen = false; onOpenFolder() },
                        )
                        NxMenuItem(
                            label       = s.editorDelete,
                            icon        = NxIcon.Delete,
                            destructive = true,
                            onClick     = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PackAvatar(iconUrl: String?, displayName: String, hue: Color) {
    SubcomposeAsyncImage(
        model              = iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
        loading            = { Box(Modifier.fillMaxSize().background(hue)) },
        error              = { InitialsAvatar(displayName, hue) },
    )
}

@Composable
private fun InitialsAvatar(name: String, hue: Color) {
    val initials = name
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier         = Modifier.fillMaxSize().background(hue),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = initials,
            color      = Color.White,
            style      = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SourceBadge(origin: PackOrigin) {
    val label = when (origin) {
        PackOrigin.Smartycraft -> "SC"
        PackOrigin.Mirror      -> "Mirror"
        PackOrigin.Modrinth    -> "Modrinth"
        PackOrigin.Local       -> "Local"
        PackOrigin.Unknown     -> "?"
    }
    val color = NxTheme.colors.origin(origin)
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetaChip(text: String, emphasis: Boolean = false) {
    AssistChip(
        onClick   = {},
        enabled   = false,
        shape     = MaterialTheme.shapes.extraSmall,
        label     = { Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White) },
        colors    = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (emphasis) NxTheme.colors.primary.copy(alpha = 0.85f)
                                     else          Color.Black.copy(alpha = 0.35f),
            disabledLabelColor     = Color.White,
        ),
        border    = null,
    )
}

@Composable
private fun LastPlayedChip(lastPlayedEpoch: Long) {
    val s = LocalStrings.current
    if (lastPlayedEpoch <= 0L) {
        MetaChip(s.packCardNeverPlayed)
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
    MetaChip(label)
}

/**
 * Workaround helper for the `requiredJava` chip on the detail screen
 * (PackDetailScreen reuses [MetaChip] via this re-exported alias so
 * it doesn't have to duplicate the chip styling).
 */
@Composable
internal fun PackMetaChip(text: String, emphasis: Boolean = false) {
    MetaChip(text, emphasis = emphasis)
}
