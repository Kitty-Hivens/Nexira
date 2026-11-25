package hivens.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.CataloguePages
import hivens.catalogue.api.OpenProject
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.packTargetKey
import hivens.core.api.interfaces.IPackRepository
import hivens.i18n.api.Strings
import hivens.packs.api.PackPages
import hivens.ui.i18n.nexiraStrings
import hivens.ui.navigation.LauncherConfigs
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.HistoryEntry
import hivens.widget.model.screen
import org.koin.compose.koinInject

/**
 * The label of a crumb nothing has to be looked up for, or null where it has: a pack and a project are called
 * what they are called, and a made screen what its maker named it. Pure, so the mapping is checked whole.
 */
fun staticCrumbLabel(entry: HistoryEntry, s: Strings): String? = when (entry.config) {
    LauncherConfigs.Home         -> s.text("crumbHome")
    LauncherConfigs.Library      -> s.text("libraryHeaderTitle")
    LauncherConfigs.Browse       -> s.text(if (CataloguePages.Into.read(entry.opened) == null) "browseTitle" else "contentFindProjects")
    LauncherConfigs.Profile      -> s.text("profileTitle")
    LauncherConfigs.Wardrobe     -> s.text("wardrobeTitle")
    LauncherConfigs.Settings     -> s.text("settingsTitle")
    LauncherConfigs.Themes       -> s.text("themePickerTitle")
    LauncherConfigs.About        -> s.text("aboutTitle")
    LauncherConfigs.Background   -> s.text("backgroundTitle")
    LauncherConfigs.PackVersions -> s.text("packVersionsTitle")
    // The entry carries the build's own number, so this one needs nothing fetched.
    LauncherConfigs.Build        -> CataloguePages.Build.read(entry.opened)?.versionNumber.orEmpty()
    else                         -> null
}

/**
 * What a project page is called before anything has been fetched, and if nothing
 * ever is.
 *
 * A file answers with its own name minus the extension, which is what the reader
 * clicked and is recognisable even when the catalogue has never heard of it. A
 * catalogue entry answers with its id, which is usually the slug and reads as a
 * name.
 */
private fun modFallbackLabel(target: ProjectTarget): String = when (target) {
    is ProjectTarget.Catalogue -> target.projectId
    is ProjectTarget.Installed -> target.fileName.substringBeforeLast('.')
}

/**
 * Human label for a breadcrumb segment. A pack's page is named by the pack (installed
 * pack via the repository, a source's via the catalogue) and not by its raw id, which
 * stands in until the name loads and where it never does. Everything else is the
 * synchronous [staticCrumbLabel].
 */
@Composable
fun rememberCrumbLabel(entry: HistoryEntry): String {
    val s = nexiraStrings()
    staticCrumbLabel(entry, s)?.let { return it }
    return when (entry.config) {
        LauncherConfigs.Pack -> installedCrumb(PackPages.Pack.read(entry.opened).orEmpty())
        LauncherConfigs.CataloguePack -> CataloguePages.Pack.read(entry.opened)?.let { catalogueCrumb(it.origin, it.packId) }.orEmpty()
        LauncherConfigs.Project -> CataloguePages.Project.read(entry.opened)?.let { modCrumb(it) }.orEmpty()
        // Read live, so a rename in the editor relabels the crumb at once. A screen
        // deleted while it was open says so rather than naming nothing.
        else -> LocalLayoutGraph.current.screen(entry.config.value)?.title?.ifBlank { null } ?: s.text("screenMissing")
    }
}

/**
 * An installed pack's name, followed: read once, this crumb kept the name the pack had
 * when it was first shown. The rename happens in place, without navigating, and the top
 * bar is chrome that is never disposed, so only visiting a different pack could ever fix
 * it. The registry always has a value, so there is no loading state to pass through, and
 * an id the registry does not hold is a pack that is gone, with the id its honest label.
 */
@Composable
private fun installedCrumb(id: String): String {
    val repo: IPackRepository = koinInject()
    val instances by remember { repo.observe() }.collectAsState()
    return instances.firstOrNull { it.id == id }?.displayName ?: id
}

/**
 * A project page's title, taken from what the page itself published rather than
 * fetched a second time.
 *
 * Guarded on the target, because the trail can hold an entry that is not the
 * screen on top: an unguarded read would label every project crumb with whatever
 * page is open now. A page already left answers with the name it had, and one
 * never loaded with the route's own name, which is what the reader clicked.
 */
@Composable
private fun modCrumb(target: ProjectTarget): String {
    val open = rememberScopedSource(CatalogueKeys.OpenProject).valueOrNull()
    val names = rememberScopedSource(CatalogueKeys.Names).valueOrNull().orEmpty()
    return modCrumbLabel(target, open, names)
}

/**
 * Only an answer is a name. A page still waiting publishes the route's own id as
 * its title, and taken from there it covered the name a visit before had left in
 * [names] until the page had heard back.
 */
internal fun modCrumbLabel(target: ProjectTarget, open: OpenProject?, names: Map<String, String>): String =
    open?.takeIf { it.targetKey == target.key && !it.pending }?.title
        ?: names[target.key]
        ?: modFallbackLabel(target)

/**
 * A catalogue pack's title, from what its page published, the way a project crumb
 * is. Asking the catalogue for the page a second time cost a whole page read per
 * visit, a Modrinth pack's every build and its team included. The id stands in until
 * the page has read the pack, and for one it never could.
 */
@Composable
private fun catalogueCrumb(origin: String, id: String): String {
    val names = rememberScopedSource(CatalogueKeys.Names).valueOrNull().orEmpty()
    return names[packTargetKey(origin, id)] ?: id
}
