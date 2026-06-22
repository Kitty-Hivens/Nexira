package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.ui.Screen
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
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

@Composable
private fun PackTile(pack: PackInstance, onClick: () -> Unit) {
    val played = pack.lastPlayedEpochOrZero > 0L
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.40f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(NxTheme.colors.textSecondary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon = if (played) NxIcon.History else NxIcon.Inventory2,
                contentDescription = null,
                tint               = NxTheme.colors.textSecondary.copy(alpha = 0.75f),
                modifier           = Modifier.size(18.dp),
            )
        }
        Text(
            text       = pack.displayName,
            style      = MaterialTheme.typography.bodyMedium,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        Text(
            text     = pack.packRef.id,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyPacksCta(onBrowse: () -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.40f))
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
