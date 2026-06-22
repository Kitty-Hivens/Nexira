package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.ImageGallery
import hivens.ui.components.galleryMedia
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.render.MarkdownHtml
import hivens.ui.render.openInBrowser
import hivens.ui.screens.RetryStateBlock
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Source-neutral catalogue detail page. Both the Hivens mirror and Modrinth flow
 * through the SAME screen: the read side comes off [PackCatalogueRegistry] and the
 * write side off [PackInstallCoordinator], so the only thing that varies per source
 * is which provider the registry hands back. The install glyph in the hero installs
 * the single version directly (the mirror serves one) or opens a version picker when
 * the source publishes several (Modrinth).
 *
 * The compat metadata (MC / loader / runtime / tags) renders in the in-page sidebar
 * for now; per the shell rule it will move into the contextual right rail once that
 * surface becomes screen-aware.
 */
@Composable
fun CataloguePackDetailScreen(
    origin: PackOrigin,
    packId: String,
    onBack: () -> Unit,
    onInstalled: (instanceId: String) -> Unit,
) {
    val s = LocalStrings.current
    val registry: PackCatalogueRegistry = koinInject()
    val coordinator: PackInstallCoordinator = koinInject()
    val scope = rememberCoroutineScope()

    // Back is the top-bar breadcrumb's job now (no hero arrow), but automation
    // still needs a handle on it.
    PuppetClick("catalogue.detail.back") { onBack() }

    var state by remember(origin, packId) { mutableStateOf<DetailState>(DetailState.Loading) }
    var retryTick by remember(origin, packId) { mutableIntStateOf(0) }
    var installing by remember(origin, packId) { mutableStateOf<InstallProgress?>(null) }
    var installError by remember(origin, packId) { mutableStateOf<String?>(null) }
    var showPicker by remember(origin, packId) { mutableStateOf(false) }

    fun install(details: CataloguePackDetails, version: CataloguePackVersion) {
        installError = null
        installing = InstallProgress(version.id, 0, 0, "")
        scope.launch {
            try {
                val instance = coordinator.install(
                    pack = CataloguePack(
                        origin  = details.origin,
                        id      = details.id,
                        title   = details.title,
                        tagline = details.tagline,
                        iconUrl = details.iconUrl,
                    ),
                    version  = version,
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
    }

    LaunchedEffect(origin, packId, retryTick) {
        state = DetailState.Loading
        state = try {
            val catalogue = registry.forOrigin(origin)
            if (catalogue == null) {
                DetailState.Error(s.browseDetailErrorMessage)
            } else {
                DetailState.Loaded(withContext(Dispatchers.IO) { catalogue.details(packId) })
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val loaded = state as? DetailState.Loaded
        CatalogueHero(
            title     = loaded?.details?.title ?: packId,
            tagline   = loaded?.details?.tagline.orEmpty(),
            iconUrl   = loaded?.details?.iconUrl,
            bannerUrl = loaded?.details?.bannerUrl,
            seed      = packId,
            // Back lives in the top-bar breadcrumb now -- no duplicate hero arrow.
            onBack    = null,
            action    = loaded?.let { ld ->
                {
                    InstallGlyphButton(
                        onClick = {
                            val versions = ld.details.versions
                            when {
                                versions.size > 1 -> showPicker = true
                                else              -> versions.firstOrNull()?.let { install(ld.details, it) }
                            }
                        },
                        enabled = installing == null && ld.details.versions.isNotEmpty(),
                        busy    = installing != null,
                    )
                }
            },
        )

        when (val st = state) {
            DetailState.Loading -> Box(
                Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = NxTheme.colors.primary.copy(alpha = 0.55f),
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(28.dp),
                )
            }
            is DetailState.Error -> RetryStateBlock(
                title      = s.browseDetailErrorTitle,
                message    = st.message,
                retryLabel = s.browseRetry,
                onRetry    = { retryTick++ },
                modifier   = Modifier.fillMaxWidth().padding(32.dp),
            )
            is DetailState.Loaded -> DetailBody(details = st.details, installing = installing, installError = installError)
        }
    }

    val pickerTarget = state as? DetailState.Loaded
    if (showPicker && pickerTarget != null) {
        VersionPickerModal(
            versions            = pickerTarget.details.versions,
            installingVersionId = installing?.versionId,
            onPick              = { v -> showPicker = false; install(pickerTarget.details, v) },
            onDismiss           = { showPicker = false },
        )
    }
}

@Composable
private fun DetailBody(details: CataloguePackDetails, installing: InstallProgress?, installError: String?) {
    val s = LocalStrings.current
    // A body link to a video (direct file or a service page) opens in-app; the
    // rest go to the browser as before.
    var videoLink by remember { mutableStateOf<String?>(null) }
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (details.galleryUrls.isNotEmpty()) {
                ImageGallery(media = galleryMedia(details.galleryUrls, details.galleryThumbUrls))
            }
            details.bodyMarkdown?.let {
                MarkdownHtml(
                    markdown = it,
                    modifier = Modifier.fillMaxWidth(),
                    onLink   = { url -> if (isPlayableVideoUrl(url)) videoLink = url else openInBrowser(url) },
                )
            }

            if (installError != null) {
                Text(installError, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.error)
            }
            if (installing != null) InstallProgressBlock(installing)
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val v = details.versions.firstOrNull()
            SidebarBlock(title = s.browseDetailCompatTitle) {
                v?.mcVersions?.firstOrNull()?.let { MetaRow(s.browseDetailCompatMc, it) }
                v?.loaders?.firstOrNull()?.let { MetaRow(s.browseDetailCompatLoader, it.replaceFirstChar { c -> c.uppercase() }) }
                details.runtimeLabel?.let { MetaRow(s.browseDetailCompatJava, it) }
            }
            if (details.tags.isNotEmpty()) {
                SidebarBlock(title = s.browseDetailTagsTitle) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { details.tags.forEach { Chip(it) } }
                }
            }
        }
    }
    videoLink?.let { url ->
        FullscreenVideo(url = url, onDismiss = { videoLink = null })
    }
}

@Composable
private fun InstallProgressBlock(p: InstallProgress) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(NxTheme.colors.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text       = s.browseDetailInstallRunningTitle,
            style      = MaterialTheme.typography.titleSmall,
            color      = NxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text  = if (p.total > 0) s.browseDetailInstallProgress(p.filename, p.current, p.total) else s.browseDetailInstallStarting,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
        if (p.total > 0) {
            LinearProgressIndicator(progress = { p.current.toFloat() / p.total }, modifier = Modifier.fillMaxWidth(), color = NxTheme.colors.primary)
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NxTheme.colors.primary)
        }
    }
}

@Composable
private fun VersionRow(version: CataloguePackVersion, installing: Boolean, anyInstalling: Boolean, onInstall: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(glassSurfaceAlpha(if (hovered && !anyInstalling) 0.7f else 0.4f))
            .clickable(interactionSource = interaction, indication = null, enabled = !anyInstalling, onClick = onInstall)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text       = version.name.ifBlank { version.versionNumber },
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                version.mcVersions.firstOrNull()?.let { Chip("MC $it") }
                version.loaders.take(2).forEach { Chip(it.replaceFirstChar { c -> c.uppercase() }) }
            }
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(NxTheme.colors.primary.copy(alpha = if (anyInstalling) 0.3f else if (hovered) 1f else 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            if (installing) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Symbol(NxIcon.Download, contentDescription = null, tint = Color.White, size = 18.dp)
            }
        }
    }
}

@Composable
private fun VersionPickerModal(
    versions: List<CataloguePackVersion>,
    installingVersionId: String?,
    onPick: (CataloguePackVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            modifier         = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 600.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(NxTheme.colors.surface)
                    .border(1.dp, NxTheme.colors.outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                // Header: title + count, close in a hover-able circle, then a hairline rule.
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(start = 22.dp, end = 14.dp, top = 18.dp, bottom = 14.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.browseDetailVersionTitle, style = MaterialTheme.typography.titleMedium, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                        Text("${versions.size}", style = MaterialTheme.typography.labelMedium, color = NxTheme.colors.textSecondary)
                    }
                    Box(
                        modifier         = Modifier.size(32.dp).clip(CircleShape).background(glassSurfaceAlpha(0.5f)).clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Symbol(NxIcon.Close, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 18.dp)
                    }
                }
                HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
                Column(
                    modifier            = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    versions.forEach { v ->
                        VersionRow(
                            version       = v,
                            installing    = installingVersionId == v.id,
                            anyInstalling = installingVersionId != null,
                            onInstall     = { onPick(v) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(glassSurfaceAlpha(0.6f)).padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
        Text(value, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = MaterialTheme.shapes.extraSmall,
        label   = { Text(text, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textPrimary) },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = glassSurfaceAlpha(0.4f),
            disabledLabelColor     = NxTheme.colors.textPrimary,
        ),
        border  = null,
    )
}

private data class InstallProgress(val versionId: String, val current: Int, val total: Int, val filename: String)

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val details: CataloguePackDetails) : DetailState()
    data class Error(val message: String) : DetailState()
}
