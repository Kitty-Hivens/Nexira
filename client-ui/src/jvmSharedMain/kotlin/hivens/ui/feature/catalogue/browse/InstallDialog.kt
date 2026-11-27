package hivens.ui.feature.catalogue.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import hivens.catalogue.api.CandidateState
import hivens.catalogue.api.CandidateVerdict
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.InstallCandidate
import hivens.catalogue.api.InstallChoiceView
import hivens.catalogue.api.InstallSubject
import hivens.catalogue.api.NewPackRequest
import hivens.catalogue.api.NewPackState
import hivens.catalogue.api.ProjectBuild
import hivens.i18n.api.Strings
import hivens.packs.api.PackKeys
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.navigation.LauncherScreens
import hivens.ui.nx.InitialsAvatar
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxChoiceFooterItem
import hivens.ui.nx.NxDialog
import hivens.ui.nx.NxField
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxSelect
import hivens.ui.nx.NxTooltip
import hivens.ui.press.clickable
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.library.rememberPackArt
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.ui.theme.decorativeColor
import hivens.ui.theme.decorativePair
import hivens.pages.api.PagesKeys

/**
 * Where a catalogue project goes, asked when an install has no pack behind it.
 *
 * A dialog and not a menu, after the catalogue's own app: the choice is between
 * every pack the player has, each with its own answer, and a pack that does not
 * exist yet. A dropdown hung off a card holds neither. The project stays named at
 * the top, so the reader knows what they are placing while they look for where.
 *
 * The choice is the window's install that asks where, see [CatalogueKeys.InstallChoice]:
 * opened when the dialog comes up and closed when it goes. Every install runs on the
 * app's scope, so closing the dialog, or leaving the screen, leaves an install that has
 * started to finish.
 */
@Composable
internal fun InstallDialog(subject: InstallSubject, onDismiss: () -> Unit) {
    val s = nexiraStrings()
    val model = rememberInstallDialogModel(subject)
    var tab by remember { mutableStateOf(InstallTab.Existing) }
    val tabs = if (model.newPackLoaders.isNotEmpty()) InstallTab.entries else listOf(InstallTab.Existing)

    PuppetClick("installDialog.close") { onDismiss() }
    PuppetClick("installDialog.tab.new") { tab = InstallTab.New }
    PuppetClick("installDialog.tab.existing") { tab = InstallTab.Existing }

    NxDialog(
        onDismissRequest = onDismiss,
        defaultWidth     = false,
        scrim            = NxColor.page.copy(alpha = 0.72f),
        enter            = Motion.emphasis.enter,
        paneTitle        = s.text("installDialogTitle"),
    ) {
        NxSurface(
            kind = SurfaceKind.Dialog,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = DIALOG_WIDTH).fillMaxWidth(0.92f),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.text("installDialogTitle"),
                        style = MaterialTheme.typography.titleLarge,
                        color = NxInk.main,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    NxIconButton(icon = NxIcon.Close, contentDescription = s.text("installDialogClose"), onClick = onDismiss)
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
                        model.candidates?.let { list -> s.text("installDialogFitCount", list.count { it.verdict == CandidateVerdict.Fits }) }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NxInk.quiet,
                        modifier = Modifier.weight(1f),
                    )
                    NxButton(label = s.text("installDialogClose"), onClick = onDismiss, style = NxButtonStyle.Secondary, icon = NxIcon.Close, compact = true)
                }
            }
        }
    }
}

private enum class InstallTab { Existing, New }

private fun InstallTab.label(s: Strings): String = when (this) {
    InstallTab.Existing -> s.text("installDialogExisting")
    InstallTab.New -> s.text("installDialogNew")
}

private val DIALOG_WIDTH = 560.dp
private val BODY_HEIGHT = 400.dp

@Composable
private fun SubjectBlock(subject: InstallSubject) {
    val s = nexiraStrings()
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
                Text(s.text("browseByAuthor", subject.author), style = MaterialTheme.typography.bodySmall, color = NxInk.quiet, maxLines = 1)
            }
        }
    }
}

// ── The model ───────────────────────────────────────────────────────────────

private class InstallDialogModel(
    val candidates: List<InstallCandidate>?,
    val failed: Boolean,
    /** The source the project was found in cannot install it yet. */
    val unsupported: Boolean,
    val listing: List<ProjectBuild>,
    val newPackLoaders: List<String?>,
    val rows: Map<String, CandidateState>,
    val newPack: NewPackState,
    val install: (InstallCandidate) -> Unit,
    val retry: () -> Unit,
    val createAndInstall: (name: String, mc: String, loader: String?) -> Unit,
    val openPack: (String) -> Unit,
)

/**
 * The window's choice for [subject], opened as the dialog comes up and closed as it
 * goes. Until the choice for this subject is in, the dialog reads as still reading.
 */
@Composable
private fun rememberInstallDialogModel(subject: InstallSubject): InstallDialogModel {
    val choose = rememberScopedCommand(CatalogueKeys.ChooseInstall)
    val dismiss = rememberScopedAction(CatalogueKeys.DismissInstall)
    val into = rememberScopedCommand(CatalogueKeys.InstallInto)
    val newPack = rememberScopedCommand(CatalogueKeys.InstallNewPack)
    val retry = rememberScopedAction(CatalogueKeys.RetryChoice)
    // Resolved here, where the dialog is asked for, rather than on the layer it is drawn on.
    val show = rememberScopedCommand(PagesKeys.Show)
    val choice: InstallChoiceView? = rememberScopedSource(CatalogueKeys.InstallChoice).valueOrNull()?.takeIf { it.subject == subject }

    LaunchedEffect(subject, choose != null) { choose?.invoke(subject) }
    DisposableEffect(subject) { onDispose { dismiss?.invoke() } }

    val listing = choice?.listing.orEmpty()
    return InstallDialogModel(
        candidates = choice?.candidates,
        failed = choice?.failed == true,
        unsupported = choice?.unsupported == true,
        listing = listing,
        newPackLoaders = newPackLoaders(listing),
        rows = choice?.rows.orEmpty(),
        newPack = choice?.newPack ?: NewPackState.Idle,
        install = { candidate -> into?.invoke(candidate.packId) },
        retry = { retry?.invoke() },
        createAndInstall = { name, mc, loader -> newPack?.invoke(NewPackRequest(name, mc, loader)) },
        openPack = { id -> show?.invoke(LauncherScreens.pack(id)) },
    )
}

/**
 * The loaders a pack made for this project could run, the ones the launcher can
 * make a pack with. Null stands for a pack with no loader, which is where a
 * resource pack goes. Empty where no pack the launcher can make would take the
 * project, which keeps the new-pack tab off: a shader needs a shader loader the
 * new pack would not have.
 */
private fun newPackLoaders(listing: List<ProjectBuild>): List<String?> {
    val published = listing.flatMapTo(LinkedHashSet()) { it.loaders }
    return buildList {
        if ("minecraft" in published) add(null)
        CREATABLE_LOADERS.filter { it in published }.forEach { add(it) }
    }
}

/** Loaders a new pack can be made with here and that publish mods for themselves. */
private val CREATABLE_LOADERS = listOf("fabric", "neoforge", "forge", "quilt")

/** Game versions the project has builds for under [loader], newest first, releases unless [all]. */
private fun versionsFor(listing: List<ProjectBuild>, loader: String?, all: Boolean): List<String> {
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
    val s = nexiraStrings()
    var query by remember { mutableStateOf("") }
    var hideUnfit by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxField(value = query, onValueChange = { query = it }, placeholder = s.text("installDialogSearch"), modifier = Modifier.weight(1f))
            NxTooltip(text = if (hideUnfit) s.text("installDialogShowUnfit") else s.text("installDialogHideUnfit")) {
                NxIconButton(
                    icon = if (hideUnfit) NxIcon.VisibilityOff else NxIcon.Visibility,
                    contentDescription = if (hideUnfit) s.text("installDialogShowUnfit") else s.text("installDialogHideUnfit"),
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
            model.unsupported -> Note(s.text("installSourceCannotInstall"))
            model.failed -> Note(s.text("installPickerFailed"), retry = model.retry)
            else -> {
                val shown = list
                    .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                    .filter { !hideUnfit || it.verdict == CandidateVerdict.Fits || model.rows[it.packId] != null }
                if (shown.isEmpty()) {
                    Note(if (list.isEmpty()) s.text("installPickerNoPacks") else s.text("contentEmpty"))
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(shown, key = { it.packId }) { candidate ->
                            CandidateRow(
                                candidate = candidate,
                                state = model.rows[candidate.packId],
                                onInstall = { model.install(candidate) },
                                onOpen = { model.openPack(candidate.packId) },
                                modifier = Modifier.animateItem(fadeInSpec = Motion.fade, placementSpec = Motion.reveal.of<IntOffset>(), fadeOutSpec = Motion.fade),
                            )
                            PuppetClick("installDialog.install.${candidate.packId}") { model.install(candidate) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: InstallCandidate,
    /** What came of an install into it, null where none was started. */
    state: CandidateState?,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    val verdict = candidate.verdict
    val fits = verdict == CandidateVerdict.Fits
    val present = verdict == CandidateVerdict.Present || state is CandidateState.Done
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
            PackMark(candidate.packId, candidate.name, 32.dp, dimmed = dim)
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dim) NxInk.quiet else NxInk.main,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val missing = (state as? CandidateState.Done)?.missing?.takeIf { it > 0 }
                val note = (state as? CandidateState.Failed)?.problem?.label(s)
                    ?: missing?.let { s.text("modPageInstallMissing", it) }
                    ?: unfitReason(verdict, s)
                Text(
                    note ?: runtimeLabel(candidate.mcVersion, candidate.loader),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        state is CandidateState.Failed || missing != null -> NxColor.status(Status.Error)
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
            state == CandidateState.Working -> CardPhase.Working
            state is CandidateState.Failed -> CardPhase.Failed
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

private fun unfitReason(verdict: CandidateVerdict, s: Strings): String? = when (verdict) {
    CandidateVerdict.NoBuild -> s.text("installTargetNoBuild")
    CandidateVerdict.NotTaken -> s.text("installTargetNotTaken")
    CandidateVerdict.Unknown -> s.text("installTargetUnknown")
    CandidateVerdict.Fits, CandidateVerdict.Present -> null
}

@Composable
private fun Note(text: String, retry: (() -> Unit)? = null) {
    val s = nexiraStrings()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
        retry?.let { NxButton(label = s.text("browseRetry"), onClick = it, style = NxButtonStyle.Secondary, icon = NxIcon.Refresh, compact = true) }
    }
}

// ── A new pack ──────────────────────────────────────────────────────────────

@Composable
private fun NewPack(model: InstallDialogModel) {
    val s = nexiraStrings()
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
                Label(s.text("createPackName"))
                NxField(value = name, onValueChange = { name = it }, placeholder = defaultName, modifier = Modifier.fillMaxWidth())
                Label(s.text("createPackLoader"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    loaders.forEach { l -> NxChoiceChip(label = l?.let(::loaderLabel) ?: "Vanilla", selected = l == loader) { loader = l } }
                }
                Label(s.text("createPackMc"))
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
                            label = if (showAll) s.text("createPackHideSnapshots") else s.text("createPackShowSnapshots"),
                            icon  = if (showAll) NxIcon.VisibilityOff else NxIcon.Visibility,
                        ) { showAll = !showAll }
                    },
                )
                Spacer(Modifier.weight(1f))
                if (state is NewPackState.Failed) {
                    Text(
                        state.message.ifBlank { s.text("contentInstallFailed") },
                        style = MaterialTheme.typography.labelMedium,
                        color = NxColor.status(Status.Error),
                        maxLines = 2,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    NxButton(
                        label = s.text("installDialogCreate"),
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
                Text(s.text("installDialogDone", state.packName), style = MaterialTheme.typography.titleMedium, color = NxInk.main, fontWeight = FontWeight.SemiBold)
                if (state.missing > 0) {
                    Text(s.text("modPageInstallMissing", state.missing), style = MaterialTheme.typography.labelMedium, color = NxColor.status(Status.Error))
                }
                NxButton(label = s.text("installDialogOpenPack"), onClick = { model.openPack(state.packId) }, style = NxButtonStyle.Secondary, compact = true)
            }
        }
    }
}

@Composable
private fun CreatingNote(state: NewPackState) {
    val s = nexiraStrings()
    val creating = state as? NewPackState.Creating
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(color = NxColor.lead(), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Text(
            if (state is NewPackState.Creating) s.text("installDialogCreating") else s.text("modPageInstalling"),
            style = MaterialTheme.typography.titleSmall,
            color = NxInk.main,
        )
        creating?.takeIf { it.total > 0 }?.let {
            Text("${it.done} / ${it.total}", style = MaterialTheme.typography.labelMedium, color = NxInk.quiet)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = NxInk.main, fontWeight = FontWeight.SemiBold)
}

/**
 * A pack's own mark, its icon or its initials over its hue. The icon is the library's,
 * read through the packs module, and a pack it does not list draws its initials.
 */
@Composable
internal fun PackMark(packId: String, name: String, size: Dp, dimmed: Boolean = false) {
    val pack = rememberScopedSource(PackKeys.Library).valueOrNull()?.firstOrNull { it.id == packId }
    val iconUrl = pack?.let { rememberPackArt(it).iconUrl }
    val hue = decorativePair(packId).first
    val alpha = if (dimmed) 0.5f else 1f
    SubcomposeAsyncImage(
        model              = iconUrl,
        contentDescription = null,
        contentScale       = ContentScale.Crop,
        alpha              = alpha,
        modifier           = Modifier.size(size).clip(RoundedCornerShape(size / 4)),
        loading            = { Box(Modifier.fillMaxSize().background(hue.copy(alpha = alpha))) },
        error              = { InitialsAvatar(name, hue.copy(alpha = alpha)) },
    )
}

/**
 * The install for a project page with no pack behind it: the same button a card
 * carries, and the same dialog behind it.
 */
@Composable
internal fun InstallChooser(subject: InstallSubject) {
    val s = nexiraStrings()
    var open by remember { mutableStateOf(false) }
    NxButton(label = s.text("modPageInstallChoose"), onClick = { open = true }, icon = NxIcon.Download)
    PuppetClick("modPage.installChoose") { open = true }
    if (open) InstallDialog(subject) { open = false }
}
