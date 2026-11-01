package hivens.ui

import hivens.core.data.PackOrigin
import hivens.core.data.SessionData
import hivens.ui.feature.catalogue.project.ModTarget
import hivens.ui.screens.detail.settings.PackSettingsCategory

// ─── State ───────────────────────────────────────────────────────────────────

sealed class AppState {
    object Loading : AppState()
    object Unauthenticated : AppState()
    data class Authenticated(val session: SessionData) : AppState()
}

// ─── Navigation ──────────────────────────────────────────────────────────────

sealed class Screen {
    object Home               : Screen()
    object Library            : Screen()
    object Browse             : Screen()
    object Profile            : Screen()
    object Wardrobe           : Screen()
    object Settings           : Screen()
    object ThemePicker        : Screen()
    object About              : Screen()
    object BackgroundSettings : Screen()

    /**
     * Library card click target. Carries the PackInstance UUID; the
     * detail screen resolves it via [hivens.core.api.interfaces.IPackRepository]
     * so the Screen sealed class stays free of domain types and the
     * back-stack item stays small (a UUID string, not a PackInstance
     * graph). [openSettings] restores the settings overlay on arrival --
     * stamped onto the back-stack entry when the user drills from the
     * settings window into the versions screen, so Back lands them in
     * the settings they left, not on the bare pack page.
     */
    data class PackDetail    (
        val instanceId: String,
        val openSettings: Boolean = false,
        /**
         * Which section the settings panel reopens on. Null means the one it
         * opens on by default; leaving the version screen names Version, because
         * that is where the user was standing when they left.
         */
        val settingsSection: PackSettingsCategory? = null,
    ) : Screen()

    /**
     * Version manager for an installed mirror pack: the retained build list,
     * per-build changelog (client-side manifest diff), switch/rollback and
     * restore points. Same UUID-only payload rationale as [PackDetail].
     */
    data class PackVersions  (val instanceId: String) : Screen()

    /**
     * The launcher's browse opened from inside a pack: narrowed to what the pack
     * runs, and every install goes into it. A screen of its own rather than a flag
     * on [Browse], because Browse is a tab every rail entry switches to, and the
     * pack it installs into has to ride in the back stack with it.
     */
    data class BrowseInto(val instanceId: String) : Screen()

    /**
     * Catalogue-side detail target, source-neutral: carries the [origin] + that
     * source's local pack id. The one [hivens.ui.feature.catalogue.browse.CataloguePackDetailScreen]
     * resolves both through [hivens.launcher.catalogue.PackCatalogueRegistry] +
     * [hivens.launcher.PackInstallCoordinator]. Distinct from [PackDetail], which
     * resolves an already-installed [hivens.core.data.PackInstance].
     */
    data class CataloguePackDetail(val origin: PackOrigin, val packId: String) : Screen()

    /**
     * The project page, rendered natively rather than linked out.
     *
     * A SCREEN and not a panel, because its metadata blocks live in
     * `appshell.rightrail` -- a shell surface present on every screen -- and a
     * page carrying its own right-hand column would stand a third column beside
     * the news. Opening one therefore navigates; it cannot open in place, because
     * in place the blocks have nowhere to appear.
     *
     * Carries a [ModTarget] rather than a project id so a jar the catalogue has
     * never indexed gets the same page as one it has.
     */
    data class ModDetail(val target: ModTarget) : Screen()

    /**
     * One build of a project: what it runs on, what it needs, what changed and
     * which files it ships.
     *
     * The versions table answers "which builds exist" and cannot also answer
     * "what is this one", because the second question needs a column per
     * dependency and a paragraph of notes. It carries the [target] as well as the
     * build so the rail keeps describing the project the build belongs to, and so
     * an install from here knows which pack it is installing into.
     *
     * [versionNumber] rides along because the caller always has it and the
     * breadcrumb needs it on the first frame. Resolving it would label the crumb
     * with a catalogue id for as long as the fetch took, which is the one name
     * nobody clicked.
     */
    data class ModVersion(
        val target: ModTarget,
        val versionId: String,
        val versionNumber: String,
    ) : Screen()

    /**
     * A screen somebody made, by its id. Everything about it, what it is called and
     * what it holds, is in the layout graph, so this carries the id and nothing else
     * and a rename never leaves a stale title on the back stack.
     */
    data class Custom(val id: String) : Screen()

    /**
     * Identity for state that outlives a visit, stable across the fields a screen
     * stamps onto its own back-stack entry.
     *
     * Not the entry itself: [PackDetail] re-describes itself with [PackDetail.openSettings]
     * before pushing the versions screen, and keying on the whole entry would throw
     * away the tab, the file tree and the scroll every time that note was taken.
     * Two visits to the same pack are the same place; two visits to different packs
     * are not, which is why the ids are in the key and nothing else is.
     */
    val retentionKey: String get() = when (this) {
        is PackDetail          -> "PackDetail:$instanceId"
        is PackVersions        -> "PackVersions:$instanceId"
        is BrowseInto          -> "BrowseInto:$instanceId"
        is CataloguePackDetail -> "CataloguePackDetail:$origin:$packId"
        is ModDetail           -> "ModDetail:${target.key}"
        is ModVersion          -> "ModVersion:${target.key}:$versionId"
        is Custom              -> "Custom:$id"
        else                   -> this::class.simpleName.orEmpty()
    }
}
