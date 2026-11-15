package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import hivens.accounts.api.AccountKeys
import hivens.accounts.AccountsModule
import hivens.accounts.SessionStore
import hivens.accounts.api.AccountView
import hivens.accounts.api.AccountsApi
import hivens.accounts.api.RemovalOutcome
import hivens.accounts.api.RemovalView
import hivens.auth.AccountStore
import hivens.auth.StoredAccount
import hivens.core.data.SessionData
import hivens.themes.Themes
import hivens.ui.HostKeys
import hivens.ui.accounts.accountsEngineStandIns
import hivens.ui.background.BackgroundSettings
import hivens.ui.host.LauncherHostAccess
import hivens.ui.host.launcherServices
import hivens.ui.i18n.NexiraStrings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.moduleKeys
import hivens.ui.uiCommandRegistry
import hivens.ui.uiDataRegistry
import hivens.widget.api.ContributedServices
import hivens.widget.api.get
import hivens.accounts.api.ProviderView
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.accounts.api.SignInFlow
import hivens.ui.components.SignOutPrompt
import hivens.ui.components.SignOutRequests
import hivens.ui.components.rememberSignOutRequest
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.levelServicesModule
import hivens.ui.onEventThread
import hivens.ui.puppet.PuppetRegistry
import hivens.ui.settle
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
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
 * it, and it closes itself once nobody is signed in. An account section asks it for
 * the last account, and with another left it removes its own and asks nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SignOutQuestionTest {

    private val microsoft = AccountView(
        providerKey = AccountKeys.MICROSOFT,
        accountId   = "ms",
        name        = "Alex",
        uuid        = "0123456789abcdef0123456789abcdef",
        online      = true,
    )
    private val smartyCraft = AccountView(providerKey = AccountKeys.SMARTYCRAFT, accountId = "sc", name = "Steve", uuid = "")

    private val session = MutableStateFlow<SessionState>(
        SessionState.SignedIn(SessionView(microsoft.name, microsoft.uuid, AccountKeys.MICROSOFT, offline = false)),
    )
    private val accounts = MutableStateFlow<List<AccountView>?>(listOf(microsoft))
    private val removals = MutableStateFlow<Map<String, RemovalView>>(emptyMap())
    private val signedOut = CopyOnWriteArrayList<String>()
    private val removed = CopyOnWriteArrayList<String>()

    private val data = WidgetDataRegistry().apply {
        register(AccountKeys.Session, flowSource(session))
        register(AccountKeys.Accounts, flowSource(accounts))
        register(AccountKeys.Providers, flowSource(MutableStateFlow(listOf(ProviderView(AccountKeys.MICROSOFT, "Microsoft", ProviderView.DEVICE_CODE)))))
        register(AccountKeys.SignIns, flowSource(MutableStateFlow<Map<String, SignInFlow>>(emptyMap())))
        register(AccountKeys.Removals, flowSource(removals))
    }

    private val commands = WidgetCommandRegistry().apply {
        register(AccountKeys.SignOut, command {
            signedOut += "signed out"
            accounts.value = emptyList()
            session.value = SessionState.SignedOut
        })
        register(AccountKeys.RemoveAccount, command { request ->
            removed += request.providerKey
            removals.value += request.providerKey to RemovalView(request.ticket, RemovalOutcome.Removing)
            accounts.value = accounts.value.orEmpty().filterNot { it.providerKey == request.providerKey }
            removals.value += request.providerKey to RemovalView(request.ticket, RemovalOutcome.Moved)
        })
        register(AccountKeys.SignInMicrosoft, command { })
        register(AccountKeys.CancelSignIn, command { })
    }

    private val app: WidgetScope = koinApplication {
        modules(
            levelServicesModule,
            module {
                single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) }
            },
        )
    }.koin.get()

    /**
     * Draws [content] in a window and a composition with the window's question over it,
     * under [app] and its registries, and runs [steps] on the scene.
     */
    private fun drawn(
        content: @Composable () -> Unit,
        app: WidgetScope = this.app,
        data: WidgetDataRegistry = this.data,
        commands: WidgetCommandRegistry = this.commands,
        steps: (ImageComposeScene, () -> SignOutRequests) -> Unit,
    ) {
        var window: WidgetScope? = null
        onEventThread {
            val scene = ImageComposeScene(900, 900, density = Density(1f)) {
                ShippedLanguageProvider("en") {
                    NxTheme(Themes.default, dark = true) {
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
                steps(scene) { assertNotNull(assertNotNull(window).launcherServices.find<SignOutRequests>()) }
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
        var shown by mutableStateOf(true)
        drawn({ if (shown) ProfileSignInSectionWidget() }) { scene, requests ->
            scene.until("the Microsoft section") { clickable("account.signout.microsoft") }
            PuppetRegistry.click("account.signout.microsoft").getOrThrow()
            scene.until("the question asked") { requests().asking.value }
            assertTrue(removed.isEmpty(), "the last account is not removed before the answer")
            shown = false
            scene.until("the section gone") { !clickable("account.signout.microsoft") }
            assertTrue(requests().asking.value, "the question went with the widget that asked it")
            assertTrue(signedOut.isEmpty(), "nothing is signed out before the answer")
            PuppetRegistry.click("logout.confirm").getOrThrow()
            scene.until("the question closing") { !requests().asking.value }
            assertEquals(listOf("signed out"), signedOut.toList())
        }
    }

    @Test
    fun `with another account left, a section removes its own and asks nothing`() {
        accounts.value = listOf(microsoft, smartyCraft)
        drawn({ ProfileSignInSectionWidget() }) { scene, requests ->
            scene.until("the Microsoft section") { clickable("account.signout.microsoft") }
            PuppetRegistry.click("account.signout.microsoft").getOrThrow()
            scene.until("the account removed") { removed.isNotEmpty() }
            assertEquals(listOf(AccountKeys.MICROSOFT), removed.toList())
            assertFalse(requests().asking.value, "nothing to ask while an account is left to front the launcher")
            assertTrue(signedOut.isEmpty())
        }
    }

    /**
     * Two accounts listed, and a store that gives no face for them, as a keyring that
     * fails half way does. Removing the Microsoft one leaves nothing to front the
     * launcher, which is the case where the section asks for a full sign-out after the
     * removal and then leaves, its account being gone. Drawn over the real accounts
     * module, which must clear nothing more before the person answers.
     */
    private class TwoAccounts(private val microsoft: SessionData) : AccountStore {
        @Volatile var microsoftRemoved = false
        @Volatile var cleared = false
        private val smartyCraft = SessionData(playerName = "Steve", accessToken = "sc-token-sc-token")
        override fun load(): SessionData? = null
        override fun accountFor(providerId: String): SessionData? = when {
            cleared -> null
            providerId == AccountKeys.MICROSOFT && !microsoftRemoved -> microsoft
            providerId == AccountKeys.SMARTYCRAFT -> smartyCraft
            else -> null
        }
        override fun refreshStored(providerId: String, session: SessionData) = Unit
        override fun saveAccount(session: SessionData, providerId: String, makeActive: Boolean) = Unit
        override fun markTwoFactor(providerId: String) = Unit
        override fun clearTwoFactor(providerId: String) = Unit
        override fun listAccounts(): List<StoredAccount> = if (cleared) emptyList() else listOfNotNull(
            StoredAccount(AccountKeys.MICROSOFT, "ms", "Alex", microsoft.uuid, "Alex").takeUnless { microsoftRemoved },
            StoredAccount(AccountKeys.SMARTYCRAFT, "sc", "Steve", "", "Steve"),
        )
        override fun activeAccountId(): String? = null
        override fun primarySession(preferredProviderId: String?): SessionData? = null
        override fun loadSession(providerId: String, accountId: String): SessionData? = accountFor(providerId)
        override fun setActive(accountId: String) = Unit
        override fun removeAccount(providerId: String, accountId: String) {
            if (providerId == AccountKeys.MICROSOFT) microsoftRemoved = true
        }
        override fun clear() {
            cleared = true
        }
    }

    @Test
    fun `a removal that leaves no face asks the window's question after the section has gone, and clears nothing before the answer`() {
        val alex = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef", accessToken = "ms-token-ms-token")
        val store = TwoAccounts(alex)
        val keys = HostKeys(
            uiDataRegistry(
                activity      = MutableStateFlow(emptyList()),
                notifications = MutableStateFlow(emptyList()),
                doNotDisturb  = MutableStateFlow(false),
                sslBypass     = flowSource(MutableStateFlow(false)),
                background    = MutableStateFlow(BackgroundSettings()),
            ),
            uiCommandRegistry({}, {}, {}),
            ContributedServices(),
        )
        moduleKeys(keys, named = setOf(AccountsApi.ID), compiled = listOf(AccountsModule), jars = emptyList())
        val real: WidgetScope = koinApplication {
            modules(
                levelServicesModule,
                accountsEngineStandIns(store),
                module {
                    single {
                        WidgetScope.app(
                            keys.sources, keys.appServices(KoinLevelServicesHost.app(this), Dispatchers.Unconfined), Dispatchers.Unconfined,
                            keys.commands, access = LauncherHostAccess,
                        )
                    }
                },
            )
        }.koin.get()
        try {
            val sessions = assertNotNull(LauncherHostAccess.servicesOf(real)).get<SessionStore>()
            val start = System.nanoTime()
            while (sessions.state.value is SessionState.Resolving && System.nanoTime() - start < 5_000_000_000L) Thread.sleep(10)
            sessions.signIn(alex, AccountKeys.MICROSOFT)
            drawn({ ProfileSignInSectionWidget() }, real, keys.sources, keys.commands) { scene, requests ->
                scene.until("the Microsoft section") { clickable("account.signout.microsoft") }
                PuppetRegistry.click("account.signout.microsoft").getOrThrow()
                scene.until("the section leaving once its account is gone") {
                    store.microsoftRemoved && !clickable("account.signout.microsoft")
                }
                scene.until("the question asked") { requests().asking.value }
                assertFalse(store.cleared, "nothing else is cleared before the answer")
                assertNotNull(store.accountFor(AccountKeys.SMARTYCRAFT), "the account that is left stays")
                assertEquals("Alex", (sessions.state.value as? SessionState.SignedIn)?.session?.name, "and the launcher as it was")
                PuppetRegistry.click("logout.confirm").getOrThrow()
                scene.until("the question closing") { !requests().asking.value }
                scene.until("everything signed out") { store.cleared && sessions.state.value is SessionState.SignedOut }
            }
        } finally {
            keys.close()
        }
    }

    // The test above answers through the automation hook, which signs out with no
    // dialog drawn at all. This one answers the way a person does.
    @Test
    fun `the question is drawn over the window and its own yes signs out`() {
        val yes = NexiraStrings.shipped("en").text("navLogout")
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
            session.value = SessionState.SignedOut
            scene.until("the question closing") { !requests().asking.value }
            assertFalse(requests().asking.value)
            assertTrue(signedOut.isEmpty())
        }
    }
}
