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
 * [generated] is the mapping written down. The frame stands in every config of the launcher: the top bar, then a row
 * of the left rail, the centre and the right rail. The four regions are singles, kept once, each holding what its
 * surface held. The centre holds each config's screen as that config's own, and a screen holds the slots its surface
 * had. Where the right rail drew another family on a config, that config holds the rail's slots as its own.
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
        val right = regions.getValue("appshell.region.right").copy(
            children = mapOf(SlotId("main") to slot("appshell.rightrail", "news"), SlotId("bottom") to slot("appshell.rightrail", "bottom")),
        )
        val singles = listOf(top, left, center, right).associateBy { it.kind }
        val body = slot("appshell.root", "regions").widgets.single { it.kind.value == "appshell.region.body" }
        val centerPlacement = regions.getValue("appshell.region.center").placement
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
                val row = SlotContent(
                    flow = FlowSpec.Row,
                    widgets = listOf(
                        stands(left),
                        stands(center, centerPlacement, mapOf(SlotId("body") to SlotContent(listOf(shown)))),
                        stands(right, own = screen.rail?.let { railOf(it, screen.config) }.orEmpty()),
                    ),
                )
                val root = SlotContent(
                    widgets = listOf(
                        stands(top),
                        body.copy(instanceId = "appshell-region-body-${screen.config}", children = mapOf(SlotId("content") to row)),
                    ),
                )
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

    /**
     * The rail's slots as [config] holds them for [family]: its blocks in one column that scrolls, each under a name of
     * the config's, held off the rail's edges and apart from each other by its own room, and nothing pinned below.
     */
    private fun railOf(family: FamilyId, config: String): Map<SlotId, SlotContent> {
        val blocks = railFamilies.getValue(family).flatMap { slot("appshell.rightrail", it, family).widgets }
        val placed = blocks.mapIndexed { i, block ->
            val last = i == blocks.lastIndex
            block.copy(
                instanceId = "${block.instanceId.removeSuffix("-default")}-$config",
                placement = Placement(padding = SurfaceInsets(start = RAIL_GUTTER, end = RAIL_GUTTER, top = RAIL_GUTTER, bottom = if (last) RAIL_GUTTER else null)),
            )
        }
        return mapOf(
            SlotId("main") to SlotContent(widgets = placed, viewport = ViewportSpec(kind = ViewportSpec.SCROLL, scrollbar = false)),
            SlotId("bottom") to SlotContent(),
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
                .child("appshell-region-body-${screen.config}", SlotId("content"))
                .child("appshell-region-center-default", SlotId("body"))
            for (name in screen.slots) {
                val held = assertNotNull(document.contentAt(body.child("screen.${screen.config}", SlotId(name))), "${screen.config}:$name")
                assertEquals(slot(screen.surface!!, name), held, "${screen.config}:$name")
            }
        }
        val home = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("home"), MAIN)).child("appshell-region-body-home", SlotId("content"))
        assertEquals(slot("appshell.rightrail", "news").widgets, document.contentAt(home.child("appshell-region-right-default", SlotId("main")))?.widgets)
        val project = NodePath(LAUNCHER, NodeRoot.Layout(ConfigId("project"), MAIN)).child("appshell-region-body-project", SlotId("content"))
        val blocks = assertNotNull(document.contentAt(project.child("appshell-region-right-default", SlotId("main"))))
        assertEquals(listOf("modData", "authorData").flatMap { slot("appshell.rightrail", it, PROJECT_VIEW).widgets.map { w -> w.kind } }, blocks.widgets.map { it.kind })
        assertTrue(document.holdsOwn(project.child("appshell-region-right-default", SlotId("main"))))
    }

    @Test
    fun `every widget of a composition goes by a name of its own`() {
        val document = StandardDocument.load()
        assertSame(document, document.withOwnNames { error("$it is said twice") })
    }

    /** Every widget a config draws, singles as they stand there, depth first. */
    private fun Document.drawn(composition: CompositionName, config: ConfigId): List<WidgetInstance> {
        fun walk(path: NodePath): List<WidgetInstance> = contentAt(path)?.widgets.orEmpty().flatMap { w ->
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

        /** What the frame is made of, which the bundled layout drew around every screen and the comparison leaves out. */
        val FRAME = setOf("appshell.region.top", "appshell.region.body", "appshell.region.left", "appshell.region.center", "appshell.region.right")
    }
}
