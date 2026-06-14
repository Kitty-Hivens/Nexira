package hivens.ui.screens.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.launcher.smrt.SmrtPackClient
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.RetryStateBlock
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Browse = the catalog of everything installable. First wired surface:
 * pack listing from the Hivens mirror via [SmrtPackClient.listPacks].
 *
 * Click navigation + standalone install lives in the next PR; this one
 * just renders the catalog so a user can see what is available the
 * moment the mirror publishes a pack manifest.
 *
 * Four states:
 *  - [BrowseState.Loading] -- first fetch in flight
 *  - [BrowseState.Loaded]  -- at least one pack returned
 *  - [BrowseState.Empty]   -- listing succeeded but empty
 *  - [BrowseState.Error]   -- network / parse failure (retry button)
 */
@Composable
fun BrowseScreen(onOpenPack: (packId: String) -> Unit) {
    PuppetScreen("Browse")

    val s = LocalStrings.current
    val client: SmrtPackClient = koinInject()
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.retry") { retryTick++ }

    LaunchedEffect(retryTick) {
        state = BrowseState.Loading
        state = try {
            val listing = withContext(Dispatchers.IO) { client.listPacks() }
            if (listing.packs.isEmpty()) BrowseState.Empty
            else BrowseState.Loaded(listing.packs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperatively cancel -- a fresh retryTick (or composition
            // leave) restarts this LaunchedEffect, and converting the
            // cancellation into an Error would surface spurious error UI
            // for a click the user already overrode.
            throw e
        } catch (e: Exception) {
            BrowseState.Error(e.message ?: s.browseErrorMessage)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text(
            text       = s.browseTitle,
            style      = MaterialTheme.typography.headlineSmall,
            color      = CelestiaTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = s.browseSubtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))

        when (val st = state) {
            BrowseState.Loading -> BrowseLoading()
            BrowseState.Empty   -> BrowseEmpty(onRetry = { retryTick++ })
            is BrowseState.Error -> BrowseError(message = st.message, onRetry = { retryTick++ })
            is BrowseState.Loaded -> BrowseList(packs = st.packs, onOpenPack = onOpenPack)
        }
    }
}

@Composable
private fun BrowseLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = CelestiaTheme.colors.primary.copy(alpha = 0.55f),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(28.dp),
        )
    }
}

@Composable
private fun BrowseEmpty(onRetry: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text       = s.browseEmptyTitle,
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = s.browseEmptyMessage,
                style     = MaterialTheme.typography.bodyMedium,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 420.dp),
            )
            Button(
                onClick = onRetry,
                shape   = MaterialTheme.shapes.small,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                    contentColor   = Color.White,
                ),
            ) { Text(s.browseRetry, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun BrowseError(message: String, onRetry: () -> Unit) {
    val s = LocalStrings.current
    RetryStateBlock(
        title      = s.browseErrorTitle,
        message    = message,
        retryLabel = s.browseRetry,
        onRetry    = onRetry,
        modifier   = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BrowseList(packs: List<SmrtPackSummary>, onOpenPack: (String) -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = packs, key = { it.packId }) { pack ->
            BrowsePackCard(pack = pack, onClick = { onOpenPack(pack.packId) })
        }
    }
}

sealed class BrowseState {
    object Loading : BrowseState()
    object Empty   : BrowseState()
    data class Loaded(val packs: List<SmrtPackSummary>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
