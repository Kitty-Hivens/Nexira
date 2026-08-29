package hivens.ui.screens.browse

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
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
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxVerticalScrollbar
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.RetryStateBlock
import hivens.ui.puppet.PuppetClick
import hivens.ui.render.MarkdownHtml
import hivens.ui.render.openInBrowser
import hivens.ui.screens.versions.PickerIntent
import hivens.ui.screens.versions.PickerVersion
import hivens.ui.screens.versions.VersionPickerWindow
import hivens.ui.theme.Dimens
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
 * Tags and what the pack runs on render in the flow of the description. The
 * column that used to hold the latter is gone: it cost a column's width down the
 * whole page to say three things, and a line says them without one.
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
    val session: BrowseSession = koinInject()

    // Back is the top-bar breadcrumb's job now (no hero arrow), but automation
    // still needs a handle on it.
    PuppetClick("catalogue.detail.back") { onBack() }

    // Opens on the page as it was last read, not on a spinner. The details are the
    // same on the way back as they were on the way in, so rebuilding them from
    // nothing meant the page a reader had just closed came back empty and filled in
    // again in front of them.
    var state by remember(origin, packId) {
        mutableStateOf<DetailState>(
            session.details(origin, packId)?.let { DetailState.Loaded(it) } ?: DetailState.Loading,
        )
    }
    var retryTick by remember(origin, packId) { mutableIntStateOf(0) }
    var showPicker by remember(origin, packId) { mutableStateOf(false) }

    // Install state is owned by the app-scoped service, not this composition.
    // Match on (origin, packId) so a return to this screen re-attaches to an
    // install started before we navigated away.
    val installs by installService.installs.collectAsState()
    val active = installs.values.firstOrNull { it.origin == origin && it.packId == packId }
    val installing = active?.let { snap ->
        (snap.phase as? InstallPhase.Running)?.let { InstallProgress(snap.versionId) }
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
        // Only a page with nothing on it says so. A refresh behind a page already
        // read replaces it when it lands, the way the catalogue list does.
        if (state !is DetailState.Loaded) state = DetailState.Loading
        state = try {
            val catalogue = registry.forOrigin(origin)
            if (catalogue == null) {
                DetailState.Error(s.browseDetailErrorMessage)
            } else {
                val details = withContext(Dispatchers.IO) { catalogue.details(packId) }
                session.putDetails(origin, packId, details)
                DetailState.Loaded(details)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A source that failed while a page of its own is on screen keeps
            // showing it: an error page loses more than the error explains.
            (state as? DetailState.Loaded) ?: DetailState.Error(e.message ?: s.browseDetailErrorMessage)
        }
    }

    // A bar to take hold of. A page this long is otherwise reachable only by the
    // wheel: there is nothing to drag, and nothing showing how far down it goes.
    //
    // Revealed while scrolling, and by the cursor reaching the edge it lives on
    // -- not by the cursor being anywhere on the page. The other lists in the app
    // put this on a pane with something beside it, so leaving the pane hides the
    // bar; here the pane is the whole window, and hover over all of it means the
    // bar never idles away at all.
    val scroll = rememberScrollState()
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
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
            is DetailState.Loaded -> DetailBody(details = st.details, installError = installError)
        }
    }
        Box(
            modifier         = Modifier
                .align(Alignment.CenterEnd)
                .width(SCROLLBAR_GUTTER)
                .fillMaxHeight()
                .hoverable(hover),
            contentAlignment = Alignment.CenterEnd,
        ) {
            NxVerticalScrollbar(
                adapter  = rememberScrollbarAdapter(scroll),
                revealed = hovered || scroll.isScrollInProgress,
                modifier = Modifier.fillMaxHeight().padding(vertical = 4.dp),
            )
        }
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

/** "Minecraft 1.12.2  Forge", skipping whichever half the source did not declare. */
private fun runtimeLineOf(v: CataloguePackVersion): String? = listOfNotNull(
    v.mcVersions.firstOrNull()?.let { "Minecraft $it" },
    v.loaders.firstOrNull()?.replaceFirstChar(Char::uppercase),
).joinToString("  ").takeIf { it.isNotBlank() }

@Composable
private fun DetailBody(details: CataloguePackDetails, installError: String?) {
    val s = LocalStrings.current
    // A body link to a video (direct file or a service page) opens in-app; the
    // rest go to the browser as before.
    var videoLink by remember { mutableStateOf<String?>(null) }
    val hasGallery = details.gallery.isNotEmpty()
    var tab by remember(details.origin, details.id) { mutableStateOf(DetailTab.Description) }
    // The description sits on a plane rather than directly on the page. It is a
    // long document over a wallpaper, and without an edge there is nothing saying
    // where the page ends and the text begins.
    // Centred under the same ceiling the other content screens use. The side
    // column that was removed had been the only thing holding the description to
    // a readable measure, and without it a line of prose ran the full width of a
    // wide monitor -- past the point where the eye can find the start of the next
    // one. The ceiling goes before the fill: fillMaxWidth first would pin the
    // minimum to the full width, and a ceiling cannot take the maximum below it.
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    NxSurface(
        level    = NxSurfaceLevel.Raised,
        modifier = Modifier
            .widthIn(max = Dimens.contentMaxWidth)
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val facts = remember(details) { compatFacts(details) }
            if (facts.isNotEmpty()) {
                // What the pack runs on, in the flow of the page. It used to be a
                // column of label-and-value rows beside the description, which
                // reserved a column's width down the whole page to say three
                // things and pushed the reading of it into a narrower measure than
                // it deserved. Three facts are a line, not a panel.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement   = Arrangement.spacedBy(6.dp),
                ) { facts.forEach { NxMetaChip(it, tone = NxMetaChipTone.Surface) } }
            }
            if (details.tags.isNotEmpty()) {
                // In the flow of the page rather than in a column of their own. The
                // side column this used to share was carrying one short block down
                // the height of a long description, and reserving that width did
                // more damage to the reading of the page than the block was worth.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement   = Arrangement.spacedBy(6.dp),
                ) { details.tags.forEach { Chip(it) } }
            }
            // The gallery is a place of its own, not a strip at the head of the
            // description. Screenshots and prose want opposite widths, and a grid
            // of them above the text pushes the text off the first screen of a
            // page whose text is the point. The tab is offered only when there
            // are shots -- a lone tab is not a choice, it is a label.
            if (hasGallery) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NxChoiceChip(s.browseDetailTabDescription, selected = tab == DetailTab.Description) {
                        tab = DetailTab.Description
                    }
                    NxChoiceChip(s.browseDetailTabGallery, selected = tab == DetailTab.Gallery) {
                        tab = DetailTab.Gallery
                    }
                }
                PuppetClick("catalogue.detail.tab.description") { tab = DetailTab.Description }
                PuppetClick("catalogue.detail.tab.gallery") { tab = DetailTab.Gallery }
            }
            when {
                hasGallery && tab == DetailTab.Gallery -> ImageGallery(media = remember(details) { galleryMedia(details.gallery) })
                else -> details.bodyMarkdown?.let {
                    MarkdownHtml(
                        markdown = it,
                        modifier = Modifier.fillMaxWidth(),
                        onLink   = { url -> if (isPlayableVideoUrl(url)) videoLink = url else openInBrowser(url) },
                    )
                }
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
    }
    videoLink?.let { url ->
        FullscreenVideo(url = url, onDismiss = { videoLink = null })
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
            disabledContainerColor = NxTheme.colors.surface.copy(alpha = 0.4f),
            disabledLabelColor     = NxTheme.colors.textPrimary,
        ),
        border  = null,
    )
}

/**
 * What the pack runs on, as short phrases, in the order a person asks for them:
 * the game first, then what loads the mods into it, then the runtime under both.
 *
 * Read off the newest version rather than off the pack, because a pack does not
 * have a Minecraft version -- its builds do, and the newest is the one the
 * install button reaches for. A source silent on any of the three contributes
 * nothing rather than a placeholder: "Loader: unknown" is worse than a line that
 * does not mention loaders.
 */
internal fun compatFacts(details: CataloguePackDetails): List<String> {
    val newest = details.versions.firstOrNull()
    return listOfNotNull(
        newest?.mcVersions?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "Minecraft $it" },
        newest?.loaders?.firstOrNull()?.takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercase),
        details.runtimeLabel?.takeIf { it.isNotBlank() },
    )
}

/** How near the edge the cursor has to come to call the scrollbar up. */
private val SCROLLBAR_GUTTER = 28.dp

/** The two halves of a pack page: what it says about itself, and what it looks like. */
private enum class DetailTab { Description, Gallery }

/**
 * Which version is being installed. Only the identity is read: the activity
 * surface narrates the progress, and the counters this used to carry were being
 * rebuilt every frame for a block that no longer exists.
 */
private data class InstallProgress(val versionId: String)

private sealed class DetailState {
    object Loading : DetailState()
    data class Loaded(val details: CataloguePackDetails) : DetailState()
    data class Error(val message: String) : DetailState()
}
