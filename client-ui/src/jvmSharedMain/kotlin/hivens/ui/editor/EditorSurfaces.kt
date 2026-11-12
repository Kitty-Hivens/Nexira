package hivens.ui.editor

import hivens.ui.Screen
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.feature.catalogue.browse.CataloguePackPageSource
import hivens.ui.feature.catalogue.project.ProjectPageSource
import hivens.ui.screens.custom.ScreenIcons
import hivens.ui.widgets.about.AboutUpdateSource
import hivens.ui.widgets.about.OpenUpdateDialog
import hivens.ui.widgets.themepicker.ThemeSelectionSource
import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScreenSpec
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.screen
import hivens.widget.model.screenOn
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the editor knows about one surface.
 *
 * Everything the host used to answer with a `when` of its own -- which screen
 * mounts it, its icon, its two names, whether it carries surface settings, and
 * what its page hands its widgets. Disjoint spots that had to move together, and a
 * surface added to all but one of them looked fine and behaved wrong in the last.
 */
internal class EditorSurfaceSpec(
    val id: SurfaceId,
    val icon: IconKey,
    val name: (AppStrings) -> String,
    val shortName: (AppStrings) -> String,
    /** True when the surface exposes its own settings panel beside the widget props. */
    val hasSettings: Boolean = false,
    /**
     * The page-level keys the screen mounting this surface provides for its own
     * widgets, by id: the ones no factory makes, so a page has them only where its
     * owner put them. See [WidgetAdmission].
     */
    val provides: Set<String> = emptySet(),
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
            mountedOn = { screen -> screen == Screen.Home },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("library"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfLibrary },
            shortName = { it.editorSurfShortLibrary },
            mountedOn = { screen -> screen == Screen.Library },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("browse"),
            icon      = NxIcon.Search,
            name      = { it.editorSurfBrowse },
            shortName = { it.editorSurfShortBrowse },
            mountedOn = { screen -> screen == Screen.Browse || screen is Screen.BrowseInto },
            ownerRegion = "appshell.region.center",
        ),
        // Both pages give their widgets the page they are drawn for, per visit and
        // nowhere else.
        EditorSurfaceSpec(
            id        = SurfaceId("project"),
            icon      = NxIcon.Description,
            name      = { it.editorSurfProject },
            shortName = { it.editorSurfShortProject },
            provides  = setOf(ProjectPageSource.id),
            mountedOn = { screen -> screen is Screen.ModDetail },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("catalogue.pack"),
            icon      = NxIcon.Inventory2,
            name      = { it.editorSurfCataloguePack },
            shortName = { it.editorSurfShortCataloguePack },
            provides  = setOf(CataloguePackPageSource.id),
            mountedOn = { screen -> screen is Screen.CataloguePackDetail },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("about"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfAbout },
            shortName = { it.editorSurfShortAbout },
            provides  = setOf(AboutUpdateSource.id, OpenUpdateDialog.id),
            mountedOn = { screen -> screen == Screen.About },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("bg.settings"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfBg },
            shortName = { it.editorSurfShortBg },
            mountedOn = { screen -> screen == Screen.BackgroundSettings },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("profile"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfProfile },
            shortName = { it.editorSurfShortProfile },
            mountedOn = { screen -> screen == Screen.Profile },
            ownerRegion = "appshell.region.center",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("theme.picker"),
            icon      = NxIcon.Home,
            name      = { it.editorSurfTheme },
            shortName = { it.editorSurfShortTheme },
            provides  = setOf(ThemeSelectionSource.id),
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
            id        = SurfaceId("appshell.backdrop"),
            icon      = NxIcon.Wallpaper,
            name      = { it.editorSurfBackdrop },
            shortName = { it.editorSurfShortBackdrop },
        ),
        EditorSurfaceSpec(
            id          = SurfaceId("appshell.leftrail"),
            icon        = NxIcon.ViewSidebar,
            name        = { it.editorSurfLeftRail },
            shortName   = { it.editorSurfShortLeftRail },
            hasSettings = true,
            ownerRegion = "appshell.region.left",
        ),
        EditorSurfaceSpec(
            id        = SurfaceId("appshell.rightrail"),
            icon      = NxIcon.ViewQuilt,
            name      = { it.editorSurfRightRail },
            shortName = { it.editorSurfShortRightRail },
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

    /** Whether [id] is one of the shell's own surfaces, the ones every screen has. */
    fun isShell(id: SurfaceId): Boolean = shell.any { it.id == id }

    private val byId: Map<SurfaceId, EditorSurfaceSpec> = all.associateBy { it.id }

    fun spec(id: SurfaceId): EditorSurfaceSpec? = byId[id]

    /**
     * The spec for [id], screens somebody made included.
     *
     * Those are in the graph and nowhere else, so a lookup that only asked the
     * compiled-in list found nothing for them, and the editor drew a made screen's
     * tab with its raw surface id and no way to its settings.
     */
    fun specIn(id: SurfaceId, graph: LayoutGraph): EditorSurfaceSpec? =
        byId[id] ?: graph.screenOn(id)?.let(::madeScreen)

    /**
     * What the editor knows about a made screen, read off its record.
     *
     * Settings, because a made screen has its own: its name, its icon, and the way
     * to delete it. It provides nothing of its own to its page: whatever its widgets
     * read, the composition provides or a page makes for itself.
     */
    private fun madeScreen(spec: ScreenSpec): EditorSurfaceSpec = EditorSurfaceSpec(
        id          = spec.surface,
        icon        = ScreenIcons.of(spec.icon),
        name        = { s -> spec.title.ifBlank { s.screenUntitled } },
        shortName   = { s -> spec.title.ifBlank { s.screenUntitled } },
        hasSettings = true,
        mountedOn   = { screen -> screen == Screen.Custom(spec.id) },
        ownerRegion = "appshell.region.center",
    )

    /**
     * The page-level keys a widget finds on [surface], or null where the surface is
     * not drawn on a page at all.
     *
     * A screen's own surface sits in the page the router made for the screen, so it
     * has every key a page makes for itself and the ones its screen hands it. A shell
     * surface is drawn once for the whole composition, outside any page, and so is a
     * surface this build does not know: refusing there is the answer that never puts
     * a widget where it then draws the placeholder.
     */
    fun pageProvides(surface: SurfaceId, graph: LayoutGraph): Set<String>? {
        if (isShell(surface)) return null
        val spec = specIn(surface, graph) ?: return null
        return if (spec.mountedOn == null) null else spec.provides
    }

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
        val main = when (screen) {
            // A made screen's surface is named by its record, which is in the graph.
            is Screen.Custom -> graph.screen(screen.id)?.let(::madeScreen)
            else -> centre.firstOrNull { it.mountedOn?.invoke(screen) == true }
        }
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
        val kind = specIn(surface, graph)?.ownerRegion ?: return null
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
