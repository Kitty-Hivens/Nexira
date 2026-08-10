package hivens.ui.editor

import androidx.compose.runtime.ProvidedValue
import hivens.core.data.HomeView
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.widgets.about.LocalAboutContext
import hivens.ui.widgets.about.STUB_ABOUT
import hivens.ui.widgets.bgsettings.LocalBgSettingsContext
import hivens.ui.widgets.bgsettings.STUB_BG_SETTINGS
import hivens.ui.widgets.home.classic.LocalHomeClassicContext
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.ui.widgets.profile.LocalProfileContext
import hivens.ui.widgets.profile.STUB_PROFILE
import hivens.ui.widgets.serverdetails.LocalServerDetailsContext
import hivens.ui.widgets.serverdetails.STUB_SERVER_DETAILS
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.themepicker.LocalThemePickerContext
import hivens.ui.widgets.themepicker.STUB_THEME_PICKER
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SurfaceId

/**
 * What the editor knows about one surface.
 *
 * Everything the host used to answer with a `when` of its own -- which screen
 * mounts it, its icon, its two names, whether it carries surface settings, and
 * the stand-in context its widgets read when dragged somewhere foreign. Six
 * disjoint spots that had to move together; a surface added to five of them
 * looked fine and behaved wrong in the sixth.
 *
 * [stub] is the reason the set had to become open. It was a fixed wall of
 * providers in the host, so a new surface with a context of its own fell
 * through it silently -- no missing branch, no compile error, just widgets
 * reading a local nobody provided.
 */
internal class EditorSurfaceSpec(
    val id: SurfaceId,
    val icon: IconKey,
    val name: (AppStrings) -> String,
    val shortName: (AppStrings) -> String,
    /** True when the surface exposes its own settings panel beside the widget props. */
    val hasSettings: Boolean = false,
    /** The no-op context a foreign-dropped widget falls through to, if this surface has one. */
    val stub: ProvidedValue<*>? = null,
    /**
     * Whether this screen mounts the surface as its centre pane. Null for the
     * shell surfaces, which are present on every screen.
     */
    val mountedOn: ((Screen, HomeView) -> Boolean)? = null,
)

/**
 * The editor's surface registry: adding a surface is an entry here rather than
 * surgery across the host.
 *
 * Order is display order in the surface picker. The centre surface for the
 * current screen comes first so it stays the default selection, then the shell
 * ones in their established order.
 */
internal object EditorSurfaces {

    private val centre: List<EditorSurfaceSpec> = listOf(
        EditorSurfaceSpec(
            id        = SurfaceId("home.classic"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfHomeClassic },
            shortName = { it.editorSurfShortHome },
            stub      = LocalHomeClassicContext provides STUB_HOME_CLASSIC,
            mountedOn = { screen, view -> screen == Screen.Home && view == HomeView.Classic },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("home.new"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfHomeNew },
            shortName = { it.editorSurfShortHome },
            stub      = LocalHomeNewContext provides STUB_HOME_NEW,
            mountedOn = { screen, view -> screen == Screen.Home && view == HomeView.New },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("library"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfLibrary },
            shortName = { it.editorSurfShortLibrary },
            stub      = LocalLibraryContext provides STUB_LIBRARY,
            // Two ways in: the Library screen, and Home configured to open on it.
            mountedOn = { screen, view ->
                screen == Screen.Library || (screen == Screen.Home && view == HomeView.LibraryFirst)
            },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("about"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfAbout },
            shortName = { it.editorSurfShortAbout },
            stub      = LocalAboutContext provides STUB_ABOUT,
            mountedOn = { screen, _ -> screen == Screen.About },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("bg.settings"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfBg },
            shortName = { it.editorSurfShortBg },
            stub      = LocalBgSettingsContext provides STUB_BG_SETTINGS,
            mountedOn = { screen, _ -> screen == Screen.BackgroundSettings },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("profile"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfProfile },
            shortName = { it.editorSurfShortProfile },
            stub      = LocalProfileContext provides STUB_PROFILE,
            mountedOn = { screen, _ -> screen == Screen.Profile },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("server.details"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfServer },
            shortName = { it.editorSurfShortServer },
            stub      = LocalServerDetailsContext provides STUB_SERVER_DETAILS,
            mountedOn = { screen, _ -> screen is Screen.ServerDetails },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("theme.picker"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfTheme },
            shortName = { it.editorSurfShortTheme },
            stub      = LocalThemePickerContext provides STUB_THEME_PICKER,
            mountedOn = { screen, _ -> screen == Screen.ThemePicker },
        ),
    )

    // Always editable: the shell frames every screen, so its surfaces are
    // reachable even where the centre is not a widget surface yet.
    private val shell: List<EditorSurfaceSpec> = listOf(
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.topbar"),
            icon      = NxIcon.Layers,
            name      = { it.editorSurfTopBar },
            shortName = { it.editorSurfShortTopBar },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.overlay"),
            icon      = NxIcon.Layers,
            name      = { it.editorSurfOverlay },
            shortName = { it.editorSurfShortOverlay },
        ),
        EditorSurfaceSpec(
            id          = SurfaceId("appshell.leftrail"),
            icon        = NxIcon.ViewSidebar,
            name        = { it.editorSurfLeftRail },
            shortName   = { it.editorSurfShortLeftRail },
            hasSettings = true,
            stub        = LocalLeftRailContext provides STUB_LEFTRAIL,
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.rightrail"),
            icon      = NxIcon.ViewQuilt,
            name      = { it.editorSurfRightRail },
            shortName = { it.editorSurfShortRightRail },
            stub      = LocalRightRailContext provides STUB_RIGHTRAIL,
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.body"),
            icon      = NxIcon.ViewQuilt,
            name      = { it.editorSurfBody },
            shortName = { it.editorSurfShortBody },
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.root"),
            icon      = NxIcon.Dashboard,
            name      = { it.editorSurfShell },
            shortName = { it.editorSurfShortShell },
        ),
    )

    val all: List<EditorSurfaceSpec> = centre + shell

    private val byId: Map<SurfaceId, EditorSurfaceSpec> = all.associateBy { it.id }

    fun spec(id: SurfaceId): EditorSurfaceSpec? = byId[id]

    /**
     * Every no-op context the editor stands in for, in one array the host
     * spreads into its provider. Derived from the registry, so a surface that
     * declares a stub gets it mounted by existing.
     */
    val stubs: Array<ProvidedValue<*>> = all.mapNotNull { it.stub }.toTypedArray()

    /**
     * The surfaces editable on this screen: the centre one this screen mounts,
     * then the shell.
     *
     * Intersected with [graph] because a surface with no entry there has no
     * slots to arrange -- selecting it would open an editor over nothing. The
     * bundled layout carries all of them and reconcile seeds what a saved graph
     * is missing, so in practice this only filters a surface that genuinely is
     * not part of this build's layout.
     */
    fun availableFor(screen: Screen, homeView: HomeView, graph: LayoutGraph): List<SurfaceId> {
        val known = graph.surfaces.keys
        val main = centre.firstOrNull { it.mountedOn?.invoke(screen, homeView) == true }
        return (listOfNotNull(main) + shell)
            .map { it.id }
            .filter { it in known }
    }
}
