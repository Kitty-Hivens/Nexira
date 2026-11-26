package hivens.widget.model

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The standard document holds what the bundled layout draws, config by config, for as long as both ship.
 *
 * [generated] is the mapping written down. The frame stands in every config of the launcher: the top bar, then the
 * body, a row of the left rail, the centre and the right rail. The regions are singles, kept once, each holding what
 * its surface held, and every config holds the body's row as its own, so that the row can hold what is the config's.
 * The centre holds each config's screen as that config's own, and a screen holds the slots its surface had. The right
 * rail is one record holding what each of its families held, each widget shown on the configs its family was shown on,
 * the way a bar's widget follows what the compositor shows.
 *
 * Set NEXIRA_WRITE_STANDARD_DOCUMENT=1 to write [generated] over the resource instead of comparing.
 */
class StandardDocumentLayoutTest {

    private val graph = DefaultLayout.load()

    /** One config of the launcher: the surface its screen drew, with the slots in order, and the rail's family on it. */
    private data class Screen(val config: String, val surface: String? = null, val slots: List<String> = emptyList(), val rail: FamilyId? = null)

    private val screens = listOf(
        Screen("home", "home.new", listOf("main")),
        Screen("library", "library", listOf("header", "body")),
        Screen("browse", "browse", listOf("header", "body"), rail = BROWSE),
        Screen("profile", "profile", listOf("nav", "skin", "account", "signin")),
        Screen("wardrobe"),
        Screen("settings", "settings.appearance", listOf("theme.link", "theme.daynight")),
        Screen("theme.picker", "theme.picker", listOf("header", "grid", "preview")),
        Screen("about", "about", listOf("left", "right")),
        Screen("background", "bg.settings", listOf("controls", "theme", "theme.link", "motion")),
        Screen("pack"),
        Screen("pack.versions"),
        Screen("catalogue.pack", "catalogue.pack", listOf("header", "body"), rail = PROJECT_VIEW),
        Screen("project", "project", listOf("header", "body"), rail = PROJECT_VIEW),
        Screen("project.build", rail = PROJECT_VIEW),
    )

    /** The slots each family other than the general one drew in the rail, in the order they stacked. */
    private val railFamilies = mapOf(PROJECT_VIEW to listOf("modData", "authorData"), BROWSE to listOf("controls"))

    private fun slot(surface: String, slot: String, family: FamilyId = FamilyId.GENERAL): SlotContent =
        checkNotNull(graph.surfaces[SurfaceId(surface)]?.slotsOf(family)?.get(SlotId(slot))) { "$surface/${family.value}:$slot" }

    private val generated: Document by lazy {
        val top = slot("appshell.root", "regions").widgets.single { it.kind.value == "appshell.region.top" }.copy(
            children = mapOf(
                SlotId("left") to slot("appshell.topbar", "left"),
                SlotId("center") to slot("appshell.topbar", "center"),
                SlotId("right") to slot("appshell.topbar", "right"),
            ),
        )
        val regions = slot("appshell.body", "content").widgets.associateBy { it.kind.value }
        val left = regions.getValue("appshell.region.left").copy(
            children = mapOf(SlotId("top") to slot("appshell.leftrail", "top"), SlotId("bottom") to slot("appshell.leftrail", "bottom")),
        )
        val center = regions.getValue("appshell.region.center").copy(
            placement = null,
            children = mapOf(
                SlotId("layers") to slot("appshell.backdrop", "layers"),
                SlotId("body") to SlotContent(),
                SlotId("bottom") to slot("appshell.overlay", "bottom"),
            ),
        )
        val right = regions.getValue("appshell.region.right").copy(children = rail())
        val centerPlacement = regions.getValue("appshell.region.center").placement
        val bodyStanding = slot("appshell.root", "regions").widgets.single { it.kind.value == "appshell.region.body" }
        val row = { center: WidgetInstance, right: WidgetInstance -> SlotContent(flow = FlowSpec.Row, widgets = listOf(stands(left), center, right)) }
        val body = bodyStanding.copy(placement = null, children = mapOf(SlotId("content") to row(stands(center, centerPlacement), stands(right))))
        val singles = listOf(top, body, left, center, right).associateBy { it.kind }
        val launcher = Composition(
            start = ConfigId("home"),
            frame = StandardDocument.load().compositions.getValue(LAUNCHER).frame,
            singles = singles,
            configs = screens.associate { screen ->
                val config = ConfigId(screen.config)
                val shown = WidgetInstance(
                    kind = WidgetKind("screen.${screen.config}"),
                    instanceId = "screen.${screen.config}",
                    children = screen.slots.associate { SlotId(it) to slot(screen.surface!!, it) },
                )
                val own = row(
                    stands(center, centerPlacement, mapOf(SlotId("body") to SlotContent(listOf(shown)))),
                    stands(right),
                )
                val root = SlotContent(widgets = listOf(stands(top), stands(body, bodyStanding.placement, mapOf(SlotId("content") to own))))
                config to Config(layouts = listOf(ConfigLayout(MAIN, root)))
            },
        )
        val console = StandardDocument.load().compositions.getValue(CONSOLE).copy(
            configs = mapOf(
                ConfigId("console") to Config(
                    layouts = listOf(ConfigLayout(MAIN, SlotContent(listOf(WidgetInstance(WidgetKind("screen.console"), "screen.console"))))),
                ),
            ),
        )
        Document(modules = DefaultLayout.modules(), start = LAUNCHER, compositions = mapOf(LAUNCHER to launcher, CONSOLE to console))
    }

    /** The configs each family other than the general one was shown on. */
    private val familyConfigs: Map<FamilyId, List<ConfigId>> =
        screens.filter { it.rail != null }.groupBy({ it.rail!! }, { ConfigId(it.config) })

    /**
     * The rail's slots: one column that scrolls without a bar, holding the feed where no other family was shown, taking
     * the column's height, then each family's blocks where that family was shown, held off the rail's edges and apart
     * from each other by their own room, and under it the message history where the feed is.
     */
    private fun rail(): Map<SlotId, SlotContent> {
        val elsewhere = familyConfigs.values.flatten()
        val feed = slot("appshell.rightrail", "news").widgets.map { it.copy(hiddenOn = elsewhere, placement = Placement(weight = 1f)) }
        val blocks = familyConfigs.flatMap { (family, configs) ->
            val widgets = railFamilies.getValue(family).flatMap { slot("appshell.rightrail", it, family).widgets }
            widgets.mapIndexed { i, block ->
                val last = i == widgets.lastIndex
                block.copy(
                    shownOn = configs,
                    placement = Placement(padding = SurfaceInsets(start = RAIL_GUTTER, end = RAIL_GUTTER, top = RAIL_GUTTER, bottom = if (last) RAIL_GUTTER else null)),
                )
            }
        }
        val history = slot("appshell.rightrail", "bottom")
        return mapOf(
            SlotId("main") to SlotContent(widgets = feed + blocks, viewport = ViewportSpec(kind = ViewportSpec.SCROLL, scrollbar = false)),
            SlotId("bottom") to history.copy(widgets = history.widgets.map { it.copy(hiddenOn = elsewhere) }),
        )
    }

    private fun stands(record: WidgetInstance, placement: Placement? = record.placement, own: Map<SlotId, SlotContent> = emptyMap()) =
        WidgetInstance(kind = record.kind, instanceId = record.instanceId, placement = placement, children = own)

    @Test
    fun `the standard document is the bundled layout written out`() {
        if (System.getenv("NEXIRA_WRITE_STANDARD_DOCUMENT") == "1") {
            File("src/jvmSharedMain/resources/widget/standard-document.json").writeText(DocumentCodec.write(generated) + "\n")
        }
        assertEquals(generated, StandardDocument.load())
    }

    @Test
    fun `every config draws the widgets the bundled layout drew for it, in each place in the same order`() {
        val document = StandardDocument.load()
        val shell = listOf(
            "appshell.topbar" to listOf("left", "center", "right"),
            "appshell.leftrail" to listOf("top", "bottom"),
            "appshell.backdrop" to listOf("layers"),
            "appshell.overlay" to listOf("bottom"),
        )
        for (screen in screens) {
            val drawn = document.drawn(CompositionName("launcher"), ConfigId(screen.config))
            val rail = screen.rail?.let { family -> railFamilies.getValue(family).map { "appshell.rightrail" to it to family } }
                ?: listOf("appshell.rightrail" to "news" to FamilyId.GENERAL, "appshell.rightrail" to "bottom" to FamilyId.GENERAL)
            val places = shell.flatMap { (surface, slots) -> slots.map { surface to it to FamilyId.GENERAL } } +
                rail + screen.slots.map { screen.surface!! to it to FamilyId.GENERAL }
            val expected = places.flatMap { (at, family) -> slot(at.first, at.second, family).widgets.map { it.kind to it.props } }
            val got = drawn.filter { it.kind.value !in FRAME && !it.kind.value.startsWith("screen.") }.map { it.kind to it.props }
            assertEquals(expected.sortedBy { it.toString() }, got.sortedBy { it.toString() }, screen.config)
            assertTrue(drawn.any { it.kind.value == "screen.${screen.config}" }, "${screen.config} draws its screen")
        }
        assertEquals(
            listOf("screen.console"),
            document.drawn(CompositionName("console"), ConfigId("console")).map { it.kind.value },
        )
    }

    @Test
    fun `a screen's slot and a rail's slot keep the order and the shape they had`() {
        val document = StandardDocument.load()
        for (screen in screens.filter { it.surface != null }) {
            val body = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId(screen.config), MAIN))
                .child(BODY, SlotId("content"))
                .child("appshell-region-center-default", SlotId("body"))
            for (name in screen.slots) {
                val held = assertNotNull(document.contentAt(body.child("screen.${screen.config}", SlotId(name))), "${screen.config}:$name")
                assertEquals(slot(screen.surface!!, name), held, "${screen.config}:$name")
            }
        }
        val home = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("home"), MAIN)).child(BODY, SlotId("content"))
        val railOnHome = assertNotNull(document.contentAt(home.child("appshell-region-right-default", SlotId("main"))))
        assertEquals(slot("appshell.rightrail", "news").widgets.map { it.kind }, railOnHome.widgets.filter { it.shownOn(ConfigId("home")) }.map { it.kind })
        val project = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("project"), MAIN)).child(BODY, SlotId("content"))
        val railOnProject = assertNotNull(document.contentAt(project.child("appshell-region-right-default", SlotId("main"))))
        assertEquals(
            listOf("modData", "authorData").flatMap { slot("appshell.rightrail", it, PROJECT_VIEW).widgets.map { w -> w.kind } },
            railOnProject.widgets.filter { it.shownOn(ConfigId("project")) }.map { it.kind },
        )
        assertEquals(railOnHome, railOnProject, "one rail, the same record on every config")
        assertTrue(!document.heldOwnAnywhere(project.child("appshell-region-right-default", SlotId("main"))))
    }

    @Test
    fun `every widget of a composition goes by a name of its own`() {
        val document = StandardDocument.load()
        assertSame(document, document.withOwnNames { error("$it is said twice") })
    }

    /** Every widget a config draws, singles as they stand there, depth first. */
    private fun Document.drawn(composition: CompositionName, config: ConfigId): List<WidgetInstance> {
        fun walk(path: NodePath): List<WidgetInstance> = contentAt(path)?.widgets.orEmpty().filter { it.shownOn(config) }.flatMap { w ->
            listOf(w) + w.children.keys.flatMap { walk(path.child(w.instanceId, it)) }
        }
        return walk(NodePath(composition, NodeRoot.Layout(config, MAIN)))
    }

    private companion object {
        val LAUNCHER = CompositionName("launcher")
        val CONSOLE = CompositionName("console")
        val MAIN = LayoutId("main")
        val PROJECT_VIEW = FamilyId("projectView")
        val BROWSE = FamilyId("browse")
        const val RAIL_GUTTER = 12f
        const val BODY = "appshell-region-body-default"

        /** What the frame is made of, which the bundled layout drew around every screen and the comparison leaves out. */
        val FRAME = setOf("appshell.region.top", "appshell.region.body", "appshell.region.left", "appshell.region.center", "appshell.region.right")
    }
}
