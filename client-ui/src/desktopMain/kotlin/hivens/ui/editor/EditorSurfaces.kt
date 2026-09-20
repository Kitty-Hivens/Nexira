package hivens.ui.editor

import androidx.compose.runtime.ProvidedValue
import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.widgets.about.LocalAboutContext
import hivens.ui.widgets.about.STUB_ABOUT
import hivens.ui.widgets.bgsettings.LocalBgSettingsContext
import hivens.ui.widgets.bgsettings.STUB_BG_SETTINGS
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.ui.widgets.profile.LocalProfileContext
import hivens.ui.widgets.profile.STUB_PROFILE
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.themepicker.LocalThemePickerContext
import hivens.ui.widgets.themepicker.STUB_THEME_PICKER
import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

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
    val mountedOn: ((Screen) -> Boolean)? = null,
    /**
     * The region widget this surface is the inside of, by kind.
     *
     * A region's own settings and its contents were on different tabs, and the
     * tab carrying the settings was named after the container rather than the
     * region: the right rail's width lived under "the row of regions" while what
     * is in the rail lived under "right rail". So anybody wanting a wider rail
     * had to know that the frame is a row, and that the row is a surface, and
     * which of the three widgets in it is the one they can see.
     *
     * Named here so the region's own tab can offer its settings, which is where
     * a person looks for them. Null for a surface that is nobody's inside.
     */
    val ownerRegion: String? = null,
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
            id        = SurfaceId("home.new"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfHomeNew },
            shortName = { it.editorSurfShortHome },
            stub      = LocalHomeNewContext provides STUB_HOME_NEW,
            mountedOn = { screen -> screen == Screen.Home },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("library"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfLibrary },
            shortName = { it.editorSurfShortLibrary },
            stub      = LocalLibraryContext provides STUB_LIBRARY,
            mountedOn = { screen -> screen == Screen.Library },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("about"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfAbout },
            shortName = { it.editorSurfShortAbout },
            stub      = LocalAboutContext provides STUB_ABOUT,
            mountedOn = { screen -> screen == Screen.About },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("bg.settings"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfBg },
            shortName = { it.editorSurfShortBg },
            stub      = LocalBgSettingsContext provides STUB_BG_SETTINGS,
            mountedOn = { screen -> screen == Screen.BackgroundSettings },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("profile"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfProfile },
            shortName = { it.editorSurfShortProfile },
            stub      = LocalProfileContext provides STUB_PROFILE,
            mountedOn = { screen -> screen == Screen.Profile },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("theme.picker"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfTheme },
            shortName = { it.editorSurfShortTheme },
            stub      = LocalThemePickerContext provides STUB_THEME_PICKER,
            mountedOn = { screen -> screen == Screen.ThemePicker },
            ownerRegion = "appshell.region.center",
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
            ownerRegion = "appshell.region.top",
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
            ownerRegion = "appshell.region.left",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.rightrail"),
            icon      = NxIcon.ViewQuilt,
            name      = { it.editorSurfRightRail },
            shortName = { it.editorSurfShortRightRail },
            stub      = LocalRightRailContext provides STUB_RIGHTRAIL,
            ownerRegion = "appshell.region.right",
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
    fun availableFor(screen: Screen, graph: LayoutGraph): List<SurfaceId> {
        val known = graph.surfaces.keys
        val main = centre.firstOrNull { it.mountedOn?.invoke(screen) == true }
        return (listOfNotNull(main) + shell).map { it.id }.filter { it in known }
    }

    /**
     * Whether this surface is folded away rather than on screen.
     *
     * A collapsed rail is not a place to arrange anything: its drop targets are a
     * hairline and the widgets said to be in there are not on screen to be
     * dragged. It is still the only place its own width, plane and the collapse
     * itself can be reached from, so the answer marks the tab rather than
     * removing it. A tab that disappears when a rail folds takes the way back
     * with it.
     *
     * The stored prop is the whole question. The right rail also folds itself away
     * below a window width it cannot lay out in, but only outside the editor: edit
     * mode renders it at its full width whatever the window measures, so counting
     * that fold here marked a tab folded while the rail it names was on screen.
     */
    fun foldedAway(surface: SurfaceId, graph: LayoutGraph): Boolean = when (surface.value) {
        "appshell.rightrail" -> graph.regionCollapsed("appshell.region.right")
        "appshell.leftrail" -> graph.regionCollapsed("appshell.region.left")
        else -> false
    }

    /**
     * Where the region widget that owns [surface] lives, so its own settings can
     * be opened from the tab of the thing it contains.
     *
     * Two frames hold all five regions and the model does not say which, so both
     * are searched rather than mapped: a table saying "the top bar is in the root
     * and the rails are in the body" is a fourth place the frame's shape is
     * written down, and the one that would be wrong after it changed.
     *
     * Null when the surface is nobody's inside, or when the frame does not carry
     * the region this build expects.
     */
    fun ownerRegionOf(surface: SurfaceId, graph: LayoutGraph): Pair<SlotPath, String>? {
        val kind = spec(surface)?.ownerRegion ?: return null
        return FRAMES.firstNotNullOfOrNull { (frame, slot) ->
            val path = SlotPath(frame, slot)
            graph.surfaces[frame]
                ?.slotsOf(FamilyId.GENERAL)
                ?.get(slot)
                ?.widgets
                ?.firstOrNull { it.kind.value == kind }
                ?.let { path to it.instanceId }
        }
    }

    /** The two slots the shell's regions live in: the window's column and its row. */
    private val FRAMES = listOf(
        SurfaceId("appshell.root") to SlotId("regions"),
        SurfaceId("appshell.body") to SlotId("content"),
    )

    /**
     * The collapse prop on a shell region, false when the region or the prop is
     * absent.
     *
     * Both frames are searched, for the reason [ownerRegionOf] searches both: the
     * model does not say which frame holds a region, and a region moved to the
     * other one read as permanently unfolded while its own settings chip went on
     * resolving.
     */
    private fun LayoutGraph.regionCollapsed(kind: String): Boolean =
        FRAMES.firstNotNullOfOrNull { (frame, slot) ->
            surfaces[frame]
                ?.slotsOf(FamilyId.GENERAL)
                ?.get(slot)
                ?.widgets
                ?.firstOrNull { it.kind.value == kind }
                ?.props
                ?.get("collapsed")
                ?.jsonPrimitive
                ?.booleanOrNull
        } ?: false
}
