package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * Splits [items] into fixed-size pages and renders one page at a time with a
 * compact prev/next control strip below. The page body takes the remaining
 * height (weight 1); the controls sit at the bottom.
 *
 * Degrades to a plain single render -- no controls, no page state -- when
 * [pageSize] <= 0 or everything fits on one page, so callers can pass an
 * adaptive page size that occasionally exceeds the item count without a stray
 * "1 / 1" strip.
 */
@Composable
fun <T> PagedContent(
    items: List<T>,
    pageSize: Int,
    modifier: Modifier = Modifier,
    content: @Composable (pageItems: List<T>) -> Unit,
) {
    if (pageSize <= 0 || items.size <= pageSize) {
        Column(modifier) { content(items) }
        return
    }

    val pageCount = (items.size + pageSize - 1) / pageSize
    // Reset to the first page when the data set or page size changes -- a stale
    // index could otherwise point past the new last page.
    var page by remember(items.size, pageSize) { mutableStateOf(0) }
    val clamped = page.coerceIn(0, pageCount - 1)
    val start = clamped * pageSize
    val pageItems = items.subList(start, minOf(start + pageSize, items.size))

    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content(pageItems) }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(
                onClick  = { if (clamped > 0) page = clamped - 1 },
                enabled  = clamped > 0,
                modifier = Modifier.size(30.dp),
            ) {
                Symbol(NxIcon.ChevronLeft,
                    contentDescription = null,
                    tint = if (clamped > 0) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary.copy(alpha = 0.4f),
                )
            }
            Text(
                text     = "${clamped + 1} / $pageCount",
                style    = MaterialTheme.typography.labelMedium,
                color    = NxTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(
                onClick  = { if (clamped < pageCount - 1) page = clamped + 1 },
                enabled  = clamped < pageCount - 1,
                modifier = Modifier.size(30.dp),
            ) {
                Symbol(NxIcon.ChevronRight,
                    contentDescription = null,
                    tint = if (clamped < pageCount - 1) NxTheme.colors.textPrimary else NxTheme.colors.textSecondary.copy(alpha = 0.4f),
                )
            }
        }
    }
}
