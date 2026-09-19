package hivens.ui.screens.mod

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.update.VersionChannel
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.RIGHT_RAIL_SURFACE
import hivens.ui.RailFamily
import hivens.ui.components.LoaderGlyph
import hivens.ui.components.ReleaseNotes
import hivens.ui.components.formatBuildTimestamp
import hivens.ui.components.hasLoaderGlyph
import hivens.ui.components.relativeAge
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.CenteredProgress
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxMetaChip
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.NxTooltip
import hivens.ui.nx.RetryStateBlock
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.utils.humanSize
import hivens.widget.api.LocalSurfaceFamilies
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.nio.file.Path

/**
 * One build of a project, on its own page.
 *
 * The versions table answers which builds exist and stops there, because the
 * rest of what a person wants to know about a build -- what it refuses to run
 * beside, what the author wrote about it, which of its three jars the installer
 * takes -- is a paragraph each and cannot be a column.
 *
 * The rail keeps showing the PROJECT while this is open, which is why the page
 * stands up a [ModDetailState] of its own: a build belongs to a project, and a
 * reader who followed a row here has not left it.
 */
@Composable
fun ModVersionScreen(
    target: ModTarget,
    versionId: String,
    modifier: Modifier = Modifier,
) {
    val modrinth: ModrinthClient = koinInject()
    val repo: IPackRepository = koinInject()
    val scanner: InstanceContentScanner = koinInject()
    val openProject: OpenProjectState = koinInject()
    val dataDir: Path = koinInject()
    val families = LocalSurfaceFamilies.current
    val s = LocalStrings.current

    // The project, for the rail and for where an install would go.
    val project = remember(target) {
        ModDetailState(target, modrinth, repo, dataDir, scanner, openProject, strings = s)
    }
    val build = remember(target, versionId) { ModVersionState(modrinth) }
    var reloadTick by remember(build) { mutableIntStateOf(0) }

    LaunchedEffect(project, reloadTick) { project.load() }
    // Keyed on the project, the same as the two panes on the project page: the id
    // this build belongs to arrives with the project, and asking before it lands
    // is asking with nothing to ask about.
    LaunchedEffect(build, project.project, reloadTick) {
        val id = project.project?.id ?: return@LaunchedEffect
        project.loadGameVersionTags()
        build.load(id, versionId)
    }

    DisposableEffect(project) {
        families.switch(RIGHT_RAIL_SURFACE, RailFamily.PROJECT_VIEW)
        onDispose {
            families.reset(RIGHT_RAIL_SURFACE)
            project.clear()
        }
    }

    ModVersionBody(
        project = project,
        build = build,
        onRetry = { reloadTick++ },
        modifier = modifier.fillMaxSize().padding(14.dp),
    )
}

/**
 * The page without its wiring, so a render sheet can stand one up from fixtures.
 *
 * The screen above resolves five things out of the graph and then draws exactly
 * this. Splitting them is what lets the layout be looked at without a catalogue
 * behind it, which is the only way a layout defect gets found before a person
 * finds it.
 */
@Composable
internal fun ModVersionBody(
    project: ModDetailState,
    build: ModVersionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    NxSurface(level = NxSurfaceLevel.Raised, modifier = modifier) {
        val v = build.version
        when {
            project.failed || build.failed -> RetryStateBlock(
                title = s.modVersionFailed,
                message = s.modPageVersionsFailedBody,
                retryLabel = s.contentTabRetry,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize().padding(20.dp),
                titleStyle = MaterialTheme.typography.titleMedium,
            )
            project.loading || build.loading || v == null -> CenteredProgress(Modifier.fillMaxSize())
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(SECTION_GAP),
            ) {
                BuildHeader(v, project, scope)
                HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
                Compatibility(v, project)
                Dependencies(build)
                Changes(v)
                Files(v)
            }
        }
    }
}

/**
 * The build's name, its channel, and the two numbers that place it in time.
 *
 * The install sits on the right, where the project page keeps it, because a
 * reader who came down to a specific build came to take THAT one.
 */
@Composable
private fun BuildHeader(v: ModrinthVersion, project: ModDetailState, scope: kotlinx.coroutines.CoroutineScope) {
    val s = LocalStrings.current
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
                    color = NxTheme.colors.textPrimary,
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
                    Symbol(NxIcon.Download, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 14.dp)
                    NxTooltip(text = v.downloads.toString()) { Subtle(compactCount(v.downloads, s)) }
                }
            }
        }
        // Names the PACK, not the build. The build is the headline two lines up,
        // and a button repeating it read as "Установить 1.8.14-beta.1+1.21.1-neoforge"
        // beside a title saying exactly that -- while the one thing the button
        // decides, which pack the file lands in, went unsaid.
        (project.install as? InstallAction.Install)?.let { action ->
            NxButton(
                label = if (project.installing) s.modPageInstalling else s.modPageInstallInto(action.packName),
                onClick = { scope.launch { project.installVersion(v) } },
                icon = NxIcon.Download,
                enabled = !project.installing,
            )
        }
    }
}

@Composable
private fun Compatibility(v: ModrinthVersion, project: ModDetailState) {
    val s = LocalStrings.current
    Section(s.modRailCompatibility) {
        Group(s.modRailGame) {
            // Folded against the catalogue's release order, the same as the rail's
            // own block: a build listing eleven minors says `1.21.x` there and must
            // not spell all eleven out here.
            val groups = remember(v, project.gameVersionTags) {
                groupGameVersions(v.gameVersions, project.gameVersionTags)
                    .ifEmpty { v.gameVersions.map { GameVersionGroup(it, listOf(it)) } }
            }
            Chips { groups.forEach { NxMetaChip(it.label, tone = NxMetaChipTone.Surface) } }
        }
        Group(s.modRailPlatforms) {
            Chips {
                v.loaders.forEach { loader ->
                    NxMetaChip(
                        loaderLabel(loader),
                        tone = NxMetaChipTone.Surface,
                        dot = loaderDot(loader).takeUnless { hasLoaderGlyph(loader) },
                        leading = if (hasLoaderGlyph(loader)) {
                            { LoaderGlyph(loader, tint = NxTheme.colors.textSecondary, size = 12.dp) }
                        } else {
                            null
                        },
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
private fun Dependencies(build: ModVersionState) {
    val s = LocalStrings.current
    val groups = listOf(
        DependencyKind.Required to s.modVersionRequires,
        DependencyKind.Optional to s.modVersionOptional,
        DependencyKind.Incompatible to s.modVersionIncompatible,
    )
    val follow = rememberLinkFollower()
    groups.forEach { (kind, title) ->
        val rows = build.dependencies.filter { it.kind == kind }
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
                            dep.title.ifBlank { s.modRailUnknownValue },
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = ROW_TEXT,
                            color = NxTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        dep.versionNumber?.let {
                            NxTooltip(text = s.modVersionPinnedBuild) {
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
        Symbol(NxIcon.Warning, contentDescription = null, tint = NxTheme.colors.error, size = 16.dp)
        return
    }
    val box = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
    if (dep.iconUrl != null) {
        AsyncImage(model = dep.iconUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = box)
    } else {
        Box(box) { InitialsAvatar(dep.title, NxTheme.colors.decorativeColor(dep.title)) }
    }
}

@Composable
private fun Changes(v: ModrinthVersion) {
    val s = LocalStrings.current
    Section(s.modPageTabChangelog) {
        val notes = v.changelog?.takeIf { it.isNotBlank() }
        if (notes == null) {
            Text(
                s.versionPickerNoChangelog,
                style = MaterialTheme.typography.bodySmall,
                fontSize = ROW_SUBTLE,
                color = NxTheme.colors.textSecondary,
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
private fun Files(v: ModrinthVersion) {
    val s = LocalStrings.current
    Section(s.modVersionFiles) {
        Column(verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            v.files.forEach { file ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (file.primary) {
                        NxTooltip(text = s.modVersionPrimaryFile) {
                            Symbol(NxIcon.Star, contentDescription = null, tint = NxTheme.colors.warnAccent, size = 16.dp)
                        }
                    } else {
                        Symbol(
                            NxIcon.InsertDriveFile,
                            contentDescription = null,
                            tint = NxTheme.colors.textSecondary,
                            size = 16.dp,
                        )
                    }
                    Text(
                        file.filename,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = ROW_TEXT,
                        color = NxTheme.colors.textPrimary,
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
            color = NxTheme.colors.onSurface,
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
            color = NxTheme.colors.textPrimary,
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
    color = NxTheme.colors.textSecondary,
    maxLines = 1,
)

/** The separator between two short facts on one line, as the reference sets it. */
@Composable
private fun Dot() = Box(
    Modifier.size(4.dp).clip(CircleShape).background(NxTheme.colors.outline),
)

@Composable
private fun channelColor(channel: VersionChannel) = when (channel) {
    VersionChannel.Release -> NxTheme.colors.success
    VersionChannel.Beta -> NxTheme.colors.warnAccent
    VersionChannel.Alpha -> NxTheme.colors.error
}
