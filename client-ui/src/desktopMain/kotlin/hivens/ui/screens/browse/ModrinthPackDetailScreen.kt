package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.ui.render.MarkdownHtml
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.screens.RetryStateBlock
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.originGradient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Native render of a Modrinth modpack project (#367) plus install. Fetches the
 * project page + versions through the Modrinth catalogue, renders the hero,
 * gallery and CommonMark body, and installs the chosen `.mrpack` version via
 * [PackInstallCoordinator] -- the installed instance lands in Library.
 */
@Composable
fun ModrinthPackDetailScreen(
    projectId: String,
    onBack: () -> Unit,
    onInstalled: (instanceId: String) -> Unit,
) {
    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val coordinator: PackInstallCoordinator = koinInject()
    val scope = rememberCoroutineScope()

    var state by remember(projectId) { mutableStateOf<ModrinthDetailState>(ModrinthDetailState.Loading) }
    var retryTick by remember(projectId) { mutableIntStateOf(0) }
    var installing by remember(projectId) { mutableStateOf<InstallProgress?>(null) }
    var installError by remember(projectId) { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, retryTick) {
        state = ModrinthDetailState.Loading
        state = try {
            val catalogue = registry.forOrigin(PackOrigin.Modrinth)
            if (catalogue == null) {
                ModrinthDetailState.Error(s.browseDetailErrorMessage)
            } else {
                ModrinthDetailState.Loaded(withContext(Dispatchers.IO) { catalogue.details(projectId) })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ModrinthDetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Symbol(NxIcon.ArrowBack, s.navBack, tint = CelestiaTheme.colors.textPrimary)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text       = (state as? ModrinthDetailState.Loaded)?.details?.title ?: "Modrinth",
                style      = MaterialTheme.typography.titleLarge,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.Bold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = CelestiaTheme.colors.outline.copy(alpha = 0.4f))

        when (val st = state) {
            ModrinthDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color       = CelestiaTheme.colors.primary.copy(alpha = 0.55f),
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(28.dp),
                )
            }
            is ModrinthDetailState.Error -> RetryStateBlock(
                title      = s.browseDetailErrorTitle,
                message    = st.message,
                retryLabel = s.browseRetry,
                onRetry    = { retryTick++ },
                modifier   = Modifier.fillMaxSize(),
            )
            is ModrinthDetailState.Loaded -> DetailBody(
                details      = st.details,
                installing   = installing,
                installError = installError,
                onInstall    = { version ->
                    installError = null
                    installing = InstallProgress(version.id, 0, 0, "")
                    scope.launch {
                        try {
                            val instance = coordinator.install(
                                pack = CataloguePack(
                                    origin  = st.details.origin,
                                    id      = st.details.id,
                                    title   = st.details.title,
                                    tagline = st.details.tagline,
                                    iconUrl = st.details.iconUrl,
                                ),
                                version = version,
                                progress = { current, total, filename ->
                                    installing = InstallProgress(version.id, current, total, filename)
                                },
                            )
                            onInstalled(instance.id)
                        } catch (e: Exception) {
                            installError = e.message ?: s.browseDetailInstallFailedGeneric
                        } finally {
                            installing = null
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun DetailBody(
    details: CataloguePackDetails,
    installing: InstallProgress?,
    installError: String?,
    onInstall: (CataloguePackVersion) -> Unit,
) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(details)

        if (details.galleryUrls.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(details.galleryUrls) { url ->
                    AsyncImage(
                        model              = url,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.width(220.dp).height(124.dp).clip(MaterialTheme.shapes.small),
                    )
                }
            }
        }

        details.bodyMarkdown?.let { MarkdownHtml(markdown = it, modifier = Modifier.fillMaxWidth()) }

        if (installError != null) {
            Text(
                text  = installError,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.error,
            )
        }

        if (installing != null) {
            val p = installing
            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(CelestiaTheme.colors.surface)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text       = s.browseDetailInstallRunningTitle,
                    style      = MaterialTheme.typography.titleSmall,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text  = if (p.total > 0) {
                        s.browseDetailInstallProgress(p.filename, p.current, p.total)
                    } else {
                        s.browseDetailInstallStarting
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.current.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                        color    = CelestiaTheme.colors.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color    = CelestiaTheme.colors.primary,
                    )
                }
            }
        }

        Text(
            text       = s.browseDetailVersionTitle,
            style      = MaterialTheme.typography.titleMedium,
            color      = CelestiaTheme.colors.primary,
            fontWeight = FontWeight.Bold,
        )
        details.versions.forEach { v ->
            VersionRow(
                version       = v,
                installing    = installing?.versionId == v.id,
                anyInstalling = installing != null,
                onInstall     = { onInstall(v) },
            )
        }
    }
}

@Composable
private fun Hero(details: CataloguePackDetails) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(CelestiaTheme.colors.originGradient(details.origin)),
    ) {
        details.bannerUrl?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        Row(
            modifier              = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            details.iconUrl?.let {
                AsyncImage(
                    model              = it,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(72.dp).clip(MaterialTheme.shapes.medium),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text       = details.title,
                    style      = MaterialTheme.typography.headlineSmall,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines   = 2,
                    overflow   = TextOverflow.Ellipsis,
                )
                if (details.tagline.isNotBlank()) {
                    Text(
                        text     = details.tagline,
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(
    version: CataloguePackVersion,
    installing: Boolean,
    anyInstalling: Boolean,
    onInstall: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(CelestiaTheme.colors.surface)
            .padding(12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = version.name.ifBlank { version.versionNumber },
                style      = MaterialTheme.typography.bodyMedium,
                color      = CelestiaTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            val meta = (version.loaders + version.mcVersions).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = CelestiaTheme.colors.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onInstall,
            enabled = !anyInstalling,
            shape   = MaterialTheme.shapes.small,
            colors  = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
                contentColor   = Color.White,
            ),
        ) {
            if (installing) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Text(s.browseDetailInstallButton, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private data class InstallProgress(val versionId: String, val current: Int, val total: Int, val filename: String)

private sealed class ModrinthDetailState {
    object Loading : ModrinthDetailState()
    data class Loaded(val details: CataloguePackDetails) : ModrinthDetailState()
    data class Error(val message: String) : ModrinthDetailState()
}
