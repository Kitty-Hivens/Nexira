package hivens.catalogue.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * Every key the catalogue module answers: the id as a constant, for `needs` and for
 * the document, and the typed key a widget reads or fires.
 *
 * The catalogue's own lists and what the pages visited were called live at the app
 * level. What Browse searches, its results, the install that asks where and the
 * project the rail describes live at the window level, one of each per window, so a
 * second window browses on its own and a trip to a project page comes back to the
 * same search. A project, a build and a pack page live
 * at the page level, one per history entry, shown by the screen that draws the page.
 * They exist only where a document names the catalogue module, and a widget that needs
 * one stands misplaced anywhere else.
 *
 * Commands are fire-and-forget. What came of one is read from a source.
 */
object CatalogueKeys {
    const val TAGS_ID: String = "catalogue.tags"
    const val LOAD_TAGS_ID: String = "catalogue.tags.load"

    const val BROWSE_ID: String = "catalogue.browse"
    const val RESULTS_ID: String = "catalogue.results"
    const val OPEN_PROJECT_ID: String = "mod.open"
    const val NAMES_ID: String = "catalogue.names"
    const val INSTALL_CHOICE_ID: String = "catalogue.install.choice"

    const val SET_KIND_ID: String = "catalogue.browse.kind"
    const val SET_ORIGIN_ID: String = "catalogue.browse.origin"
    const val SET_QUERY_ID: String = "catalogue.browse.query"
    const val SET_SORT_ID: String = "catalogue.browse.sort"
    const val SET_TARGET_ID: String = "catalogue.browse.target"
    const val AIM_ID: String = "catalogue.browse.aim"
    const val TOGGLE_FILTER_ID: String = "catalogue.browse.filter.toggle"
    const val EXCLUDE_FILTER_ID: String = "catalogue.browse.filter.exclude"
    const val REMOVE_FILTER_ID: String = "catalogue.browse.filter.remove"
    const val CLEAR_FILTERS_ID: String = "catalogue.browse.filter.clear"
    const val SEARCH_FOR_ID: String = "catalogue.browse.searchFor"
    const val UNLOCK_ID: String = "catalogue.browse.unlock"
    const val RELOCK_ID: String = "catalogue.browse.relock"
    const val HIDE_INSTALLED_ID: String = "catalogue.browse.hideInstalled"
    const val MIRROR_OWN_ID: String = "catalogue.browse.mirrorOwn"
    const val MIRROR_COMMUNITY_ID: String = "catalogue.browse.mirrorCommunity"
    const val TOGGLE_HIDE_INSTALLED_ID: String = "catalogue.browse.hideInstalled.toggle"
    const val TOGGLE_MIRROR_OWN_ID: String = "catalogue.browse.mirrorOwn.toggle"
    const val TOGGLE_MIRROR_COMMUNITY_ID: String = "catalogue.browse.mirrorCommunity.toggle"
    const val MORE_RESULTS_ID: String = "catalogue.results.more"
    const val RETRY_RESULTS_ID: String = "catalogue.results.retry"
    const val SHOW_RESULTS_ID: String = "catalogue.results.show"
    const val HIDE_RESULTS_ID: String = "catalogue.results.hide"
    const val SCROLL_RESULTS_ID: String = "catalogue.results.scroll"
    const val INSTALL_RESULT_ID: String = "catalogue.results.install"
    const val CHOOSE_INSTALL_ID: String = "catalogue.install.choose"
    const val INSTALL_INTO_ID: String = "catalogue.install.into"
    const val INSTALL_NEW_PACK_ID: String = "catalogue.install.newPack"
    const val RETRY_CHOICE_ID: String = "catalogue.install.retry"
    const val DISMISS_INSTALL_ID: String = "catalogue.install.dismiss"

    const val PROJECT_ID: String = "catalogue.project"
    const val BUILD_ID: String = "catalogue.build"
    const val PACK_ID: String = "catalogue.pack"

    const val SHOW_PROJECT_ID: String = "catalogue.project.show"
    const val HIDE_PROJECT_ID: String = "catalogue.project.hide"
    const val RELOAD_PROJECT_ID: String = "catalogue.project.reload"
    const val LOAD_VERSIONS_ID: String = "catalogue.project.versions.load"
    const val RETRY_VERSIONS_ID: String = "catalogue.project.versions.retry"
    const val INSTALL_PROJECT_ID: String = "catalogue.project.install"
    const val RETRY_INSTALL_ID: String = "catalogue.project.install.retry"
    const val SHOW_BUILD_ID: String = "catalogue.build.show"
    const val SHOW_PACK_ID: String = "catalogue.pack.show"
    const val HIDE_PACK_ID: String = "catalogue.pack.hide"
    const val RETRY_PACK_ID: String = "catalogue.pack.retry"

    // ── App ─────────────────────────────────────────────────────────────

    /** The catalogue's game versions, categories and loaders, or null until they are read. */
    val Tags: SourceKey<CatalogueTags?> = SourceKey(TAGS_ID)

    /** Starts reading [Tags] unless it is read or under way. A read that failed is tried again. */
    val LoadTags: CommandKey<Unit> = CommandKey(LOAD_TAGS_ID)

    /**
     * What the pages visited lately in any window were called, by target key, for a
     * trail of them. Kept for the app, so a crumb in a second window names a page the
     * first one read.
     */
    val Names: SourceKey<Map<String, String>> = SourceKey(NAMES_ID)

    // ── Window ──────────────────────────────────────────────────────────

    /** What Browse is searching in this window. Changed through the browse commands. */
    val Browse: SourceKey<BrowseView> = SourceKey(BROWSE_ID, ScopeLevel.Window)

    /**
     * What this window's search found, kept for the life of the window and of the
     * launcher, so a return to a search finds it as it was left, scrolled where it was.
     * A source that answers from a cache answers at once, and its fresh answer replaces it.
     */
    val Results: SourceKey<SearchResults> = SourceKey(RESULTS_ID, ScopeLevel.Window)

    /** The project or pack the page open in this window describes, for the rail. Null where none is open. */
    val OpenProject: SourceKey<OpenProject?> = SourceKey(OPEN_PROJECT_ID, ScopeLevel.Window)

    /** The install that asks where, while one is open. */
    val InstallChoice: SourceKey<InstallChoiceView?> = SourceKey(INSTALL_CHOICE_ID, ScopeLevel.Window)

    /** Searches this kind, null for packs. */
    val SetKind: CommandKey<ProjectKind?> = CommandKey(SET_KIND_ID, ScopeLevel.Window)

    /** Lists packs from this source, see [PackOrigins]. */
    val SetOrigin: CommandKey<String> = CommandKey(SET_ORIGIN_ID, ScopeLevel.Window)

    /** Searches for these words. A search follows the words a moment after the last change. */
    val SetQuery: CommandKey<String> = CommandKey(SET_QUERY_ID, ScopeLevel.Window)

    val SetSort: CommandKey<BrowseSort> = CommandKey(SET_SORT_ID, ScopeLevel.Window)

    /**
     * Installs into the pack with this id, or into none. Naming the same pack again
     * reads it off disk again, for a screen that shows Browse anew.
     */
    val SetTarget: CommandKey<String?> = CommandKey(SET_TARGET_ID, ScopeLevel.Window)

    /**
     * Opens a browse into the pack with this id: it becomes the target, and once it is
     * read the search moves to a kind it takes.
     */
    val Aim: CommandKey<String> = CommandKey(AIM_ID, ScopeLevel.Window)

    /** Asks for a filter, or drops it when it is already chosen either way. */
    val ToggleFilter: CommandKey<FilterPick> = CommandKey(TOGGLE_FILTER_ID, ScopeLevel.Window)

    /** Rules a filter out, or drops it when it is already ruled out. */
    val ExcludeFilter: CommandKey<FilterPick> = CommandKey(EXCLUDE_FILTER_ID, ScopeLevel.Window)

    val RemoveFilter: CommandKey<FilterPick> = CommandKey(REMOVE_FILTER_ID, ScopeLevel.Window)

    val ClearFilters: CommandKey<FilterClear> = CommandKey(CLEAR_FILTERS_ID, ScopeLevel.Window)

    /** A fresh search narrowed by a tag alone, see [TagSearch]. */
    val SearchFor: CommandKey<TagSearch> = CommandKey(SEARCH_FOR_ID, ScopeLevel.Window)

    /** Takes this field out of the target pack's hands. */
    val Unlock: CommandKey<FilterField> = CommandKey(UNLOCK_ID, ScopeLevel.Window)

    /** Hands this field back to the target pack. */
    val Relock: CommandKey<FilterField> = CommandKey(RELOCK_ID, ScopeLevel.Window)

    val SetHideInstalled: CommandKey<Boolean> = CommandKey(HIDE_INSTALLED_ID, ScopeLevel.Window)

    val SetMirrorOwn: CommandKey<Boolean> = CommandKey(MIRROR_OWN_ID, ScopeLevel.Window)

    val SetMirrorCommunity: CommandKey<Boolean> = CommandKey(MIRROR_COMMUNITY_ID, ScopeLevel.Window)

    /**
     * Turns [BrowseView.hideInstalled] the other way from where it stands when the
     * command runs, for a control that flips it: a value read off the view a frame
     * late turns two quick clicks into the same one twice.
     */
    val ToggleHideInstalled: CommandKey<Unit> = CommandKey(TOGGLE_HIDE_INSTALLED_ID, ScopeLevel.Window)

    /** Turns [BrowseView.mirrorOwn] the other way, see [ToggleHideInstalled]. */
    val ToggleMirrorOwn: CommandKey<Unit> = CommandKey(TOGGLE_MIRROR_OWN_ID, ScopeLevel.Window)

    /** Turns [BrowseView.mirrorCommunity] the other way, see [ToggleHideInstalled]. */
    val ToggleMirrorCommunity: CommandKey<Unit> = CommandKey(TOGGLE_MIRROR_COMMUNITY_ID, ScopeLevel.Window)

    /** Reads the next page of [Results], unless it has none or one is on its way. */
    val MoreResults: CommandKey<Unit> = CommandKey(MORE_RESULTS_ID, ScopeLevel.Window)

    /** Asks the search again from its first page. */
    val RetryResults: CommandKey<Unit> = CommandKey(RETRY_RESULTS_ID, ScopeLevel.Window)

    /**
     * The viewer with this name shows [Results] until it fires [HideResults] with the
     * same name. The search runs only while at least one viewer shows them, so nothing
     * reaches a source or a pack's folders while nobody looks. The first viewer to show
     * them runs the search again, from what was kept and where it was left, and asks
     * the source and the pack behind it. A name is a viewer, so showing twice under one
     * name is one viewer.
     */
    val ShowResults: CommandKey<String> = CommandKey(SHOW_RESULTS_ID, ScopeLevel.Window)

    /** The viewer with this name stopped showing [Results]. With the last one gone the search stops, its results kept. */
    val HideResults: CommandKey<String> = CommandKey(HIDE_RESULTS_ID, ScopeLevel.Window)

    /** Remembers where the person left a list, see [ResultsScroll]. */
    val ScrollResults: CommandKey<ResultsScroll> = CommandKey(SCROLL_RESULTS_ID, ScopeLevel.Window)

    /** Installs the project with this id into the target pack. The outcome is in [Results]. */
    val InstallResult: CommandKey<String> = CommandKey(INSTALL_RESULT_ID, ScopeLevel.Window)

    /** Opens the install that asks where for this project, and reads every pack's answer. */
    val ChooseInstall: CommandKey<InstallSubject> = CommandKey(CHOOSE_INSTALL_ID, ScopeLevel.Window)

    /** Installs the open choice's project into the pack with this id. The outcome is in [InstallChoice]. */
    val InstallInto: CommandKey<String> = CommandKey(INSTALL_INTO_ID, ScopeLevel.Window)

    /** Makes a pack for the open choice's project and installs it there. */
    val InstallNewPack: CommandKey<NewPackRequest> = CommandKey(INSTALL_NEW_PACK_ID, ScopeLevel.Window)

    /** Reads every pack's answer again after a read that failed. */
    val RetryChoice: CommandKey<Unit> = CommandKey(RETRY_CHOICE_ID, ScopeLevel.Window)

    /** Closes the open choice. An install it started goes on to its end. */
    val DismissInstall: CommandKey<Unit> = CommandKey(DISMISS_INSTALL_ID, ScopeLevel.Window)

    // ── Page ────────────────────────────────────────────────────────────

    /** The project page of this page level, or null until one is shown with [ShowProject]. */
    val ProjectPage: SourceKey<ProjectPageView?> = SourceKey(PROJECT_ID, ScopeLevel.Page)

    /** The build page of this page level, or null until one is shown with [ShowBuild]. */
    val BuildPage: SourceKey<BuildPageView?> = SourceKey(BUILD_ID, ScopeLevel.Page)

    /** The pack page of this page level, or null until one is shown with [ShowPack]. */
    val PackPage: SourceKey<PackPageView?> = SourceKey(PACK_ID, ScopeLevel.Page)

    /**
     * Shows a project page for a visit: reads the project and has the window's rail
     * describe it until [HideProject] names the same visit.
     */
    val ShowProject: CommandKey<ProjectVisit> = CommandKey(SHOW_PROJECT_ID, ScopeLevel.Page)

    /** The visit with this name left the screen, and the rail goes back to what it showed before. */
    val HideProject: CommandKey<String> = CommandKey(HIDE_PROJECT_ID, ScopeLevel.Page)

    /** Reads the project page again, after a lookup that did not run, and its build page with it. */
    val ReloadProject: CommandKey<Unit> = CommandKey(RELOAD_PROJECT_ID, ScopeLevel.Page)

    /** Reads every build of the project once per visit, for a tab that lists them. */
    val LoadVersions: CommandKey<Unit> = CommandKey(LOAD_VERSIONS_ID, ScopeLevel.Page)

    /** Reads the builds again after a listing that failed. */
    val RetryVersions: CommandKey<Unit> = CommandKey(RETRY_VERSIONS_ID, ScopeLevel.Page)

    /** Installs into the pack behind the page. The outcome is in [ProjectPage]. */
    val InstallProject: CommandKey<ProjectInstall> = CommandKey(INSTALL_PROJECT_ID, ScopeLevel.Page)

    /** Repeats the last install as it was asked for. */
    val RetryInstall: CommandKey<Unit> = CommandKey(RETRY_INSTALL_ID, ScopeLevel.Page)

    /** Shows one build of the page's project for a visit. The project is shown with [ShowProject] beside it. */
    val ShowBuild: CommandKey<BuildVisit> = CommandKey(SHOW_BUILD_ID, ScopeLevel.Page)

    /** Shows a pack page for a visit, and has the rail describe it until [HidePack] names the visit. */
    val ShowPack: CommandKey<PackVisit> = CommandKey(SHOW_PACK_ID, ScopeLevel.Page)

    val HidePack: CommandKey<String> = CommandKey(HIDE_PACK_ID, ScopeLevel.Page)

    /** Reads the pack again after a read that failed. */
    val RetryPack: CommandKey<Unit> = CommandKey(RETRY_PACK_ID, ScopeLevel.Page)
}
