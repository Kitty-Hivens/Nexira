package hivens.module.accountsconsumer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module reads the session and fires sign-out typed, through nothing but the
 * kernel and the accounts API. The test stands in for the launcher: it answers the
 * accounts keys the way the accounts module would, then brings this module's keys in
 * the way the host brings a jar's, not trusted.
 */
@OptIn(HostApi::class, ExperimentalComposeUiApi::class)
class GreetingTest {

    private object NoServices : LevelServicesHost {
        override val services = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    private val alex = SessionView(name = "Alex", uuid = "0123456789abcdef0123456789abcdef", providerKey = AccountKeys.MICROSOFT, offline = false)
    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn(alex))
    private var signedOut = 0

    /** The accounts keys as the accounts module answers them, or none of them when [withAccounts] is false. */
    private fun tree(withAccounts: Boolean): Triple<WidgetScope, WidgetDataRegistry, WidgetCommandRegistry> {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        if (withAccounts) {
            sources.registerFactory(AccountKeys.Session) { flowSource(session) }
            commands.registerFactory(AccountKeys.SignOut) {
                command {
                    signedOut++
                    session.value = SessionState.SignedOut
                }
            }
        }
        val services = ContributedServices()
        assertTrue(registerModuleKeys("accounts-consumer", GreetingKeys(), sources, commands, services, trusted = false) is ModuleKeysResult.Registered)
        val app = WidgetScope.app(sources, services.host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands)
        return Triple(app, sources, commands)
    }

    @Test
    fun `its own key is made from the session, typed, and follows it`() {
        val (app) = tree(withAccounts = true)
        assertEquals("Hello, Alex", app.current(GreetingKeys.Greeting))
        session.value = SessionState.SignedOut
        assertNull(app.current(GreetingKeys.Greeting))
    }

    @Test
    fun `without the accounts module its own key is missing too, and nothing throws`() {
        val (app) = tree(withAccounts = false)
        assertNull(app.find(GreetingKeys.Greeting))
    }

    @Test
    fun `a widget reads the session view and fires sign-out`() {
        val (app, sources, commands) = tree(withAccounts = true)
        var read: SessionView? = null
        var signOut: (() -> Unit)? = null
        val scene = ImageComposeScene(50, 50, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides sources,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides app,
            ) {
                SessionBadge { s, out ->
                    read = s
                    signOut = out
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertEquals(alex, read, "the name, the uuid and the provider, and nothing else")
        assertNotNull(signOut).invoke()
        assertEquals(1, signedOut)
        assertEquals(SessionState.SignedOut, session.value)
    }

    @Test
    fun `a widget where the accounts module is not brought in reads nobody and has no sign-out`() {
        val (app, sources, commands) = tree(withAccounts = false)
        var read = true
        val scene = ImageComposeScene(50, 50, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides sources,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides app,
            ) {
                SessionBadge { s, out -> read = s != null || out != null }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertTrue(!read, "it found accounts keys nobody brought in")
    }
}
