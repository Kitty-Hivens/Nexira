package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.smrt.ModIconResolver
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativeColor
import org.koin.compose.koinInject

/**
 * Square mod icon with the full resolver fallback chain rendered
 * inline: direct iconUrl -> Modrinth project lookup -> letter avatar
 * derived from filename. Coil's disk cache handles repeat loads of
 * the resolved URL automatically; the ModIconResolver caches the
 * URL-resolution step so repeated renders of the same mod across
 * scroll positions don't keep poking Modrinth.
 *
 * Sized via [size] so the same composable serves both the compact
 * row icon (24-32dp) and the larger expanded-panel icon (48-64dp).
 */
@Composable
fun ModIconImage(
    mod: SmrtModEntry,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val resolver: ModIconResolver = koinInject()
    var resolved by remember(mod.filename) { mutableStateOf<String?>(null) }
    var attempted by remember(mod.filename) { mutableStateOf(false) }

    LaunchedEffect(mod.filename) {
        resolved = resolver.resolve(mod)
        attempted = true
    }

    Box(
        modifier         = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 6)),
        contentAlignment = Alignment.Center,
    ) {
        val url = resolved
        if (url != null) {
            AsyncImage(
                model              = url,
                contentDescription = null,
                modifier           = Modifier.size(size),
            )
        } else if (attempted) {
            LetterAvatar(name = mod.filename, size = size)
        } else {
            // Pre-resolve: blank-but-styled box keeps row height stable
            // before the LaunchedEffect lands. Same colour as the
            // letter-avatar fallback, so the visual swap on resolution
            // failure is invisible.
            Box(
                modifier = Modifier
                    .size(size)
                    .background(NxTheme.colors.decorativeColor(mod.filename)),
            )
        }
    }
}

@Composable
private fun LetterAvatar(name: String, size: Dp) {
    val initials = name
        .removeSuffix(".jar")
        .split(' ', '-', '_', '.')
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercaseChar().toString() }
        .ifEmpty { "?" }
    Box(
        modifier         = Modifier
            .size(size)
            .background(NxTheme.colors.decorativeColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text       = initials,
            color      = Color.White,
            style      = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}
