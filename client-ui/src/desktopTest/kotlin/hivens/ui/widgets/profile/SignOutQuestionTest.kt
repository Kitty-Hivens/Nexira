package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import hivens.auth.AccountStore
import hivens.auth.AuthProviderRegistry
import hivens.auth.StoredAccount
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.ui.AppState
import hivens.ui.components.SignOutPrompt
import hivens.ui.components.SignOutRequests
import hivens.ui.components.rememberSignOutRequest
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.i18n.stringsFor
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.levelServicesModule
import hivens.ui.onEventThread
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.Commands
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sign-out question belongs to the window, so it outlives the widget that asked
 * it, and it closes itself once nobody is signed in.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SignOutQuestionTest {

    private val microsoft = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef")

    /**
     * Two accounts, Microsoft and SmartyCraft. Removing the Microsoft one leaves no
     * face to fall back to, which is the case where the section asks for a full
     * sign-out and then leaves, its account being gone.
     */
    private class TwoAccounts(private val microsoft: SessionData) : AccountStore {
        @Volatile var microsoftRemoved = false
        override fun load(): SessionData? = null
        override fun accountFor(providerId: String): SessionData? =
            if (providerId == PackAuthRequirement.Microsoft.PROVIDER_KEY && !microsoftRemoved) microsoft else null
        override fun refreshStored(providerId: String, session: SessionData) = Unit
        override fun saveAccount(session: SessionData, providerId: String, makeActive: Boolean) = Unit
        override fun markTwoFactor(providerId: String) = Unit
        override fun clearTwoFactor(providerId: String) = Unit
        override fun listAccounts(): List<StoredAccount> = listOf(
            StoredAccount(PackAuthRequirement.Microsoft.PROVIDER_KEY, "ms", "Alex", microsoft.uuid, "Alex"),
            StoredAccount(PackAuthRequirement.SmartyCraft.PROVIDER_KEY, "sc", "Steve", "", "Steve"),
        )
        override fun activeAccountId(): String? = null
        override fun primarySession(preferredProviderId: String?): SessionData? = null
        override fun loadSession(providerId: String, accountId: String): SessionData? = null
        override fun setActive(accountId: String) = Unit
        override fun removeAccount(providerId: String, accountId: String) {
            if (providerId == PackAuthRequirement.Microsoft.PROVIDER_KEY) microsoftRemoved = true
        }
        override fun clear() = Unit
    }

    private class Settings : ISettingsService {
        @Volatile private var current = SettingsData()
        override fun getSettings(): SettingsData = current
        override fun saveSettings(settings: SettingsData) {
            current = settings
        }
    }

    private val session = MutableStateFlow<AppState>(AppState.Authenticated(microsoft))
    private val revision = MutableStateFlow(0)
    private val signedOut = CopyOnWriteArrayList<String>()

    private val data = WidgetDataRegistry().apply {
        register(Sources.Session, flowSource(session))
        register(Sources.AccountsRevision, flowSource(revision))
    }

    private val commands = WidgetCommandRegistry().apply {
        register(Commands.SignIn, command { session.value = AppState.Authenticated(it) })
        register(Commands.SignOut, command {
            signedOut += "signed out"
            session.value = AppState.Unauthenticated
        })
        register(Commands.AccountsChanged, command { revision.value += 1 })
    }

    private val accounts = TwoAccounts(microsoft)

    private val app: WidgetScope = koinApplication {
        modules(
            levelServicesModule,
            module {
                single<AccountStore> { accounts }
                single<ISettingsService> { Settings() }
                single { AuthProviderRegistry(emptyList()) }
                single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) }
            },
        )
    }.koin.get()

    /** Draws [content] in a window and a composition with the window's question over it, and runs [steps] on the scene. */
    private fun drawn(content: @Composable () -> Unit, steps: (ImageComposeScene, () -> SignOutRequests) -> Unit) {
        var window: WidgetScope? = null
        onEventThread {
            val scene = ImageComposeScene(900, 900, density = Density(1f)) {
                LocaleProvider(AppLocale.ENGLISH) {
                    NxTheme(dark = true) {
                        LayerHost(rememberWindowLayers(), Modifier.fillMaxSize()) {
                            CompositionLocalProvider(
                                LocalWidgetDataRegistry provides data,
                                LocalWidgetCommandRegistry provides commands,
                                LocalScope provides app,
                            ) {
                                ChildLevel(ScopeLevel.Window) {
                                    window = LocalScope.current
                                    ChildLevel(ScopeLevel.Composition) {
                                        content()
                                        SignOutPrompt()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            try {
                scene.settle(4)
                steps(scene) { assertNotNull(assertNotNull(window).services.find<SignOutRequests>()) }
            } finally {
                scene.close()
            }
        }
    }

    /** Pumps frames until [done], with the work dispatched off the event thread given its turn. */
    private fun ImageComposeScene.until(what: String, done: () -> Boolean) {
        repeat(250) {
            if (done()) return
            Thread.sleep(20)
            Snapshot.sendApplyNotifications()
            settle(1)
        }
        throw AssertionError("$what never happened")
    }

    private fun clickable(id: String): Boolean = PuppetRegistry.snapshot().elements.any { it.id == id }

    @Test
    fun `the question asked by an account section stays open after the section has gone, and a yes signs out`() {
        drawn({ ProfileSignInSectionWidget() }) { scene, requests ->
            scene.until("the Microsoft section") { clickable("account.signout.microsoft") }
            PuppetRegistry.click("account.signout.microsoft").getOrThrow()
            scene.until("the section leaving once its account is gone") {
                accounts.microsoftRemoved && !clickable("account.signout.microsoft")
            }
            assertTrue(requests().asking.value, "the question went with the widget that asked it")
            assertTrue(signedOut.isEmpty(), "nothing is signed out before the answer")
            PuppetRegistry.click("logout.confirm").getOrThrow()
            scene.until("the question closing") { !requests().asking.value }
            assertEquals(listOf("signed out"), signedOut.toList())
        }
    }

    // The test above answers through the automation hook, which signs out with no
    // dialog drawn at all. This one answers the way a person does.
    @Test
    fun `the question is drawn over the window and its own yes signs out`() {
        val yes = stringsFor(AppLocale.ENGLISH).navLogout
        var ask: (() -> Unit)? = null
        drawn({ ask = rememberSignOutRequest() }) { scene, requests ->
            assertNotNull(ask).invoke()
            scene.until("the question on screen") { scene.button(yes) != null }
            assertTrue(signedOut.isEmpty(), "nothing is signed out before the answer")
            scene.click(assertNotNull(scene.button(yes)).boundsInRoot.center)
            scene.until("the question closing") { !requests().asking.value }
            assertEquals(listOf("signed out"), signedOut.toList())
        }
    }

    /** The control on screen that says [label] and takes a click, or null. */
    private fun ImageComposeScene.button(label: String): SemanticsNode? =
        semanticsOwners.asSequence()
            .flatMap { it.rootSemanticsNode.withDescendants() }
            .firstOrNull { node ->
                SemanticsActions.OnClick in node.config &&
                    node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label }
            }

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    private fun ImageComposeScene.click(at: Offset) {
        sendPointerEvent(PointerEventType.Move, at)
        sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        settle(2)
        sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        settle(2)
    }

    @Test
    fun `an open question closes itself once nobody is signed in`() {
        var ask: (() -> Unit)? = null
        drawn({ ask = rememberSignOutRequest() }) { scene, requests ->
            assertNotNull(ask).invoke()
            assertTrue(requests().asking.value)
            session.value = AppState.Unauthenticated
            scene.until("the question closing") { !requests().asking.value }
            assertFalse(requests().asking.value)
            assertTrue(signedOut.isEmpty())
        }
    }
}
