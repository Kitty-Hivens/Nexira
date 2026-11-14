package hivens.widget.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The plain app reads, [rememberCommand] and [rememberAction], answer a command the
 * app level makes from a factory the way [rememberSource] answers a source, and a
 * factory that cannot make what it is asked for is told to the tree's listener.
 */
class AppCommandFallbackTest {

    /** A tree with no nodes: these reads draw nothing, they only remember. */
    private object NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    /** Composes [content] once, under [commands] and [app], and lets go of it. */
    private fun compose(commands: WidgetCommandRegistry, app: WidgetScope, content: @Composable () -> Unit) {
        val recomposer = Recomposer(EmptyCoroutineContext)
        val composition = Composition(NoNodes, recomposer)
        try {
            composition.setContent {
                CompositionLocalProvider(LocalWidgetCommandRegistry provides commands, LocalScope provides app, content = content)
            }
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private val say = CommandKey<String>("test.say")
    private val ping = CommandKey<Unit>("test.ping")

    @Test
    fun `an app command made by a factory is fired by rememberCommand and rememberAction`() {
        val fired = ArrayList<String>()
        val commands = WidgetCommandRegistry().apply {
            registerFactory(say) { command { fired += it } }
            registerFactory(ping) { command { fired += "ping" } }
        }
        val app = WidgetScope.app(WidgetDataRegistry(), MemoryLevelServices.app(), Dispatchers.Unconfined, commands)
        var sayIt: ((String) -> Unit)? = null
        var pingIt: (() -> Unit)? = null
        compose(commands, app) {
            sayIt = rememberCommand(say)
            pingIt = rememberAction(ping)
        }
        assertNotNull(sayIt).invoke("hello")
        assertNotNull(pingIt).invoke()
        assertEquals(listOf("hello", "ping"), fired)
    }

    // Thrown from a click, a command a host left out took the window down.
    @Test
    fun `a registered value is still what answers, and a key nothing answers does nothing when fired`() {
        val fired = ArrayList<String>()
        val commands = WidgetCommandRegistry().apply { register(say, command { fired += "value $it" }) }
        val app = WidgetScope.app(WidgetDataRegistry(), MemoryLevelServices.app(), Dispatchers.Unconfined, commands)
        var sayIt: ((String) -> Unit)? = null
        var pingIt: (() -> Unit)? = null
        compose(commands, app) {
            sayIt = rememberCommand(say)
            pingIt = rememberAction(ping)
        }
        assertNotNull(sayIt).invoke("x")
        assertNotNull(pingIt).invoke()
        assertEquals(listOf("value x"), fired)
    }

    @Test
    fun `a factory that cannot make its source or command is told to the listener once per level`() {
        val told = ArrayList<String>()
        val broken = SourceKey<String>("test.broken", ScopeLevel.Window)
        val data = WidgetDataRegistry().apply { registerFactory(broken) { error("no service") } }
        val commands = WidgetCommandRegistry().apply { registerFactory(say) { error("no service") } }
        val app = WidgetScope.app(data, MemoryLevelServices.app(), Dispatchers.Unconfined, commands) { id, level, cause ->
            told += "$id at $level: ${cause.message}"
        }
        val window = app.child(ScopeLevel.Window)
        assertNull(window.find(broken))
        assertNull(window.find(broken))
        assertNull(app.findCommand(say))
        assertNull(window.findCommand(say))
        assertEquals(listOf("test.broken at Window: no service", "test.say at App: no service"), told)
        app.child(ScopeLevel.Window).find(broken)
        assertEquals(3, told.size, "another window tries again and says so again")
    }

    @Test
    fun `a run that fails beside one that makes the key is not told, and both read what was made`() {
        val told = CopyOnWriteArrayList<String>()
        val racy = SourceKey<String>("test.racy", ScopeLevel.Window)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val data = WidgetDataRegistry().apply {
            registerFactory(racy) {
                if (calls.getAndIncrement() == 0) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    error("lost the race")
                }
                flowSource(MutableStateFlow("made"))
            }
        }
        val app = WidgetScope.app(data, MemoryLevelServices.app(), Dispatchers.Unconfined) { id, _, _ -> told += id }
        val window = app.child(ScopeLevel.Window)
        var slow: WidgetDataSource<String>? = null
        val slowRead = thread { slow = window.find(racy) }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first run never started")
        val fast = assertNotNull(window.find(racy))
        release.countDown()
        slowRead.join(5_000)
        assertSame(fast, slow, "the run that failed reads what the other one made")
        assertEquals(emptyList(), told.toList(), "a key that works was reported missing")
    }

    @Test
    fun `a listener that throws leaves the key missing rather than failing the read`() {
        val broken = SourceKey<String>("test.broken", ScopeLevel.Window)
        val data = WidgetDataRegistry().apply { registerFactory(broken) { error("no service") } }
        val app = WidgetScope.app(data, MemoryLevelServices.app(), Dispatchers.Unconfined) { _, _, _ -> error("listener broke") }
        assertNull(app.child(ScopeLevel.Window).find(broken))
    }
}
