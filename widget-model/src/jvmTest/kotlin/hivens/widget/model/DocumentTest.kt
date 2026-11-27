package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A document: which layout a config draws, what an edit of one config does to the others, and what becomes of a file.
 */
class DocumentTest {

    private val launcher = CompositionName("launcher")
    private val home = ConfigId("home")
    private val library = ConfigId("library")
    private val main = LayoutId("main")
    private val rail = WidgetKind("rail")

    private fun node(kind: String, id: String = kind, vararg children: Pair<String, List<WidgetInstance>>) =
        WidgetInstance(WidgetKind(kind), id, children = children.associate { (slot, of) -> SlotId(slot) to SlotContent(of) })

    private fun standing(x: Float = 0f) = WidgetInstance(rail, "rail", placement = Placement(x = x))

    private fun props(value: String) = JsonObject(mapOf("title" to JsonPrimitive(value)))

    private fun config(vararg widgets: WidgetInstance) = Config(layouts = listOf(ConfigLayout(main, SlotContent(widgets.toList()))))

    private val document = Document(
        modules = listOf("packs"),
        start = launcher,
        compositions = mapOf(
            launcher to Composition(
                start = home,
                configs = mapOf(
                    home to config(standing(1f), node("news"), node("box", "box", "body" to listOf(node("clock")))),
                    library to config(standing(2f), node("packs")),
                ),
                singles = mapOf(rail to node("rail", "rail", "top" to listOf(node("link", "link1")))),
            ),
        ),
    )

    private fun root(config: ConfigId) = NodePath(launcher, NodeRoot.Layout(config, main))
    private fun Document.launcher() = compositions.getValue(launcher)
    private fun Document.at(path: NodePath) = assertNotNull(contentAt(path))
    private fun Document.ids(path: NodePath) = at(path).widgets.map { it.instanceId }

    // Which layout

    @Test
    fun `a config draws the widest layout the size holds, then the tallest, and the smallest where none fits`() {
        fun layout(id: String, w: Float, h: Float = 0f) = ConfigLayout(LayoutId(id), minWidth = w, minHeight = h)
        val config = Config(layouts = listOf(layout("wide", 1600f), layout("phone", 300f), layout("tall", 900f, 1000f), layout("desk", 900f)))
        fun drawn(w: Float, h: Float) = config.layoutFor(w, h)?.id?.value
        assertEquals("desk", drawn(1000f, 700f))
        assertEquals("tall", drawn(1000f, 1200f))
        assertEquals("wide", drawn(2560f, 1440f))
        assertEquals("phone", drawn(400f, 800f))
        assertEquals("phone", drawn(200f, 800f), "something is drawn in a window smaller than any of them")
        assertNull(Config().layoutFor(1000f, 700f))
    }

    @Test
    fun `a start that names nothing falls to the first there is`() {
        assertSame(document.launcher(), document.copy(start = CompositionName("gone")).startComposition)
        assertSame(document.launcher().configs[home], document.launcher().copy(start = ConfigId("gone")).startConfig)
        assertNull(Document(start = launcher).startComposition)
    }

    // Singles

    @Test
    fun `a single stands in every config as the one it is, each placing it where it likes`() {
        val atHome = document.at(root(home)).widgets.first()
        val atLibrary = document.at(root(library)).widgets.first()
        assertEquals(listOf(1f, 2f), listOf(atHome, atLibrary).map { it.placement?.x })
        assertEquals(atHome.copy(placement = null), atLibrary.copy(placement = null))
        assertEquals(setOf(SlotId("top")), atHome.children.keys)
    }

    @Test
    fun `changing a single in one config changes it in all, and moving it moves it in one`() {
        val named = document.edit(root(home)) { c -> c.withWidgetUpdated("rail") { it.copy(props = props("new")) } }
        assertEquals(props("new"), named.at(root(library)).widgets.first().props)
        assertEquals(2f, named.at(root(library)).widgets.first().placement?.x)
        val moved = document.edit(root(home)) { c -> c.withPlacement("rail") { it.copy(x = 40f) } }
        assertEquals(listOf(40f, 2f), listOf(home, library).map { moved.at(root(it)).widgets.first().placement?.x })
        assertEquals(document.launcher().singles, moved.launcher().singles)
    }

    @Test
    fun `what a single holds is edited through any config that has it`() {
        val inside = { config: ConfigId -> root(config).child("rail", SlotId("top")) }
        val edited = document.edit(inside(library)) { it.withWidget(node("link", "link2"), 1) }
        assertEquals(listOf("link1", "link2"), edited.ids(inside(home)))
        assertEquals(document.launcher().configs, edited.launcher().configs, "no config says any of it again")
    }

    @Test
    fun `a config holds of a single only that it stands there and where`() {
        val edited = document.edit(root(home)) { c -> c.withWidgetUpdated("rail") { it.copy(props = props("new"), placement = Placement(x = 5f)) } }
        val raw = edited.launcher().configs.getValue(home).layouts.single().root.widgets.first()
        assertEquals(WidgetInstance(rail, "rail", placement = Placement(x = 5f)), raw)
        assertNull(edited.launcher().singles.getValue(rail).placement)
    }

    @Test
    fun `a second one put down is the one there is and changes nothing of it`() {
        val fresh = WidgetInstance(rail, "rail-new", props = props("default"), placement = Placement(x = 9f))
        val emptied = document.edit(root(library)) { it.withoutWidget("rail") }
        val again = emptied.edit(root(library)) { it.withWidget(fresh, 0) }
        assertEquals(document.launcher().singles, again.launcher().singles)
        assertEquals(listOf("rail", "packs"), again.ids(root(library)))
        assertEquals(9f, again.at(root(library)).widgets.first().placement?.x)
        assertSame(document, document.keepSingle(launcher, fresh))
    }

    @Test
    fun `taking a single out of one config leaves it in the others and keeps its record`() {
        val out = document.edit(root(home)) { it.withoutWidget("rail") }
        assertEquals(listOf("news", "box"), out.ids(root(home)))
        assertEquals(listOf("rail", "packs"), out.ids(root(library)))
        assertEquals(document.launcher().singles, out.launcher().singles)
    }

    @Test
    fun `a widget kept as a single is one from then on`() {
        val news = WidgetKind("news")
        val kept = document.keepSingle(launcher, node("news").copy(props = props("kept"), placement = Placement(x = 3f)))
        assertEquals(props("kept"), kept.at(root(home)).widgets.first { it.kind == news }.props)
        assertNull(kept.launcher().singles.getValue(news).placement)
        assertSame(document, document.keepSingle(CompositionName("gone"), node("news")))
    }

    // A slot held as a config's own

    private val top = SlotId("top")
    private fun inRail(config: ConfigId) = root(config).child("rail", top)
    private val mint = { name: String -> "$name-own" }

    @Test
    fun `a slot a config holds as its own shows what that config holds, and the record's everywhere else`() {
        val held = document.holdOwn(inRail(home), mint)
        assertTrue(held.holdsOwn(inRail(home)))
        assertFalse(held.holdsOwn(inRail(library)))
        assertEquals(listOf("link1-own"), held.ids(inRail(home)), "a copy under a name of its own")
        assertEquals(listOf("link1"), held.ids(inRail(library)))
        val edited = held.edit(inRail(home)) { it.withWidget(node("filter"), 1) }
        assertEquals(listOf("link1-own", "filter"), edited.ids(inRail(home)))
        assertEquals(listOf("link1"), edited.ids(inRail(library)))
        assertEquals(document.launcher().singles, edited.launcher().singles, "the record is untouched")
    }

    @Test
    fun `the other slots of a single stay one record while one of them is a config's own`() {
        val two = document.edit(root(home)) { c -> c.withWidgetUpdated("rail") { it.copy(children = it.children + (SlotId("bottom") to SlotContent(listOf(node("bell"))))) } }
        val held = two.holdOwn(inRail(home), mint)
        val edited = held.edit(root(home).child("rail", SlotId("bottom"))) { it.withWidget(node("clock", "clock2"), 1) }
        assertEquals(listOf("bell", "clock2"), edited.ids(root(library).child("rail", SlotId("bottom"))))
        assertEquals(listOf("link1-own"), edited.ids(inRail(home)))
    }

    @Test
    fun `changing the single where it stands keeps the slot the config holds as its own out of the record`() {
        val held = document.holdOwn(inRail(home), mint).edit(inRail(home)) { it.withWidget(node("filter"), 0) }
        val named = held.edit(root(home)) { c -> c.withWidgetUpdated("rail") { it.copy(props = props("new")) } }
        assertEquals(props("new"), named.at(root(library)).widgets.first().props)
        assertEquals(document.launcher().singles.getValue(rail).children, named.launcher().singles.getValue(rail).children)
        assertEquals(listOf("filter", "link1-own"), named.ids(inRail(home)))
        val raw = named.launcher().configs.getValue(home).layouts.single().root.widgets.first()
        assertEquals(setOf(top), raw.children.keys, "the config holds the slot and nothing else of the single")
    }

    @Test
    fun `a slot held as one's own by any config is known from every config`() {
        val held = document.holdOwn(inRail(home), mint)
        assertTrue(held.heldOwnAnywhere(inRail(library)))
        assertTrue(held.heldOwnAnywhere(inRail(home)))
        assertFalse(document.heldOwnAnywhere(inRail(library)))
        assertFalse(held.heldOwnAnywhere(root(home).child("box", SlotId("body"))))
        assertFalse(held.heldOwnAnywhere(NodePath(launcher, NodeRoot.Residents)))
    }

    @Test
    fun `a slot given back shows the record's again`() {
        val back = document.holdOwn(inRail(home), mint).edit(inRail(home)) { it.withWidget(node("filter"), 0) }.shareOwn(inRail(home))
        assertFalse(back.holdsOwn(inRail(home)))
        assertEquals(listOf("link1"), back.ids(inRail(home)))
        assertEquals(document.launcher().configs, back.launcher().configs)
    }

    @Test
    fun `only a single's slot can be held as one's own, and only once`() {
        val held = document.holdOwn(inRail(home), mint)
        assertSame(held, held.holdOwn(inRail(home)) { error("held already") })
        assertSame(document, document.holdOwn(root(home).child("box", SlotId("body")), mint))
        assertSame(document, document.holdOwn(root(home).child("rail", SlotId("nothing")), mint))
        assertSame(document, document.holdOwn(root(home), mint))
        assertSame(document, document.shareOwn(inRail(home)))
        assertFalse(document.holdsOwn(root(home).child("box", SlotId("body"))))
    }

    @Test
    fun `a single moved takes the slots it holds as its own along`() {
        val held = document.holdOwn(inRail(home), mint)
        val inBox = held.moveWidget(root(home), root(home).child("box", SlotId("body")), "rail", 0)
        assertEquals(listOf("link1-own"), inBox.ids(root(home).child("box", SlotId("body")).child("rail", top)))
        assertEquals(document.launcher().singles, inBox.launcher().singles)
        val fromLibrary = document.edit(root(library)) { it.withoutWidget("rail") }.holdOwn(inRail(home), mint)
        val there = fromLibrary.moveWidget(root(home), root(library), "rail", 0)
        assertEquals(listOf("link1-own"), there.ids(inRail(library)))
    }

    @Test
    fun `what a config holds as its own is listed and named like anything else`() {
        val held = document.holdOwn(inRail(home), mint)
        assertEquals(listOf("rail", "link1", "link1-own", "news", "box", "clock", "packs"), held.launcher().widgets().map { it.instanceId }.toList())
        val twice = held.edit(inRail(home)) { it.withWidget(node("link", "link1"), 1) }
        var n = 0
        val named = twice.withOwnNames { "$it-${++n}" }
        val ids = named.launcher().widgets().map { it.instanceId }.toList()
        assertEquals(ids.size, ids.toSet().size, "$ids")
        assertEquals(listOf("link1-own", "link1-1"), named.ids(inRail(home)))
        val read = assertIs<DocumentRead.Whole>(DocumentCodec.read(DocumentCodec.write(held)))
        assertEquals(held, read.document)
    }

    // Shown on

    @Test
    fun `a widget is shown where one of the places it names is, and nowhere one it is hidden on is`() {
        val anywhere = node("news")
        assertTrue(anywhere.shownAt(setOf("home")) && anywhere.shownAt(emptySet()) && !anywhere.conditional)
        val onHome = node("news").copy(shownOn = listOf("home"))
        assertTrue(onHome.shownAt(setOf("main", "home")))
        assertFalse(onHome.shownAt(setOf("main", "library")))
        val notOnHome = node("news").copy(hiddenOn = listOf("home"))
        assertFalse(notOnHome.shownAt(setOf("main", "home")))
        assertTrue(notOnHome.shownAt(setOf("main", "library")))
        assertFalse(node("news").copy(shownOn = listOf("home"), hiddenOn = listOf("main")).shownAt(setOf("main", "home")), "hidden wins")
        val read = assertIs<DocumentRead.Whole>(DocumentCodec.read(DocumentCodec.write(document.edit(root(home)) { it.withWidget(onHome, 0) })))
        assertEquals(listOf("home"), read.document.at(root(home)).widgets.first().shownOn)
    }

    // Configs

    @Test
    fun `a config is added, put in place and taken away`() {
        val other = config(node("news"))
        val added = document.withConfig(launcher, ConfigId("made"), other)
        assertEquals(other, added.launcher().configs[ConfigId("made")])
        assertSame(added, added.withConfig(launcher, ConfigId("made"), other))
        assertEquals(document.launcher().configs, added.withoutConfig(launcher, ConfigId("made")).launcher().configs)
        assertSame(document, document.withoutConfig(launcher, ConfigId("none")))
        assertSame(document, document.withConfig(CompositionName("gone"), home, other))
    }

    @Test
    fun `every widget is rewritten, inside singles too, and the singles stay`() {
        val noLinks = document.flatMapWidgets(launcher) { w -> if (w.kind.value == "link") emptyList() else listOf(w) }
        assertEquals(emptyList(), noLinks.ids(root(home).child("rail", SlotId("top"))))
        assertEquals(setOf(rail), noLinks.launcher().singles.keys)
        assertEquals(listOf("news", "box"), noLinks.ids(root(home)).drop(1))
        assertSame(document, document.flatMapWidgets(launcher) { listOf(it) })
    }

    // Edits

    @Test
    fun `an edit reaches a nested slot and nothing beside it`() {
        val body = root(home).child("box", SlotId("body"))
        val edited = document.edit(body) { it.withWidget(node("weather"), 0) }
        assertEquals(listOf("weather", "clock"), edited.ids(body))
        assertSame(document.launcher().configs[library], edited.launcher().configs[library])
        assertEquals(root(home), body.parentPath)
        assertNull(root(home).parentPath)
    }

    @Test
    fun `an edit that changes nothing or leads nowhere is this very document`() {
        assertSame(document, document.edit(root(home)) { it })
        assertSame(document, document.edit(root(home)) { it.withoutWidget("nobody") })
        assertSame(document, document.edit(root(ConfigId("gone"))) { SlotContent() })
        assertSame(document, document.edit(NodePath(launcher, NodeRoot.Layout(home, LayoutId("gone")))) { SlotContent() })
        assertSame(document, document.edit(NodePath(CompositionName("gone"), NodeRoot.Residents)) { SlotContent() })
        assertSame(document, document.edit(root(home).child("nobody", SlotId("body"))) { SlotContent() })
        assertSame(document, document.edit(root(home).child("box", SlotId("nothing"))) { SlotContent() })
    }

    @Test
    fun `a slot its container holds nothing under yet reads as empty and takes what is put in it`() {
        val fresh = root(home).child("news", SlotId("body"))
        assertEquals(SlotContent(), document.contentAt(fresh), "a slot the widget came to declare after the file was written")
        val edited = document.edit(fresh) { it.withWidget(node("weather"), 0) }
        assertEquals(listOf("weather"), edited.ids(fresh))
        val inRecord = root(home).child("rail", SlotId("bottom"))
        assertEquals(listOf("weather"), document.edit(inRecord) { it.withWidget(node("weather"), 0) }.launcher().singles.getValue(rail).children[SlotId("bottom")]?.widgets?.map { it.instanceId })
        assertNull(document.contentAt(root(home).child("nobody", SlotId("body"))), "a container that is not there still leads nowhere")
    }

    @Test
    fun `residents are edited like any slot and are placed freely`() {
        val path = NodePath(launcher, NodeRoot.Residents)
        val edited = document.edit(path) { it.withWidget(node("pill"), 0) }
        assertEquals(listOf("pill"), edited.ids(path))
        assertNull(edited.at(path).flow)
    }

    // Moves

    @Test
    fun `a widget moves between slots and configs, and into a single`() {
        val body = root(home).child("box", SlotId("body"))
        val top = root(home).child("rail", SlotId("top"))
        assertEquals(listOf("clock", "news"), document.moveWidget(root(home), body, "news", 1).ids(body))
        assertEquals(listOf("rail", "news", "packs"), document.moveWidget(root(home), root(library), "news", 1).ids(root(library)))
        val inRail = document.moveWidget(root(home), top, "news", 0)
        assertEquals(listOf("news", "link1"), inRail.ids(root(library).child("rail", SlotId("top"))))
        assertEquals(listOf("rail", "box"), inRail.ids(root(home)))
    }

    @Test
    fun `a widget moves within its slot by its place in it`() {
        val moved = document.moveWidget(root(home), root(home), "news", 2)
        assertEquals(listOf("rail", "box", "news"), moved.ids(root(home)))
    }

    @Test
    fun `nothing moves inside itself, off the document, or out of the composition that has it once`() {
        val console = CompositionName("console")
        val two = document.copy(compositions = document.compositions + (console to Composition(start = home, configs = mapOf(home to config()))))
        val there = NodePath(console, NodeRoot.Layout(home, main))
        assertSame(two, two.moveWidget(root(home), root(home).child("box", SlotId("body")), "box", 0))
        assertSame(two, two.moveWidget(root(home), root(ConfigId("gone")), "news", 0))
        assertSame(two, two.moveWidget(root(home), there, "nobody", 0))
        assertSame(two, two.moveWidget(root(home), there, "rail", 0))
        assertEquals(listOf("news"), two.moveWidget(root(home), there, "news", 0).ids(there))
    }

    @Test
    fun `a widget arriving among placed ones is given a place`() {
        val placed = document.edit(root(library)) { it.withFlow(null) }
        val moved = placed.moveWidget(root(home), root(library), "news", 0)
        assertNotNull(moved.at(root(library)).widgets.first { it.instanceId == "news" }.placement)
    }

    // Names

    @Test
    fun `every widget is listed once, a single however many configs have it`() {
        assertEquals(listOf("rail", "link1", "news", "box", "clock", "packs"), document.launcher().widgets().map { it.instanceId }.toList())
    }

    @Test
    fun `two widgets under one name are told apart and a single keeps the one it goes by everywhere`() {
        val twice = document.edit(root(library)) { it.withWidget(node("link", "link1"), 2).withWidget(node("other", "rail"), 3) }
        var n = 0
        val named = twice.withOwnNames { "$it-${++n}" }
        val ids = named.launcher().widgets().map { it.instanceId }.toList()
        assertEquals(ids.size, ids.toSet().size, "$ids")
        assertEquals("rail", named.launcher().singles.getValue(rail).instanceId)
        assertTrue(listOf(home, library).all { named.ids(root(it)).first() == "rail" })
        assertEquals(6 + 2, ids.size)
        assertSame(document, document.withOwnNames { error("nothing to mint") })
    }

    @Test
    fun `a single given a name of its own goes by it wherever it stands`() {
        val bar = WidgetKind("bar")
        val box = node("rail", "rail", "top" to listOf(WidgetInstance(bar, "rail")))
        val shared = document.copy(
            compositions = mapOf(
                launcher to document.launcher().copy(
                    singles = mapOf(rail to box, bar to node("bar", "rail")),
                    configs = mapOf(home to config(standing(), WidgetInstance(bar, "rail"), node("box", "box", "body" to listOf(WidgetInstance(bar, "rail"))))),
                    residents = SlotContent(listOf(WidgetInstance(bar, "rail"))),
                ),
            ),
        )
        val named = shared.withOwnNames { "$it-own" }
        assertEquals(listOf("rail", "rail-own"), named.launcher().singles.values.map { it.instanceId })
        assertEquals(listOf("rail", "rail-own", "box"), named.ids(root(home)))
        assertEquals(listOf("rail-own"), named.ids(root(home).child("box", SlotId("body"))))
        assertEquals(listOf("rail-own"), named.ids(root(home).child("rail", SlotId("top"))))
        assertEquals(listOf("rail-own"), named.ids(NodePath(launcher, NodeRoot.Residents)))
        val raw = named.launcher().configs.getValue(home).layouts.single().root.widgets
        assertEquals(listOf("rail", "rail-own", "box"), raw.map { it.instanceId }, "in the file too, which is what an edit's address goes by")
    }

    // The file

    @Test
    fun `a document reads back as it was written`() {
        val full = document.copy(
            compositions = document.compositions.mapValues { (_, c) ->
                c.copy(frame = Frame(width = 1280, minWidth = 400, chrome = Frame.NONE, state = Frame.MAXIMIZED, title = "Nexira"), transition = node("fade"))
            },
        )
        val read = assertIs<DocumentRead.Whole>(DocumentCodec.read(DocumentCodec.write(full)))
        assertEquals(full, read.document)
        assertTrue("\"schema\": $DOCUMENT_SCHEMA" in DocumentCodec.write(full), "a file says which schema it is even where it is the current one")
    }

    @Test
    fun `a newer build's file and an older shape are told apart from a broken one, and what a newer one adds is passed over`() {
        val text = DocumentCodec.write(document)
        val current = "\"schema\": $DOCUMENT_SCHEMA"
        assertEquals(DocumentRead.Newer(DOCUMENT_SCHEMA + 1), DocumentCodec.read(text.replace(current, "\"schema\": ${DOCUMENT_SCHEMA + 1}")))
        assertEquals(DocumentRead.Outdated(DOCUMENT_SCHEMA - 1), DocumentCodec.read(text.replace(current, "\"schema\": ${DOCUMENT_SCHEMA - 1}")))
        assertEquals(DocumentRead.Outdated(1), DocumentCodec.read("{ \"schema\": 1 }"), "an older shape is not read any further than its schema")
        assertEquals(document, assertIs<DocumentRead.Whole>(DocumentCodec.read(text.replaceFirst("{", "{ \"later\": true,"))).document)
        for (broken in listOf("", "[]", "{", "{}", "{ \"schema\": \"one\" }", "{ \"schema\": $DOCUMENT_SCHEMA }")) {
            assertIs<DocumentRead.Broken>(DocumentCodec.read(broken), broken)
        }
    }

    @Test
    fun `a frame a build does not know is the plain one`() {
        assertTrue(Frame(chrome = "glass").systemChrome && !Frame(chrome = " None ").systemChrome)
        assertTrue(Frame(state = "Maximized ").opensMaximized && !Frame(state = "kiosk").opensMaximized)
    }
}
