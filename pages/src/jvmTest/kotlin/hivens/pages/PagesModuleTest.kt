package hivens.pages

import hivens.pages.api.MadeScreen
import hivens.pages.api.PagesHost
import hivens.pages.api.PagesHostProps
import hivens.pages.api.ScreenContribution
import hivens.pages.api.ScreenId
import hivens.pages.api.ScreenView
import hivens.pages.api.withPagesHostProps
import hivens.widget.api.TakenContribution
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The screens a composition can show and the one it starts on: what the modules brought, each id once, under what the
 * person called them in the host's record, then what the person made there.
 */
class PagesModuleTest {

    private fun brought(module: String, id: String, icon: String? = null) =
        TakenContribution(module, ScreenContribution(id, "$id.label", WidgetKind("screen.$id"), icon))

    private fun host(props: PagesHostProps) = WidgetInstance(PagesHost.KIND, "host").withPagesHostProps(props)

    private val contributed = listOf(brought("launcher", "home", icon = "home"), brought("launcher", "library"), brought("other", "home"))

    @Test
    fun `a screen is the first module's to bring its id, under the module's words`() {
        assertEquals(
            listOf(
                ScreenView(ScreenId("home"), "home.label", owner = "launcher", icon = "home", kind = WidgetKind("screen.home")),
                ScreenView(ScreenId("library"), "library.label", owner = "launcher", kind = WidgetKind("screen.library")),
            ),
            screensOf(contributed, host = null),
        )
    }

    @Test
    fun `a screen the person renamed goes by their name and icon, and is no module's to name`() {
        val renamed = screensOf(contributed, host(PagesHostProps(named = listOf(MadeScreen("home", "Start", "star"), MadeScreen("library", icon = "book")))))
        assertEquals(ScreenView(ScreenId("home"), "Start", owner = null, icon = "star", kind = WidgetKind("screen.home")), renamed[0])
        assertEquals(ScreenView(ScreenId("library"), "library.label", owner = "launcher", icon = "book", kind = WidgetKind("screen.library")), renamed[1], "only the icon is theirs")
    }

    @Test
    fun `the screens a person made come after the ones brought, each once, and none under a brought one's id`() {
        val made = listOf(MadeScreen("notes", "Notes"), MadeScreen("home", "Mine"), MadeScreen("notes", "Again"), MadeScreen(""))
        val screens = screensOf(contributed, host(PagesHostProps(made = made)))
        assertEquals(listOf("home", "library", "notes"), screens.map { it.id.value })
        assertEquals(ScreenView(ScreenId("notes"), "Notes", owner = null, made = true), screens.last())
        assertEquals("home.label", screens.first().title, "a made one cannot take a brought one's place")
    }

    @Test
    fun `a record that cannot be read is no record`() {
        val broken = WidgetInstance(PagesHost.KIND, "host", props = JsonObject(mapOf("made" to JsonPrimitive("not a list"))))
        assertEquals(screensOf(contributed, host = null), screensOf(contributed, broken))
    }

    @Test
    fun `the start is the screen the host names where there is one, else the first`() {
        val screens = screensOf(contributed, host = null)
        assertEquals(ScreenId("library"), startOf(screens, host(PagesHostProps(start = "library"))))
        assertEquals(ScreenId("home"), startOf(screens, host(PagesHostProps(start = "gone"))))
        assertEquals(ScreenId("home"), startOf(screens, host = null))
        assertNull(startOf(emptyList(), host(PagesHostProps(start = "home"))))
    }
}
