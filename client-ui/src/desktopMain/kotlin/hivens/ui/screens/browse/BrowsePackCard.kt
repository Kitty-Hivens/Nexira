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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.ui.components.SourceBadge
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.InitialsAvatar
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.theme.Dimens
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair

/**
 * One Browse row. Same shape as Library's PackCard (banner-as-bg + avatar +
 * title + chips + chevron) so the surfaces read as one design language. The
 * whole card is a click target into the source's detail screen. The source
 * badge follows [CataloguePack.origin], so the card stays source-neutral across
 * the mirror, Modrinth and future sources.
 *
 * Background layers, back to front: a deterministic pixel-art fill (so a packless
 * banner is never a flat green), the real banner image on top when the source
 * carries one ([CataloguePack.bannerUrl]; transparent while loading or on
 * failure, so the art shows through), then a dark scrim for text legibility.
 * The avatar shows the pack icon, falling back to title initials.
 */
@Composable
fun BrowsePackCard(
    pack: CataloguePack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val (hueA, hueB) = NxTheme.colors.decorativePair(pack.id)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.packCardHeight)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().pixelArtBackground(pack.id, hueA, hueB))
        if (pack.bannerUrl != null) {
            AsyncImage(
                model              = pack.bannerUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
        // Banners can be bright, so they get a heavier wash than the dark art.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (pack.bannerUrl != null) 0.45f else 0.32f)))

        Row(
            modifier              = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BrowseAvatar(pack = pack, hue = hueA)

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
                // Mirror summaries sometimes ship tagline == name; don't echo the title.
                if (pack.tagline.isNotBlank() && !pack.tagline.equals(pack.title, ignoreCase = true)) {
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
                    pack.mcVersion?.let { NxMetaChip("MC $it", tone = NxMetaChipTone.OnMedia) }
                    pack.tags.take(3).forEach { tag ->
                        NxMetaChip(
                            if (pack.origin == PackOrigin.Modrinth) s.modrinthCategory(tag) else tag,
                            tone = NxMetaChipTone.OnMedia,
                        )
                    }
                }
            }

            Symbol(
                NxIcon.ArrowForwardIos,
                contentDescription = null,
                tint               = Color.White.copy(alpha = 0.75f),
                modifier           = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun BrowseAvatar(pack: CataloguePack, hue: Color) {
    SubcomposeAsyncImage(
        model              = pack.iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        modifier           = Modifier.size(Dimens.packAvatar).clip(RoundedCornerShape(Dimens.packAvatarCorner)),
        // While the icon resolves, a flat hue avoids a letters-then-icon flash;
        // a missing or broken icon (incl. a null url) lands in error -> initials.
        loading            = { Box(Modifier.fillMaxSize().background(hue)) },
        error              = { InitialsAvatar(pack.title, hue) },
    )
}

