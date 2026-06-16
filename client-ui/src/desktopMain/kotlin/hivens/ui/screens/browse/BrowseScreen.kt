package hivens.ui.screens.browse

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.screens.RetryStateBlock
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Browse = the catalogue of everything installable, across sources. A source
 * switcher (Hivens mirror / Modrinth) drives which [hivens.core.api.interfaces.IPackCatalogueService]
 * the search + grid read from; the search box queries the active source
 * (Modrinth searches its catalogue, the mirror filters its listing client-side).
 * Clicking a card opens the source's detail screen.
 */
@Composable
fun BrowseScreen(onOpenPack: (CataloguePack) -> Unit) {
    PuppetScreen("Browse")

    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val origins = registry.origins

    var origin by remember { mutableStateOf(origins.firstOrNull() ?: PackOrigin.Mirror) }
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var retryTick by remember { mutableIntStateOf(0) }

    PuppetClick("browse.retry") { retryTick++ }
    PuppetField("browse.search", query) { query = it }

    // Debounce typing so each keystroke does not hit the network.
    LaunchedEffect(query) {
        delay(350.milliseconds)
        submittedQuery = query
    }

    LaunchedEffect(origin, submittedQuery, retryTick) {
        state = BrowseState.Loading
        state = try {
            val catalogue = registry.forOrigin(origin)
                ?: return@LaunchedEffect run { state = BrowseState.Empty }
            val packs = withContext(Dispatchers.IO) { catalogue.search(submittedQuery) }
            if (packs.isEmpty()) BrowseState.Empty else BrowseState.Loaded(packs)
        } catch (e: kotlinx.coroutines.CancellationException) {
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

        // Source switcher -- one chip per registered catalogue origin.
        if (origins.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                origins.forEach { o ->
                    SourceTab(label = originLabel(o), selected = o == origin) { origin = o }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value         = query,
            onValueChange = { query = it },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
            leadingIcon   = { Icon(Icons.Default.Search, contentDescription = null) },
            label         = { Text(s.browseTitle) },
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
private fun SourceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (selected) Color.White else CelestiaTheme.colors.textSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun originLabel(origin: PackOrigin): String = when (origin) {
    PackOrigin.Mirror -> "Hivens"
    PackOrigin.Modrinth -> "Modrinth"
    PackOrigin.Smartycraft -> "SmartyCraft"
    PackOrigin.Local -> "Local"
    PackOrigin.Unknown -> "Other"
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
private fun BrowseList(packs: List<CataloguePack>, onOpenPack: (CataloguePack) -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = packs, key = { "${it.origin}:${it.id}" }) { pack ->
            BrowsePackCard(pack = pack, onClick = { onOpenPack(pack) })
        }
    }
}

sealed class BrowseState {
    object Loading : BrowseState()
    object Empty   : BrowseState()
    data class Loaded(val packs: List<CataloguePack>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
