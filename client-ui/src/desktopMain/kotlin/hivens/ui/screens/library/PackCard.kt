package hivens.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.ui.screens.detail.PackDetailScreen
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.origin
import hivens.ui.theme.originGradient
import java.time.Duration
import java.time.Instant

/**
 * One Library row. Banner-as-background (placeholder gradient until
 * AsyncImage plumbing lands -- bannerUrl will be honored when the
 * catalogue service starts populating it from manifests), avatar +
 * title + metadata chips overlaid on top, quick actions on the
 * right. Whole card click-routes to [PackDetailScreen]; the explicit
 * Play / Settings / Overflow buttons short-circuit common actions
 * without the detail hop.
 *
 * Source-badge is the small chip next to the title; it differentiates
 * cards from the four [PackOrigin] values at a glance per
 * [[project_home_library_ia]]'s unified-entity-with-source-badge
 * rule.
 *
 * Sizes are deliberately defaulted, not pinned to a design spec --
 * Atelier visual rework will revisit; this is the working baseline.
 */
@Composable
fun PackCard(
    instance: PackInstance,
    onOpenDetail: () -> Unit,
    onPlay: () -> Unit,
    onSettings: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = CelestiaTheme.colors.originGradient(instance.packRef.origin)
    val s = LocalStrings.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .clickable(onClick = onOpenDetail),
    ) {
        // Dim overlay so any future banner image stays legible behind
        // the title / chips. With the placeholder gradient the dim is
        // visually redundant but keeps the layering consistent when
        // bannerUrl support lands.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Row(
            modifier              = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PackAvatar(instance)

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
                    SourceBadge(instance.packRef.origin)
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

            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(
                    onClick        = onPlay,
                    shape          = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    colors         = ButtonDefaults.buttonColors(
                        containerColor = CelestiaTheme.colors.primary,
                        contentColor   = Color.White,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(s.packCardPlay, fontWeight = FontWeight.SemiBold)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = s.packCardSettings, tint = Color.White)
                }
                IconButton(onClick = onMore) {
                    Icon(Icons.Default.MoreVert, contentDescription = s.packCardMore, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PackAvatar(instance: PackInstance) {
    val initials = instance.displayName
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier         = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CelestiaTheme.colors.origin(instance.packRef.origin)),
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
    val color = CelestiaTheme.colors.origin(origin)
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
            disabledContainerColor = if (emphasis) CelestiaTheme.colors.primary.copy(alpha = 0.85f)
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
