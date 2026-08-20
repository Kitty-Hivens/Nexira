package hivens.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackDetails
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackOrigin
import hivens.launcher.InstallPhase
import hivens.launcher.PackInstallService
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.ui.components.FullscreenVideo
import hivens.ui.components.ImageGallery
import hivens.ui.components.galleryMedia
import hivens.ui.components.isPlayableVideoUrl
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.render.MarkdownHtml
import hivens.ui.render.openInBrowser
import hivens.ui.screens.versions.PickerIntent
import hivens.ui.screens.versions.PickerVersion
import hivens.ui.screens.versions.VersionPickerWindow
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Source-neutral catalogue detail page. Both the Hivens mirror and Modrinth flow
 * through the SAME screen: the read side comes off [PackCatalogueRegistry] and the
 * write side off [PackInstallService], so the only thing that varies per source
 * is which provider the registry hands back. The install glyph in the hero installs
 * the single version directly (the mirror serves one) or opens a version picker when
 * the source publishes several (Modrinth).
 *
 * The install itself runs on [PackInstallService]'s app scope, so progress is read
 * back from the service rather than owned here: leaving this screen no longer
 * cancels the download, and re-entering while it runs re-attaches to the live
 * progress instead of showing an idle button.
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
    val installService: PackInstallService = koinInject()

    // Back is the top-bar breadcrumb's job now (no hero arrow), but automation
    // still needs a handle on it.
    PuppetClick("catalogue.detail.back") { onBack() }

    var state by remember(origin, packId) { mutableStateOf<DetailState>(DetailState.Loading) }
    var retryTick by remember(origin, packId) { mutableIntStateOf(0) }
    var showPicker by remember(origin, packId) { mutableStateOf(false) }

    // Install state is owned by the app-scoped service, not this composition.
    // Match on (origin, packId) so a return to this screen re-attaches to an
    // install started before we navigated away.
    val installs by installService.installs.collectAsState()
    val active = installs.values.firstOrNull { it.origin == origin && it.packId == packId }
    val installing = active?.let { snap ->
        (snap.phase as? InstallPhase.Running)?.let { r ->
            InstallProgress(snap.versionId, r.current, r.total, r.filename)
        }
    }
    val installError = (active?.phase as? InstallPhase.Failed)?.message

    // Success navigates to the installed instance (unchanged behaviour), then
    // evicts the terminal snapshot so a later reinstall starts clean.
    LaunchedEffect(active?.key, active?.phase) {
        val snap = active
        val phase = snap?.phase
        if (snap != null && phase is InstallPhase.Succeeded) {
            installService.dismiss(snap.key)
            onInstalled(phase.instanceId)
        }
    }

    fun install(details: CataloguePackDetails, version: CataloguePackVersion) {
        installService.start(
            pack = CataloguePack(
                origin    = details.origin,
                id        = details.id,
                title     = details.title,
                tagline   = details.tagline,
                iconUrl   = details.iconUrl,
                bannerUrl = details.bannerUrl,
            ),
            version = version,
        )
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

    // The page scrolls, the side column does not. Laid out as siblings rather
    // than as two columns inside one scroll: a side column that scrolls away
    // leaves its width reserved and empty for the rest of the page, which on a
    // long description is most of it -- a dead band down the right of the screen
    // wider than the gap on the left. Standing still, it is never empty and never
    // has to be paid for twice.
    Row(Modifier.fillMaxSize()) {
    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
        val loaded = state as? DetailState.Loaded
        CatalogueHero(
            // Same floated-card rounding as the Library detail hero.
            modifier  = Modifier
                .padding(start = 16.dp, top = 8.dp, end = 16.dp)
                .clip(RoundedCornerShape(LocalStyle.current.cardCorner)),
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
        (state as? DetailState.Loaded)?.let { DetailSidebar(it.details) }
    }

    (state as? DetailState.Loaded)?.let { ld ->
        PuppetClick("catalogue.detail.install", enabled = installing == null && ld.details.versions.isNotEmpty()) {
            val versions = ld.details.versions
            if (versions.size > 1) showPicker = true else versions.firstOrNull()?.let { install(ld.details, it) }
        }
    }

    val pickerTarget = state as? DetailState.Loaded
    if (showPicker && pickerTarget != null) {
        val d = pickerTarget.details
        // The listing arrives newest-first from every source, so the head is the
        // latest build; nothing here is "installed" yet, that flag belongs to the
        // instance host.
        val rows = remember(d.versions) {
            d.versions.mapIndexed { index, v ->
                PickerVersion(
                    id = v.id,
                    label = v.versionNumber,
                    channel = v.channel,
                    publishedAt = v.publishedAt,
                    changelog = v.changelog,
                    runtimeLine = runtimeLineOf(v),
                    latest = index == 0,
                )
            }
        }
        VersionPickerWindow(
            title = s.versionPickerInstallTitle,
            packName = d.title,
            packIconUrl = d.iconUrl,
            versions = rows,
            intentFor = { PickerIntent.Install },
            busyVersionId = installing?.versionId,
            onConfirm = { picked ->
                d.versions.firstOrNull { it.id == picked.id }?.let { install(d, it) }
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** "Minecraft 1.12.2 . Forge", skipping whichever half the source did not declare. */
private fun runtimeLineOf(v: CataloguePackVersion): String? = listOfNotNull(
    v.mcVersions.firstOrNull()?.let { "Minecraft $it" },
    v.loaders.firstOrNull()?.replaceFirstChar(Char::uppercase),
).joinToString("  ").takeIf { it.isNotBlank() }

@Composable
private fun DetailBody(details: CataloguePackDetails, installing: InstallProgress?, installError: String?) {
    // A body link to a video (direct file or a service page) opens in-app; the
    // rest go to the browser as before.
    var videoLink by remember { mutableStateOf<String?>(null) }
    // The description sits on a plane rather than directly on the page. It is a
    // long document over a wallpaper, and without an edge there is nothing saying
    // where the page ends and the text begins.
    NxSurface(
        level    = NxSurfaceLevel.Raised,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
            // No in-page progress block: the activity surface narrates the
            // install, and it survives leaving this page while a block bound to
            // the screen cannot. Two of them on one install was the state the
            // surface was built to end.
        }
    }
    videoLink?.let { url ->
        FullscreenVideo(url = url, onDismiss = { videoLink = null })
    }
}

/**
 * The compatibility and tags column, held still while the description scrolls
 * past it. Scrolls on its own only when it outgrows the window, which the blocks
 * it holds today never do.
 */
@Composable
private fun DetailSidebar(details: CataloguePackDetails) {
    val s = LocalStrings.current
    Column(
        modifier            = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(end = 24.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        val v = details.versions.firstOrNull()
        SidebarBlock(title = s.browseDetailCompatTitle) {
            v?.mcVersions?.firstOrNull()?.let { MetaRow(s.browseDetailCompatMc, it) }
            v?.loaders?.firstOrNull()?.let { MetaRow(s.browseDetailCompatLoader, it.replaceFirstChar { c -> c.uppercase() }) }
            details.runtimeLabel?.let { MetaRow(s.browseDetailCompatJava, it) }
        }
        if (details.tags.isNotEmpty()) {
            SidebarBlock(title = s.browseDetailTagsTitle) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    details.tags.forEach { Chip(it) }
                }
            }
        }
    }
}

@Composable
private fun SidebarBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        // The library's plane, not a hand-rolled one. A bare tinted box has no
        // edge, so on a page whose background is a wallpaper the block had
        // nothing telling the eye where it started -- and it disagreed with every
        // other card in the app, which all carry the bevel hairline.
        NxSurface(level = NxSurfaceLevel.Raised, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) { content() }
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

/** Enough for a label and its value on one line, and no more. */
private val SIDEBAR_WIDTH = 300.dp

private data class InstallProgress(val versionId: String, val current: Int, val total: Int, val filename: String)

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val details: CataloguePackDetails) : DetailState()
    data class Error(val message: String) : DetailState()
}
