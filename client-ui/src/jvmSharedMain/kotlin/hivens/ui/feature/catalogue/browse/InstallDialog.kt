package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.launcher.InstallPhase
import hivens.launcher.PackInstallService
import hivens.launcher.imports.LocalPackCreator
import hivens.launcher.instance.ContentDestination
import hivens.launcher.instance.InstallTargets
import hivens.launcher.instance.ModInstaller
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.modrinth.chooseBuild
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.navigation.NavRequests
import hivens.ui.nx.InitialsAvatar
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxChoiceFooterItem
import hivens.ui.nx.NxField
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxSelect
import hivens.ui.nx.NxTooltip
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.theme.decorativeColor
import hivens.ui.theme.decorativePair
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID

private val log = LoggerFactory.getLogger("InstallDialog")

/** What an install is for, as the dialog names it. */
internal data class InstallSubject(
    val projectId: String,
    val title: String,
    val iconUrl: String? = null,
    val author: String = "",
)

/**
 * Where a catalogue project goes, asked when an install has no pack behind it.
 *
 * A dialog and not a menu, after the catalogue's own app: the choice is between
 * every pack the player has, each with its own answer, and a pack that does not
 * exist yet. A dropdown hung off a card holds neither. The project stays named at
 * the top, so the reader knows what they are placing while they look for where.
 *
 * Every install runs on the app's scope. Closing the dialog, or leaving the screen,
 * leaves an install that has started to finish.
 */
@Composable
internal fun InstallDialog(subject: InstallSubject, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scrim by animateFloatAsState(if (shown) 0.72f else 0f, animationSpec = Motion.fade, label = "installScrim")
    val model = rememberInstallDialogModel(subject)
    var tab by remember { mutableStateOf(InstallTab.Existing) }
    val tabs = if (model.newPackLoaders.isNotEmpty()) InstallTab.entries else listOf(InstallTab.Existing)

    PuppetClick("installDialog.close") { onDismiss() }
    PuppetClick("installDialog.tab.new") { tab = InstallTab.New }
    PuppetClick("installDialog.tab.existing") { tab = InstallTab.Existing }

    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier.fillMaxSize().background(NxColor.page.copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = shown, enter = Motion.emphasis.enter, exit = Motion.emphasis.exit) {
                NxSurface(
                    kind = SurfaceKind.Dialog,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.widthIn(max = DIALOG_WIDTH).fillMaxWidth(0.92f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                s.installDialogTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = NxInk.main,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            NxIconButton(icon = NxIcon.Close, contentDescription = s.installDialogClose, onClick = onDismiss)
                        }
                        SubjectBlock(subject)
                        if (tabs.size > 1) {
                            Row(
                                Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                tabs.forEach { t -> NxChoiceChip(label = t.label(s), selected = tab == t) { tab = t } }
                            }
                        } else {
                            Spacer(Modifier.height(14.dp))
                        }
                        HorizontalDivider(color = NxInk.line)
                        val fade = Motion.fade
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { fade.enter togetherWith fade.exit },
                            modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT).background(NxColor.wash(NxInk.quiet, 0.04f)),
                            label = "installTab",
                        ) { t ->
                            when (t) {
                                InstallTab.Existing -> ExistingPacks(model)
                                InstallTab.New -> NewPack(model)
                            }
                        }
                        HorizontalDivider(color = NxInk.line)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Symbol(NxIcon.Inventory2, contentDescription = null, tint = NxInk.quiet, size = 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                model.candidates?.let { list -> s.installDialogFitCount(list.count { it.verdict is InstallTargets.Verdict.Fits }) }.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = NxInk.quiet,
                                modifier = Modifier.weight(1f),
                            )
                            NxButton(label = s.installDialogClose, onClick = onDismiss, style = NxButtonStyle.Secondary, icon = NxIcon.Close, compact = true)
                        }
                    }
                }
            }
        }
    }
}

private enum class InstallTab { Existing, New }

private fun InstallTab.label(s: AppStrings): String = when (this) {
    InstallTab.Existing -> s.installDialogExisting
    InstallTab.New -> s.installDialogNew
}

private val DIALOG_WIDTH = 560.dp
private val BODY_HEIGHT = 400.dp

@Composable
private fun SubjectBlock(subject: InstallSubject) {
    val s = LocalStrings.current
    Row(
        Modifier.padding(horizontal = 24.dp).padding(top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(NxColor.wash(NxInk.quiet, 0.08f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val shape = MaterialTheme.shapes.medium
        if (subject.iconUrl != null) {
            AsyncImage(model = subject.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(shape))
        } else {
            Box(Modifier.size(56.dp).clip(shape).background(decorativeColor(subject.title)), contentAlignment = Alignment.Center) {
                Text(subject.title.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(subject.title, style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subject.author.isNotBlank()) {
                Text(s.browseByAuthor(subject.author), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet, maxLines = 1)
            }
        }
    }
}

// ── The model ───────────────────────────────────────────────────────────────

/** Where one pack's install stands inside the dialog. */
private sealed interface RowState {
    data object Idle : RowState
    data object Working : RowState
    /** Landed, with [missing] required dependencies it went without. */
    data class Done(val missing: Int = 0) : RowState
    data class Failed(val problem: InstallProblem) : RowState
}

/** Where making a new pack stands. */
private sealed interface NewPackState {
    data object Idle : NewPackState
    data class Creating(val key: String) : NewPackState
    data object Installing : NewPackState
    data class Done(val pack: PackInstance, val missing: Int = 0) : NewPackState
    data class Failed(val message: String) : NewPackState
}

private class InstallDialogModel(
    val candidates: List<InstallTargets.Candidate>?,
    val failed: Boolean,
    val listing: List<ModrinthVersion>,
    val newPackLoaders: List<String?>,
    val rows: Map<String, RowState>,
    val newPack: NewPackState,
    val install: (InstallTargets.Candidate) -> Unit,
    val retry: () -> Unit,
    val createAndInstall: (name: String, mc: String, loader: String?) -> Unit,
    val openPack: (String) -> Unit,
)

@Composable
private fun rememberInstallDialogModel(subject: InstallSubject): InstallDialogModel {
    val modrinth: ModrinthClient = koinInject()
    val targets: InstallTargets = koinInject()
    val installer: ModInstaller = koinInject()
    val installService: PackInstallService = koinInject()
    val creator: LocalPackCreator = koinInject()
    val repo: IPackRepository = koinInject()
    val dataDir: Path = koinInject()
    val nav: NavRequests = koinInject()
    // Installs run on the app's scope: closing the dialog must not stop one halfway.
    val appScope: CoroutineScope = koinInject()

    var attempt by remember { mutableStateOf(0) }
    var candidates by remember { mutableStateOf<List<InstallTargets.Candidate>?>(null) }
    var listing by remember { mutableStateOf(emptyList<ModrinthVersion>()) }
    var failed by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf(emptyMap<String, RowState>()) }
    var newPack by remember { mutableStateOf<NewPackState>(NewPackState.Idle) }

    LaunchedEffect(subject.projectId, attempt) {
        failed = false
        candidates = null
        try {
            val builds = withContext(Dispatchers.IO) { modrinth.listVersions(subject.projectId) }
            listing = builds
            candidates = targets.forProject(subject.projectId, builds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("reading where {} could go failed", subject.projectId, e)
            failed = true
            candidates = emptyList()
        }
    }

    fun install(candidate: InstallTargets.Candidate) {
        val build = (candidate.verdict as? InstallTargets.Verdict.Fits)?.build ?: return
        val id = candidate.pack.id
        rows = rows + (id to RowState.Working)
        appScope.launch(Dispatchers.Main) {
            val target = candidate.destination
            val state = try {
                val outcome = withContext(Dispatchers.IO) { installer.install(target.dir, build, target.mcVersion, target.loader) }
                problemOf(outcome)?.let { RowState.Failed(it) } ?: RowState.Done(outcome.missing.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("installing {} into {} failed", subject.projectId, target.dir, e)
                RowState.Failed(InstallProblem.NotLanded)
            }
            rows = rows + (id to state)
        }
    }

    fun createAndInstall(name: String, mc: String, loader: String?) {
        val key = "create:${UUID.randomUUID()}"
        newPack = NewPackState.Creating(key)
        val builds = listing
        appScope.launch(Dispatchers.Main) {
            try {
                installService.run(key = key, title = name) { reserve, progress ->
                    creator.create(name, mc, loader, "", reserve, progress)
                }
                val phase = installService.installs.first { all -> all[key]?.phase.let { it != null && it !is InstallPhase.Running } }[key]?.phase
                if (phase !is InstallPhase.Succeeded) {
                    newPack = NewPackState.Failed((phase as? InstallPhase.Failed)?.message.orEmpty())
                    return@launch
                }
                installService.dismiss(key)
                newPack = NewPackState.Installing
                val pack = withContext(Dispatchers.IO) { repo.get(phase.instanceId) }
                    ?: return@launch run { newPack = NewPackState.Failed("") }
                val target = ContentDestination.Pack.of(pack, dataDir.resolve("instances"))
                val build = chooseBuild(builds, target.mcVersion, target.loader)
                    ?: return@launch run { newPack = NewPackState.Failed("") }
                val outcome = withContext(Dispatchers.IO) { installer.install(target.dir, build, target.mcVersion, target.loader) }
                newPack = if (outcome.ok) NewPackState.Done(pack, outcome.missing.size) else NewPackState.Failed("")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("making a pack for {} failed", subject.projectId, e)
                newPack = NewPackState.Failed(e.message.orEmpty())
            }
        }
    }

    return InstallDialogModel(
        candidates = candidates,
        failed = failed,
        listing = listing,
        newPackLoaders = newPackLoaders(listing),
        rows = rows,
        newPack = newPack,
        install = ::install,
        retry = { attempt++ },
        createAndInstall = ::createAndInstall,
        openPack = { id -> nav.open(Screen.PackDetail(id)) },
    )
}

/**
 * The loaders a pack made for this project could run, the ones the launcher can
 * make a pack with. Null stands for a pack with no loader, which is where a
 * resource pack goes. Empty where no pack the launcher can make would take the
 * project, which keeps the new-pack tab off: a shader needs a shader loader the
 * new pack would not have.
 */
private fun newPackLoaders(listing: List<ModrinthVersion>): List<String?> {
    val published = listing.flatMapTo(LinkedHashSet()) { it.loaders }
    return buildList {
        if ("minecraft" in published) add(null)
        CREATABLE_LOADERS.filter { it in published }.forEach { add(it) }
    }
}

/** Loaders a new pack can be made with here and that publish mods for themselves. */
private val CREATABLE_LOADERS = listOf("fabric", "neoforge", "forge", "quilt")

/** Game versions the project has builds for under [loader], newest first, releases unless [all]. */
private fun versionsFor(listing: List<ModrinthVersion>, loader: String?, all: Boolean): List<String> {
    val wanted = loader ?: "minecraft"
    val release = Regex("""^\d+\.\d+(\.\d+)?$""")
    return listing.asSequence()
        .filter { wanted in it.loaders }
        .flatMap { it.gameVersions.asSequence() }
        .distinct()
        .filter { all || release.matches(it) }
        .sortedWith { a, b -> compareVersions(b, a) }
        .toList()
}

private fun compareVersions(a: String, b: String): Int {
    val x = a.split('.', '-').map { it.toIntOrNull() ?: -1 }
    val y = b.split('.', '-').map { it.toIntOrNull() ?: -1 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return a.compareTo(b)
}

// ── Existing packs ──────────────────────────────────────────────────────────

@Composable
private fun ExistingPacks(model: InstallDialogModel) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    var hideUnfit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxField(value = query, onValueChange = { query = it }, placeholder = s.installDialogSearch, modifier = Modifier.weight(1f))
            NxTooltip(text = if (hideUnfit) s.installDialogShowUnfit else s.installDialogHideUnfit) {
                NxIconButton(
                    icon = if (hideUnfit) NxIcon.VisibilityOff else NxIcon.Visibility,
                    contentDescription = if (hideUnfit) s.installDialogShowUnfit else s.installDialogHideUnfit,
                    onClick = { hideUnfit = !hideUnfit },
                )
            }
        }
        PuppetClick("installDialog.hideUnfit") { hideUnfit = !hideUnfit }
        Spacer(Modifier.height(10.dp))
        val list = model.candidates
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            model.failed -> Note(s.installPickerFailed, retry = model.retry)
            else -> {
                val shown = list
                    .filter { query.isBlank() || it.pack.displayName.contains(query.trim(), ignoreCase = true) }
                    .filter { !hideUnfit || it.verdict is InstallTargets.Verdict.Fits || model.rows[it.pack.id] != null }
                if (shown.isEmpty()) {
                    Note(if (list.isEmpty()) s.installPickerNoPacks else s.contentEmpty)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.pack.id }) { candidate ->
                            CandidateRow(
                                candidate = candidate,
                                state = model.rows[candidate.pack.id] ?: RowState.Idle,
                                onInstall = { model.install(candidate) },
                                onOpen = { model.openPack(candidate.pack.id) },
                                modifier = Modifier.animateItem(fadeInSpec = Motion.fade, placementSpec = Motion.reveal.of<IntOffset>(), fadeOutSpec = Motion.fade),
                            )
                            PuppetClick("installDialog.install.${candidate.pack.id}") { model.install(candidate) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: InstallTargets.Candidate,
    state: RowState,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val verdict = candidate.verdict
    val fits = verdict is InstallTargets.Verdict.Fits
    val present = verdict is InstallTargets.Verdict.Present || state is RowState.Done
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val wash by animateColorAsState(
        if (hovered && fits && !present) NxColor.wash(NxInk.quiet, 0.08f) else Color.Transparent,
        animationSpec = Motion.tap.of(),
        label = "candidateHover",
    )
    val dim = !fits && !present
    Row(
        modifier.fillMaxWidth().hoverable(interaction).background(wash).padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PackMark(candidate.pack, 32.dp, dimmed = dim)
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.pack.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dim) NxInk.quiet else NxInk.main,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val missing = (state as? RowState.Done)?.missing?.takeIf { it > 0 }
                val note = (state as? RowState.Failed)?.problem?.label(s)
                    ?: missing?.let { s.modPageInstallMissing(it) }
                    ?: unfitReason(verdict, s)
                Text(
                    note ?: runtimeLabel(candidate.destination),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        state is RowState.Failed || missing != null -> NxColor.status(Status.Error, text = true)
                        else -> NxInk.quiet
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val tap = Motion.tap
        // One control whose phase moves, so the change animates where the eye already is.
        val phase = when {
            present -> CardPhase.Installed
            state == RowState.Working -> CardPhase.Working
            state is RowState.Failed -> CardPhase.Failed
            fits -> CardPhase.Offered
            else -> null
        }
        if (phase != null) {
            CardInstall(phase, onInstall)
        } else {
            Symbol(NxIcon.Block, contentDescription = null, tint = NxInk.off, size = 18.dp)
        }
    }
}

private fun unfitReason(verdict: InstallTargets.Verdict, s: AppStrings): String? = when (verdict) {
    is InstallTargets.Verdict.NotFit -> when (verdict.reason) {
        InstallTargets.Unfit.NoBuild -> s.installTargetNoBuild
        InstallTargets.Unfit.NotTaken -> s.installTargetNotTaken
    }
    InstallTargets.Verdict.Unknown -> s.installTargetUnknown
    else -> null
}

@Composable
private fun Note(text: String, retry: (() -> Unit)? = null) {
    val s = LocalStrings.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
        retry?.let { NxButton(label = s.browseRetry, onClick = it, style = NxButtonStyle.Secondary, icon = NxIcon.Refresh, compact = true) }
    }
}

// ── A new pack ──────────────────────────────────────────────────────────────

@Composable
private fun NewPack(model: InstallDialogModel) {
    val s = LocalStrings.current
    val loaders = model.newPackLoaders
    var loader by remember(loaders) { mutableStateOf(loaders.firstOrNull { it != null } ?: loaders.firstOrNull()) }
    var showAll by remember { mutableStateOf(false) }
    val versions = remember(model.listing, loader, showAll) { versionsFor(model.listing, loader, showAll) }
    // The pick survives the list changing under it: showing snapshots used to throw
    // away a chosen 1.20.1 for the newest snapshot, with the list still open.
    var mc by remember(loader) { mutableStateOf(versions.firstOrNull().orEmpty()) }
    LaunchedEffect(versions) { if (mc !in versions) mc = versions.firstOrNull().orEmpty() }
    var name by remember { mutableStateOf("") }
    val defaultName = listOf(loader?.let(::loaderLabel) ?: "Vanilla", mc).filter { it.isNotBlank() }.joinToString(" ")

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (val state = model.newPack) {
            NewPackState.Idle, is NewPackState.Failed -> {
                Label(s.createPackName)
                NxField(value = name, onValueChange = { name = it }, placeholder = defaultName, modifier = Modifier.fillMaxWidth())
                Label(s.createPackLoader)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    loaders.forEach { l -> NxChoiceChip(label = l?.let(::loaderLabel) ?: "Vanilla", selected = l == loader) { loader = l } }
                }
                Label(s.createPackMc)
                NxSelect(
                    options     = versions,
                    selected    = mc.takeIf { it.isNotBlank() },
                    onSelect    = { mc = it },
                    label       = { it },
                    placeholder = "—",
                    maxHeight   = 240.dp,
                    modifier    = Modifier.fillMaxWidth(),
                    footer      = {
                        NxChoiceFooterItem(
                            label = if (showAll) s.createPackHideSnapshots else s.createPackShowSnapshots,
                            icon  = if (showAll) NxIcon.VisibilityOff else NxIcon.Visibility,
                        ) { showAll = !showAll }
                    },
                )
                Spacer(Modifier.weight(1f))
                if (state is NewPackState.Failed) {
                    Text(
                        state.message.ifBlank { s.contentInstallFailed },
                        style = MaterialTheme.typography.labelMedium,
                        color = NxColor.status(Status.Error, text = true),
                        maxLines = 2,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NxButton(
                        label = s.installDialogCreate,
                        onClick = { model.createAndInstall(name.trim().ifBlank { defaultName }, mc, loader) },
                        icon = NxIcon.Download,
                        enabled = mc.isNotBlank(),
                        compact = true,
                    )
                }
                PuppetClick("installDialog.create", enabled = mc.isNotBlank()) {
                    model.createAndInstall(name.trim().ifBlank { defaultName }, mc, loader)
                }
            }
            is NewPackState.Creating, NewPackState.Installing -> CreatingNote(state)
            is NewPackState.Done -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Symbol(NxIcon.CheckCircle, contentDescription = null, tint = NxColor.lead(), size = 40.dp)
                Text(s.installDialogDone(state.pack.displayName), style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                if (state.missing > 0) {
                    Text(s.modPageInstallMissing(state.missing), style = MaterialTheme.typography.labelMedium, color = NxColor.status(Status.Error, text = true))
                }
                NxButton(label = s.installDialogOpenPack, onClick = { model.openPack(state.pack.id) }, style = NxButtonStyle.Secondary, compact = true)
            }
        }
    }
}

@Composable
private fun CreatingNote(state: NewPackState) {
    val s = LocalStrings.current
    val installService: PackInstallService = koinInject()
    val installs by installService.installs.collectAsState()
    val phase = (state as? NewPackState.Creating)?.let { installs[it.key]?.phase as? InstallPhase.Running }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Text(
            if (state is NewPackState.Creating) s.installDialogCreating else s.modPageInstalling,
            style = MaterialTheme.typography.titleSmall,
            color = NxInk.main,
        )
        phase?.takeIf { it.total > 0 }?.let {
            Text("${it.current} / ${it.total}", style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = NxInk.main, fontWeight = FontWeight.SemiBold)
}

/** A pack's own mark, its icon or its initials over its hue. */
@Composable
internal fun PackMark(pack: PackInstance, size: Dp, dimmed: Boolean = false) {
    val art = rememberPackArt(pack)
    val hue = decorativePair(pack.id).first
    val alpha = if (dimmed) 0.5f else 1f
    SubcomposeAsyncImage(
        model              = art.iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        alpha              = alpha,
        modifier           = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
        loading            = { Box(Modifier.fillMaxSize().background(hue.copy(alpha = alpha))) },
        error              = { InitialsAvatar(pack.displayName, hue.copy(alpha = alpha)) },
    )
}

/**
 * The install for a project page with no pack behind it: the same button a card
 * carries, and the same dialog behind it.
 */
@Composable
internal fun InstallChooser(subject: InstallSubject) {
    val s = LocalStrings.current
    var open by remember { mutableStateOf(false) }
    NxButton(label = s.modPageInstallChoose, onClick = { open = true }, icon = NxIcon.Download)
    PuppetClick("modPage.installChoose") { open = true }
    if (open) InstallDialog(subject) { open = false }
}
