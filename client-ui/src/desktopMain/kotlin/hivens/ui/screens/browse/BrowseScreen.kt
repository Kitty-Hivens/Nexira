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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.core.api.catalogue.CataloguePack
import hivens.core.data.PackOrigin
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxButton
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
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
 * Clicking a card opens the source's detail screen. Importing a local pack lives
 * in Library (it adds to the collection), not here.
 */
@Composable
fun BrowseScreen(
    onOpenPack: (CataloguePack) -> Unit,
) {
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

    Column(Modifier.fillMaxSize()) {
        // Title lives in the top-bar breadcrumb now -- no in-screen duplicate.
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Source switcher -- one chip per registered catalogue origin.
            if (origins.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    origins.forEach { o ->
                        SourceTab(label = originLabel(o), selected = o == origin) { origin = o }
                        PuppetClick("browse.source.${o.name}") { origin = o }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            SearchField(
                value         = query,
                onValueChange = { query = it },
                placeholder   = s.browseSearchPlaceholder,
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
}

/** Compact, rounded, filled search field (a bare OutlinedTextField sat too tall and read as a form input). */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    BasicTextField(
        value         = value,
        onValueChange = onValueChange,
        singleLine    = true,
        textStyle     = MaterialTheme.typography.bodyMedium.copy(color = NxTheme.colors.textPrimary),
        cursorBrush   = SolidColor(NxTheme.colors.primary),
        modifier      = Modifier.fillMaxWidth(),
    ) { inner ->
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(NxTheme.colors.surface)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Symbol(
                NxIcon.Search,
                contentDescription = null,
                tint               = NxTheme.colors.textSecondary,
                modifier           = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text  = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NxTheme.colors.textSecondary,
                    )
                }
                inner()
            }
        }
    }
}

@Composable
private fun SourceTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) NxTheme.colors.primary else NxTheme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            color      = if (selected) Color.White else NxTheme.colors.textSecondary,
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
            color       = NxTheme.colors.primary.copy(alpha = 0.55f),
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
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = s.browseEmptyMessage,
                style     = MaterialTheme.typography.bodyMedium,
                color     = NxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 420.dp),
            )
            NxButton(label = s.browseRetry, onClick = onRetry)
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
            PuppetClick("browse.open.${pack.origin}.${pack.id}") { onOpenPack(pack) }
        }
    }
}

sealed class BrowseState {
    object Loading : BrowseState()
    object Empty   : BrowseState()
    data class Loaded(val packs: List<CataloguePack>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}
