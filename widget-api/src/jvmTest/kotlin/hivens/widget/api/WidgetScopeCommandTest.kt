package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Commands on the scope tree, and the declarations the editor reads keys by. */
class WidgetScopeCommandTest {

    /** A command that records what it was fired with and knows whether it was closed. */
    private class Recording(val at: ScopeLevel) : WidgetCommand<String>, AutoCloseable {
        val fired = ArrayList<String>()
        var closed = false
        override fun run(payload: String) {
            fired += payload
        }
        override fun close() {
            closed = true
        }
    }

    private val appCommand = CommandKey<String>("test.app.command")
    private val windowCommand = CommandKey<String>("test.window.command", ScopeLevel.Window)
    private val pageCommand = CommandKey<String>("test.page.command", ScopeLevel.Page)
    private val pageSource = SourceKey<String>("test.page.source", ScopeLevel.Page)

    private val made = ArrayList<Recording>()
    private val appFired = ArrayList<String>()

    private fun commands() = WidgetCommandRegistry().apply {
        register(appCommand, command { appFired += it })
        registerFactory(windowCommand) { context -> Recording(context.level).also { made += it } }
        declare(pageCommand)
    }

    private fun sources() = WidgetDataRegistry().apply {
        register(SourceKey<String>("test.app.source"), flowSource(MutableStateFlow("app")))
        declare(pageSource)
    }

    private fun app(commands: WidgetCommandRegistry = commands()): WidgetScope {
        val data = sources()
        val koin = koinApplication {
            modules(module { single { WidgetScope.app(data, this, Dispatchers.Unconfined, commands) } })
        }.koin
        return koin.get()
    }

    @Test
    fun `two windows make one command each and do not fire each other's`() {
        val app = app()
        val a = app.child(ScopeLevel.Window)
        val b = app.child(ScopeLevel.Window)
        val fromA = assertNotNull(a.findCommand(windowCommand))
        val fromB = assertNotNull(b.findCommand(windowCommand))
        assertNotSame(fromA, fromB)
        assertSame(fromA, a.findCommand(windowCommand), "made once per level")
        fromA.run("back")
        assertEquals(listOf("back"), (fromA as Recording).fired)
        assertTrue((fromB as Recording).fired.isEmpty())
    }

    @Test
    fun `a command is fired at the level it declares, from below it`() {
        val window = app().child(ScopeLevel.Window)
        val page = window.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(window.findCommand(windowCommand), page.findCommand(windowCommand))
        page.findCommand(appCommand)?.run("app-wide")
        assertEquals(listOf("app-wide"), appFired)
    }

    @Test
    fun `two pages do not see what their sibling was given`() {
        val composition = app().child(ScopeLevel.Window).child(ScopeLevel.Composition)
        val first = composition.child(ScopeLevel.Page)
        val second = composition.child(ScopeLevel.Page)
        val given = Recording(ScopeLevel.Page)
        first.provideCommand(pageCommand, given)
        assertSame(given, first.findCommand(pageCommand))
        assertNull(second.findCommand(pageCommand))
        assertNull(composition.findCommand(pageCommand), "a level above any page has no page to ask")
    }

    @Test
    fun `a withdrawn command is missing again, and a closed level fires nothing`() {
        val page = app().child(ScopeLevel.Page)
        val handle = page.provideCommand(pageCommand, Recording(ScopeLevel.Page))
        handle.close()
        assertNull(page.findCommand(pageCommand))
        page.provideCommand(pageCommand, Recording(ScopeLevel.Page))
        page.close()
        assertNull(page.findCommand(pageCommand))
        assertFailsWith<IllegalStateException> { page.provideCommand(pageCommand, Recording(ScopeLevel.Page)) }
    }

    @Test
    fun `what a level made is closed with it`() {
        val window = app().child(ScopeLevel.Window)
        val command = window.findCommand(windowCommand) as Recording
        window.close()
        assertTrue(command.closed)
    }

    @Test
    fun `a provision is refused where the command does not live or a factory makes it`() {
        val app = app()
        val window = app.child(ScopeLevel.Window)
        assertFailsWith<IllegalArgumentException> { window.provideCommand(pageCommand, Recording(ScopeLevel.Window)) }
        assertFailsWith<IllegalArgumentException> { window.provideCommand(windowCommand, Recording(ScopeLevel.Window)) }
    }

    @Test
    fun `the registry keeps a value to the app and an id to one entry`() {
        val registry = commands()
        assertFailsWith<IllegalArgumentException> { registry.register(pageCommand, command { }) }
        assertFailsWith<IllegalArgumentException> { registry.declare(CommandKey<Unit>("test.app.command", ScopeLevel.Page)) }
        assertFailsWith<IllegalArgumentException> { registry.declare(CommandKey<Unit>("test.other")) }
        assertEquals(setOf("test.app.command", "test.window.command", "test.page.command"), registry.keys())
    }

    @Test
    fun `a declaration says where a key lives and what answers it`() {
        val app = app()
        assertEquals(
            KeyDeclaration("test.window.command", ScopeLevel.Window, KeyKind.Command, KeyOrigin.Factory),
            app.declarationOf("test.window.command"),
        )
        assertEquals(
            KeyDeclaration("test.page.source", ScopeLevel.Page, KeyKind.Source, KeyOrigin.Owner),
            app.declarationOf("test.page.source"),
        )
        assertEquals(KeyOrigin.Registered, app.declarationOf("test.app.source")?.origin)
        assertNull(app.declarationOf("test.nobody"))
    }

    @Test
    fun `a level answers an owner's key only while the owner provides it`() {
        val app = app()
        val page = app.child(ScopeLevel.Window).child(ScopeLevel.Page)
        val source = assertNotNull(app.declarationOf(pageSource.id))
        val command = assertNotNull(app.declarationOf(pageCommand.id))
        assertFalse(page.answers(source))
        assertFalse(page.answers(command))
        val handle = page.provide(pageSource, "here")
        page.provideCommand(pageCommand, Recording(ScopeLevel.Page))
        assertTrue(page.answers(source))
        assertTrue(page.answers(command))
        handle.close()
        assertFalse(page.answers(source))
        assertFalse(app.answers(source), "no page above the app")
        val factory = assertNotNull(app.declarationOf(windowCommand.id))
        assertFalse(app.answers(factory), "no window above the app")
        assertTrue(page.answers(factory), "a factory answers wherever its level is")
    }
}
