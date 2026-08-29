package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.ui.Screen
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
data class RecentProps(
    @PropLabel("widget.home.new.recent.title") val title: String = "",
    @PropLabel("widget.home.new.recent.maxTiles") @PropRange(1.0, 12.0) val maxTiles: Int = 5,
)

// Pack tiles row. Sort priority: played packs first by recency, then
// unplayed packs by install order. A fresh install with packs but no
// launches still shows the tiles (sorted by createdAt), so the new
// home reads as populated rather than blank. Empty repo shows a CTA
// pointing at Browse.
@Widget(id = "home.new.recent", displayName = "widget.home.new.recent", propsClass = RecentProps::class)
@Composable
fun HomeNewRecent(instance: WidgetInstance) {
    val p = instance.rememberProps<RecentProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())

    if (all.isEmpty()) {
        EmptyPacksCta(onBrowse = { ctx.onScreenChange(Screen.Browse) })
        return
    }

    val recent = remember(all, p.maxTiles) {
        all.sortedWith(
            compareByDescending<PackInstance> { it.lastPlayedEpochOrZero }
                .thenByDescending { it.createdAtEpoch },
        ).take(p.maxTiles)
    }

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text       = p.title.ifBlank { s.homeRecentTitle },
            style      = MaterialTheme.typography.titleSmall,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding        = PaddingValues(vertical = 2.dp),
        ) {
            items(items = recent, key = { it.id }) { pack ->
                PackTile(
                    pack    = pack,
                    onClick = { ctx.onScreenChange(Screen.PackDetail(pack.id)) },
                )
            }
        }
    }
}

// Mini version of the Library card's three-layer treatment: pixel-art fill,
// captured banner when the pack has one, scrim, caption. Same footprint the
// glyph tile had -- the row gets art, not more space.
@Composable
private fun PackTile(pack: PackInstance, onClick: () -> Unit) {
    val (hueA, hueB) = NxTheme.colors.decorativePair(pack.id)
    val art = rememberPackArt(pack)
    Box(
        modifier = Modifier
            .width(180.dp)
            .height(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().pixelArtBackground(pack.id, hueA, hueB))
        if (art.bannerUrl != null) {
            AsyncImage(
                model              = art.bannerUrl,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.05f),
                    1f to Color.Black.copy(alpha = 0.68f),
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp),
        ) {
            Text(
                text       = pack.displayName,
                style      = MaterialTheme.typography.bodyMedium,
                color      = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = pack.packRef.id,
                style    = MaterialTheme.typography.labelSmall,
                color    = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyPacksCta(onBrowse: () -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = s.homeNoPacksTitle,
            style      = MaterialTheme.typography.titleSmall,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text  = s.homeNoPacksBody,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            onClick = onBrowse,
            shape   = MaterialTheme.shapes.small,
            colors  = ButtonDefaults.outlinedButtonColors(
                contentColor = NxTheme.colors.primary,
            ),
        ) {
            Symbol(NxIcon.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.browseOpen, fontWeight = FontWeight.Medium)
        }
    }
}
