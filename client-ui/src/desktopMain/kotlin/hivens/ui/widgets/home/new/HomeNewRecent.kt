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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

private const val MAX_RECENT = 5

// Recent packs LRU row. Sorted by lastPlayedEpochOrZero descending;
// packs that have never been played fall to the end. Empty repo
// elides the whole widget so the slot does not show a stranded
// section header.
@Widget(id = "home.new.recent", displayName = "Recent packs")
@Composable
fun HomeNewRecent(instance: WidgetInstance) {
    val ctx = LocalHomeNewContext.current
    val repo: IPackRepository = koinInject()
    val all by remember { repo.observe() }.collectAsState(initial = emptyList())

    val recent = remember(all) {
        all.filter { it.lastPlayedEpochOrZero > 0L }
            .sortedByDescending { it.lastPlayedEpochOrZero }
            .take(MAX_RECENT)
    }
    if (recent.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text       = "Недавние сборки",
            style      = MaterialTheme.typography.titleSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding      = PaddingValues(vertical = 2.dp),
        ) {
            items(items = recent, key = { it.id }) { pack ->
                RecentPackTile(
                    pack    = pack,
                    onClick = { ctx.onScreenChange(Screen.PackDetail(pack.id)) },
                )
            }
        }
    }
}

@Composable
private fun RecentPackTile(pack: PackInstance, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(glassSurfaceAlpha(0.40f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CelestiaTheme.colors.textSecondary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.History,
                contentDescription = null,
                tint               = CelestiaTheme.colors.textSecondary.copy(alpha = 0.75f),
                modifier           = Modifier.size(18.dp),
            )
        }
        Text(
            text       = pack.displayName,
            style      = MaterialTheme.typography.bodyMedium,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
        )
        Text(
            text     = pack.packRef.id,
            style    = MaterialTheme.typography.bodySmall,
            color    = CelestiaTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
