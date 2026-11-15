package hivens.ui.feature.catalogue.project

import androidx.compose.foundation.background
import hivens.ui.i18n.nexiraStrings
import hivens.ui.theme.decorativeColor
import hivens.ui.nx.InitialsAvatar
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.catalogue.api.BuildPageView
import hivens.catalogue.api.BuildVisit
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.DependencyKind
import hivens.catalogue.api.InstallOffer
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.ProjectInstall
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.VersionDependency
import hivens.catalogue.api.gameVersionChips
import hivens.core.update.VersionChannel
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.ReleaseNotes
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.components.relativeAge
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTooltip
import hivens.ui.nx.RetryStateBlock
import hivens.ui.feature.catalogue.browse.ProjectTag
import hivens.ui.feature.catalogue.browse.tagSearch
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.utils.humanSize
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * One build of a project, on its own page.
 *
 * The versions table answers which builds exist and stops there, because the
 * rest of what a person wants to know about a build -- what it refuses to run
 * beside, what the author wrote about it, which of its three jars the installer
 * takes -- is a paragraph each and cannot be a column.
 *
 * The rail keeps showing the PROJECT while this is open, which is why the page
 * shows the project page of its own level beside the build: a build belongs to a
 * project, and a reader who followed a row here has not left it.
 */
@Composable
fun ModVersionScreen(
    target: ProjectTarget,
    versionId: String,
    modifier: Modifier = Modifier,
) {
    val families = LocalSurfaceFamilies.current
    val showBuild = rememberScopedCommand(CatalogueKeys.ShowBuild)
    val reload = rememberScopedAction(CatalogueKeys.ReloadProject)

    // The project, for the rail and for where an install would go, and the build.
    val visit = rememberProjectVisit(target)
    val project = rememberProjectPage(target, visit)
    LaunchedEffect(visit, versionId, showBuild != null) { showBuild?.invoke(BuildVisit(versionId, visit)) }
    val build = rememberScopedSource(CatalogueKeys.BuildPage).valueOrNull()?.takeIf { it.visit == visit && it.versionId == versionId }

    DisposableEffect(visit) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW, visit)
        onDispose { families.reset(RIGHT_RAIL_SURFACE, visit) }
    }

    ModVersionBody(
        project = project,
        build = build,
        onRetry = { reload?.invoke() },
        modifier = modifier.fillMaxSize().padding(14.dp),
    )
}

/**
 * The page without its wiring, so a render sheet can stand one up from fixtures.
 *
 * The screen above shows the page to the catalogue module and then draws exactly
 * this. Splitting them is what lets the layout be looked at without a catalogue
 * behind it, which is the only way a layout defect gets found before a person
 * finds it.
 */
@Composable
internal fun ModVersionBody(
    project: ProjectPageView,
    build: BuildPageView?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    NxSurface(SurfaceKind.Panel, modifier = modifier) {
        when (buildPageView(project, build)) {
            BuildPageState.Failed -> RetryStateBlock(
                title = s.text("modVersionFailed"),
                message = s.text("modPageVersionsFailedBody"),
                retryLabel = s.text("contentTabRetry"),
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(20.dp),
                titleStyle = MaterialTheme.typography.titleMedium,
            )
            BuildPageState.NoEntry -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Text(s.text("modPageVersionsNoEntry"), style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }
            BuildPageState.Loading -> CenteredProgress(Modifier.fillMaxSize())
            BuildPageState.Ready -> build?.build?.let { v ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
                ) {
                    BuildHeader(v, project)
                    HorizontalDivider(color = NxColor.wash(NxInk.line, 0.25f))
                    Compatibility(v, project)
                    Dependencies(build.dependencies, project.packId)
                    Changes(v)
                    Files(v)
                }
            }
        }
    }
}

/** What a build's page shows: a retry, that there is nothing to ask about, a spinner, or the build. */
internal enum class BuildPageState { Failed, NoEntry, Loading, Ready }

/**
 * Which of the four the page is in. A page whose project has loaded with no
 * catalogue entry has no build to ask for: the file it was opened on has gone since,
 * or the catalogue never knew it. Before this was its own state, the page waited
 * on a spinner for a build nobody would ever ask for.
 */
internal fun buildPageView(project: ProjectPageView, build: BuildPageView?): BuildPageState = when {
    project.failed || build?.failed == true -> BuildPageState.Failed
    !project.loading && project.project == null -> BuildPageState.NoEntry
    project.loading || build == null || build.loading || build.build == null -> BuildPageState.Loading
    else -> BuildPageState.Ready
}

/**
 * The build's name, its channel, and the two numbers that place it in time.
 *
 * The install sits on the right, where the project page keeps it, because a
 * reader who came down to a specific build came to take THAT one.
 */
@Composable
private fun BuildHeader(v: ProjectBuild, project: ProjectPageView) {
    val s = nexiraStrings()
    val install = rememberScopedCommand(CatalogueKeys.InstallProject)
    val channel = remember(v) { VersionChannel.of(v.versionType, v.versionNumber) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    v.versionNumber.ifBlank { v.name },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = TITLE_SIZE,
                    color = NxInk.main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(Modifier.size(9.dp).clip(CircleShape).background(channelColor(channel)))
            }
            // The name, the date and the count on one line, separated by dots the
            // way the reference sets them. Three short facts stacked into three
            // lines reads as three sections.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.name.isNotBlank() && v.name != v.versionNumber) {
                    Subtle(v.name)
                    Dot()
                }
                NxTooltip(text = formatBuildTimestamp(v.datePublished).orEmpty()) {
                    Subtle(relativeAge(v.datePublished, s))
                }
                Dot()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Symbol(NxIcon.Download, contentDescription = null, tint = NxInk.quiet, size = 14.dp)
                    NxTooltip(text = (v.downloads ?: 0L).toString()) { Subtle(compactCount(v.downloads ?: 0L, s)) }
                }
            }
        }
        // Names the PACK, not the build. The build is the headline two lines up,
        // and a button repeating it read as "Установить 1.8.14-beta.1+1.21.1-neoforge"
        // beside a title saying exactly that -- while the one thing the button
        // decides, which pack the file lands in, went unsaid.
        //
        // Marked the way the versions table marks the same build: one that does not
        // run on the pack is still offered, since the reader came to take this one,
        // and the line under it says which way they are stepping. What the install
        // came to is said there too, as on the project page.
        val fits = remember(v, project.packMcVersion, project.packLoaders) {
            runsOn(v, project.packMcVersion, project.packLoaders)
        }
        when (val action = project.install) {
            is InstallOffer.Install -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NxButton(
                    label = when {
                        project.installing -> s.text("modPageInstalling")
                        project.installFailed -> s.text("modPageInstallRetry")
                        else -> s.text("modPageInstallInto", action.packName)
                    },
                    onClick = { install?.invoke(ProjectInstall(v.id)) },
                    icon = if (project.installFailed) NxIcon.Refresh else NxIcon.Download,
                    enabled = !project.installing,
                )
                if (!fits) {
                    Text(s.text("versionsIncompatibleHint"), style = MaterialTheme.typography.labelSmall, color = NxColor.status(Status.Warning))
                }
                InstallNotes(project)
            }
            is InstallOffer.Present -> Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NxButton(
                    label = s.text("modPageInstalledIn", action.packName),
                    onClick = {},
                    icon = NxIcon.Check,
                    enabled = false,
                    style = NxButtonStyle.Secondary,
                )
                InstallNotes(project)
            }
            else -> Unit
        }
    }
}

@Composable
private fun Compatibility(v: ProjectBuild, project: ProjectPageView) {
    val s = nexiraStrings()
    val type = project.project?.projectType
    Section(s.text("modRailCompatibility")) {
        Group(s.text("modRailGame")) {
            // Folded against the catalogue's release order, the same as the rail's
            // own block: a build listing eleven minors says `1.21.x` there and must
            // not spell all eleven out here.
            val groups = remember(v, project.gameVersionTags) {
                gameVersionChips(v.gameVersions, project.gameVersionTags)
            }
            Chips {
                groups.forEach {
                    NxMetaChip(it.label, tone = NxMetaChipTone.Surface, onClick = tagSearch(type, project.packId, ProjectTag.GameVersions(it.versions)))
                }
            }
        }
        Group(s.text("modRailPlatforms")) {
            Chips {
                v.loaders.forEach { loader ->
                    NxMetaChip(
                        loaderLabel(loader),
                        tone = NxMetaChipTone.Surface,
                        dot = loaderDot(loader).takeUnless { hasLoaderGlyph(loader) },
                        leading = if (hasLoaderGlyph(loader)) {
                            { LoaderGlyph(loader, tint = NxInk.quiet, size = 12.dp) }
                        } else {
                            null
                        },
                        onClick = tagSearch(type, project.packId, ProjectTag.Loader(loader)),
                    )
                }
            }
        }
    }
}

/**
 * What the author said about other projects, one heading per statement.
 *
 * Embedded entries are left out: the jar already contains them, so there is
 * nothing for a reader to do about one and listing it invites them to go and
 * install what they already have.
 */
@Composable
private fun Dependencies(dependencies: List<VersionDependency>, packId: String?) {
    val s = nexiraStrings()
    val groups = listOf(
        DependencyKind.Required to s.text("modVersionRequires"),
        DependencyKind.Optional to s.text("modVersionOptional"),
        DependencyKind.Incompatible to s.text("modVersionIncompatible"),
    )
    val follow = rememberLinkFollower(packId)
    groups.forEach { (kind, title) ->
        val rows = dependencies.filter { it.kind == kind }
        if (rows.isEmpty()) return@forEach
        Section(title) {
            Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
                rows.forEach { dep ->
                    // A dependency the catalogue knows is a page of its own, so the
                    // row is the way to it. One that it does not know is a name and
                    // stays a name: a row that looks clickable and is not is worse
                    // than one that never offered.
                    val target = dep.projectId?.let(::projectUrl)
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .then(
                                if (target == null) Modifier
                                else Modifier.clickable { follow(target) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DependencyMark(dep, kind)
                        Text(
                            dep.title.ifBlank { s.text("modRailUnknownValue") },
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = ROW_TEXT,
                            color = NxInk.main,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        dep.versionNumber?.let {
                            NxTooltip(text = s.text("modVersionPinnedBuild")) {
                                NxMetaChip(it, tone = NxMetaChipTone.Surface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The mod's own art where the catalogue has it, its initials where it does not,
 * and a warning where the author said the two do not go together.
 *
 * The incompatible list is the one place a glyph beats a mark: the row is a
 * warning about a project rather than a way to it, and the project's own logo
 * would read as a recommendation.
 */
@Composable
private fun DependencyMark(dep: VersionDependency, kind: DependencyKind) {
    if (kind == DependencyKind.Incompatible) {
        Symbol(NxIcon.Warning, contentDescription = null, tint = NxColor.status(Status.Error), size = 16.dp)
        return
    }
    val box = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
    if (dep.iconUrl != null) {
        AsyncImage(model = dep.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
    } else {
        Box(box) { InitialsAvatar(dep.title, decorativeColor(dep.title)) }
    }
}

@Composable
private fun Changes(v: ProjectBuild) {
    val s = nexiraStrings()
    Section(s.text("modPageTabChangelog")) {
        val notes = v.changelog?.takeIf { it.isNotBlank() }
        if (notes == null) {
            Text(
                s.text("versionPickerNoChangelog"),
                style = MaterialTheme.typography.bodySmall,
                fontSize = ROW_SUBTLE,
                color = NxInk.quiet,
            )
        } else {
            ReleaseNotes(notes, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Every file the build ships, with the one the installer takes marked.
 *
 * A build with three jars -- the mod, its sources, a deobfuscated copy -- is
 * ordinary, and which of them is the one that gets installed is not derivable
 * from the names.
 */
@Composable
private fun Files(v: ProjectBuild) {
    val s = nexiraStrings()
    Section(s.text("modVersionFiles")) {
        Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            v.files.forEach { file ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (file.primary) {
                        NxTooltip(text = s.text("modVersionPrimaryFile")) {
                            Symbol(NxIcon.Star, contentDescription = null, tint = NxColor.status(Status.Warning), size = 16.dp)
                        }
                    } else {
                        Symbol(
                            NxIcon.InsertDriveFile,
                            contentDescription = null,
                            tint = NxInk.quiet,
                            size = 16.dp,
                        )
                    }
                    Text(
                        file.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = ROW_TEXT,
                        color = NxInk.main,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Subtle(humanSize(file.size, s))
                }
            }
        }
    }
}

// ── Shared furniture, measured against the rail's blocks so the page reads as one ──

private val TITLE_SIZE = 24.sp
private val BLOCK_TITLE = 18.sp
private val GROUP_LABEL = 16.sp

/**
 * The measure the reference sets its body at, and the one under it.
 *
 * Sixteen, not the app's default fourteen. This page is a column of short facts
 * under an 18 heading, and at fourteen the whole of it read as a footnote to its
 * own title -- the same error the rail made before its labels were measured off
 * the reference rather than taken from the type scale.
 */
private val ROW_TEXT = 16.sp
private val ROW_SUBTLE = 14.sp
private val SECTION_GAP = 16.dp
private val GROUP_GAP = 8.dp

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontSize = BLOCK_TITLE,
            color = NxInk.main,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = GROUP_LABEL,
            color = NxInk.main,
            fontWeight = FontWeight.Normal,
        )
        content()
    }
}

@Composable
private fun Chips(content: @Composable () -> Unit) = FlowRow(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) { content() }

@Composable
private fun Subtle(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    fontSize = ROW_SUBTLE,
    color = NxInk.quiet,
    maxLines = 1,
)

/** The separator between two short facts on one line, as the reference sets it. */
@Composable
private fun Dot() = Box(
    Modifier.size(4.dp).clip(CircleShape).background(NxInk.line),
)

@Composable
private fun channelColor(channel: VersionChannel) = when (channel) {
    VersionChannel.Release -> NxColor.status(Status.Success)
    VersionChannel.Beta -> NxColor.status(Status.Warning)
    VersionChannel.Alpha -> NxColor.status(Status.Error)
}
