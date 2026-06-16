package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
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
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.origin
import hivens.ui.theme.originGradient

/**
 * One Browse row. Same shape as Library's PackCard (banner-as-bg + avatar +
 * title + chips + chevron) so the surfaces read as one design language. The
 * whole card is a click target into the source's detail screen. Colour, avatar
 * and source badge follow [CataloguePack.origin], so the card is source-neutral
 * across the mirror, Modrinth and future sources.
 */
@Composable
fun BrowsePackCard(
    pack: CataloguePack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(CelestiaTheme.colors.originGradient(pack.origin))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        Row(
            modifier              = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrowseAvatar(pack.title, pack.origin)

            Column(
                modifier            = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text       = pack.title,
                        style      = MaterialTheme.typography.titleMedium,
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                        modifier   = Modifier.weight(1f, fill = false),
                    )
                    SourceBadge(pack.origin)
                }
                if (pack.tagline.isNotBlank()) {
                    Text(
                        text     = pack.tagline,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = Color.White.copy(alpha = 0.80f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pack.mcVersion?.let { MetaChip("MC $it") }
                    pack.tags.take(2).forEach { MetaChip(it) }
                }
            }

            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.75f),
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun BrowseAvatar(title: String, origin: PackOrigin) {
    val initials = title
        .split(' ', '-', '_')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier         = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CelestiaTheme.colors.origin(origin)),
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
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(CelestiaTheme.colors.origin(origin).copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text       = originLabel(origin),
            style      = MaterialTheme.typography.labelSmall,
            color      = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun originLabel(origin: PackOrigin): String = when (origin) {
    PackOrigin.Mirror -> "Mirror"
    PackOrigin.Modrinth -> "Modrinth"
    PackOrigin.Smartycraft -> "SmartyCraft"
    PackOrigin.Local -> "Local"
    PackOrigin.Unknown -> "Other"
}

@Composable
private fun MetaChip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = MaterialTheme.shapes.extraSmall,
        label   = { Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White) },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = Color.Black.copy(alpha = 0.35f),
            disabledLabelColor     = Color.White,
        ),
        border  = null,
    )
}
