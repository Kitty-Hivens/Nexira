package hivens.pages.api

import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What a person made of the pages, read from and written to the host's own record: the screens made with what they
 * hold, the names given to the brought ones, and what the record says nothing of left out of it.
 */
class PagesHostRecordTest {

    private val launcher = CompositionName("launcher")
    private val main = ConfigId("main")
    private val home = ScreenId("home")
    private val notes = ScreenId("notes")

    private val host = WidgetInstance(PagesHost.KIND, "host", children = mapOf(SlotId("home") to SlotContent(listOf(WidgetInstance(WidgetKind("screen.home"), "screen.home")))))

    private fun document(host: WidgetInstance = this.host, kept: Boolean = false): Document {
        val frame = WidgetKind("frame")
        val standing = if (kept) WidgetInstance(PagesHost.KIND, host.instanceId) else host
        val root = SlotContent(listOf(WidgetInstance(frame, "frame", children = mapOf(SlotId("body") to SlotContent(listOf(standing))))))
        return Document(
            start = launcher,
            compositions = mapOf(
                launcher to Composition(
                    start = main,
                    configs = mapOf(main to Config(layouts = listOf(ConfigLayout(LayoutId("main"), root)))),
                    singles = if (kept) mapOf(PagesHost.KIND to host) else emptyMap(),
                ),
            ),
        )
    }

    private fun Document.host() = assertNotNull(compositions.getValue(launcher).pagesHost())

    @Test
    fun `the host is found wherever it stands, and a composition without one has none`() {
        assertEquals(host, document().host())
        assertEquals(host, document(kept = true).host())
        assertNull(Composition(start = main).pagesHost())
    }

    @Test
    fun `a screen is made with what it holds, and made again under its id in place of the first`() {
        val made = document().withMadeScreen(launcher, MadeScreen("notes", "Notes"), SlotContent(listOf(WidgetInstance(WidgetKind("note"), "n1"))))
        assertEquals(listOf(MadeScreen("notes", "Notes")), made.host().pagesHostProps().made)
        assertEquals(listOf("n1"), made.host().screenContent(notes)?.widgets?.map { it.instanceId })
        val again = made.withMadeScreen(launcher, MadeScreen("notes", "Other"))
        assertEquals(listOf(MadeScreen("notes", "Other")), again.host().pagesHostProps().made)
        assertEquals(SlotContent(), again.host().screenContent(notes))
    }

    @Test
    fun `a made screen is renamed in its own entry, a brought one among the named, which a blank name leaves`() {
        val made = document().withMadeScreen(launcher, MadeScreen("notes", "Notes", "star"))
        assertEquals(MadeScreen("notes", "Diary", "star"), made.withScreenNamed(launcher, notes, title = "Diary").host().pagesHostProps().made.single())
        val named = document().withScreenNamed(launcher, home, title = "Start")
        assertEquals(listOf(MadeScreen("home", "Start")), named.host().pagesHostProps().named)
        assertEquals(listOf(MadeScreen("home", "Start", "home")), named.withScreenNamed(launcher, home, icon = "home").host().pagesHostProps().named)
        assertEquals(emptyList(), named.withScreenNamed(launcher, home, title = "").host().pagesHostProps().named)
    }

    @Test
    fun `a made screen goes with what it held, and a brought one's content is put back or dropped`() {
        val made = document().withMadeScreen(launcher, MadeScreen("notes"), SlotContent(listOf(WidgetInstance(WidgetKind("note"), "n1"))))
        val gone = made.withoutMadeScreen(launcher, notes)
        assertEquals(emptyList(), gone.host().pagesHostProps().made)
        assertNull(gone.host().screenContent(notes))
        assertNull(document().withScreenContent(launcher, home, null).host().screenContent(home), "drawn by its module again")
        assertEquals(SlotContent(), document().withScreenContent(launcher, home, SlotContent()).host().screenContent(home))
    }

    @Test
    fun `a host kept once is changed in its record`() {
        val kept = document(kept = true).withScreenNamed(launcher, home, title = "Start")
        assertEquals(listOf(MadeScreen("home", "Start")), kept.compositions.getValue(launcher).singles.getValue(PagesHost.KIND).pagesHostProps().named)
    }

    @Test
    fun `what equals a default is left out of the record, and a record that cannot be read reads as none`() {
        assertEquals(JsonObject(mapOf("start" to JsonPrimitive("home"))), host.withPagesHostProps(PagesHostProps(start = "home")).props)
        assertSame(host, host.withPagesHostProps(PagesHostProps()))
        val broken = host.copy(props = JsonObject(mapOf("named" to JsonPrimitive(3))))
        assertEquals(PagesHostProps(), broken.pagesHostProps())
    }

    @Test
    fun `a composition without a host is left as it is`() {
        val none = Document(start = launcher, compositions = mapOf(launcher to Composition(start = main)))
        assertSame(none, none.withMadeScreen(launcher, MadeScreen("notes")))
        assertSame(none, none.withScreenNamed(CompositionName("gone"), home, title = "x"))
    }
}
