package hivens.accounts

import hivens.accounts.api.AccountRemoval
import hivens.accounts.api.DeviceCodeSignIn
import hivens.accounts.api.FaceChoice
import hivens.accounts.api.PasswordSignIn
import hivens.accounts.api.RemovalOutcome
import hivens.accounts.api.RemovalView
import hivens.accounts.api.SessionState
import hivens.accounts.api.SignInCode
import hivens.accounts.api.SignInError
import hivens.accounts.api.SignInFlow
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Signing in by hand, through what the accounts module offers a widget: the flow says
 * where a sign-in stands and why it failed, and nothing else leaves the module.
 */
class SignInsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private inner class Accounts(settings: SettingsData = SettingsData()) {
        val store = MemoryAccounts()
        val settings = MemorySettings(settings)
        val host = RecordingHost()
        val provider = ScriptedProvider()
        val directory = AccountDirectory(store, this.settings, host, scope)
        val session = SessionStore(store, this.settings, provider, null, MutableStateFlow(Unit), host, scope, directory::refresh)
        val signIns = SignIns(
            registry  = AuthProviderRegistry(listOf(provider, OfflineAuthProvider())),
            password  = provider,
            offline   = OfflineAuthProvider(),
            accounts  = store,
            settings  = this.settings,
            host      = host,
            session   = session,
            directory = directory,
            scope     = scope,
        )

        init {
            await(session.state, "the startup sign-in") { it !is SessionState.Resolving }
        }

        fun password(login: String, password: String, remember: Boolean = true) =
            signIns.password("smartycraft", PasswordSignIn(login, password, remember))
    }

    private fun <T> await(flow: StateFlow<T>, what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(5.seconds) { flow.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${flow.value}", e)
        }
    }

    private fun SessionState.name(): String? = (this as? SessionState.SignedIn)?.session?.name

    private val steve = SessionData(playerName = "Steve", uuid = "", accessToken = "token-token-token")

    @Test
    fun `a name and a password sign in, keep the account when asked, and leave no flow behind`() {
        val accounts = Accounts()
        accounts.provider.logins += steve
        accounts.password("Steve", "secret")
        await(accounts.session.state, "the sign-in") { it.name() == "Steve" }
        assertEquals(steve, accounts.store.accountFor("smartycraft"), "kept")
        assertTrue(accounts.signIns.flows.value.isEmpty())
        assertEquals(listOf("login Steve@Industrial"), accounts.provider.asked.toList(), "for the provider's own server")
        await(accounts.directory.list, "the list read again") { it?.map { a -> a.name } == listOf("Steve") }
    }

    @Test
    fun `a sign-in not kept still signs the launcher in`() {
        val accounts = Accounts()
        accounts.provider.logins += steve
        accounts.password("Steve", "secret", remember = false)
        await(accounts.session.state, "the sign-in") { it.name() == "Steve" }
        assertNull(accounts.store.accountFor("smartycraft"), "not kept")
    }

    @Test
    fun `a sign-in lands on the face the person chose, not on the account just added`() {
        val accounts = Accounts(SettingsData(preferredFaceProvider = "microsoft"))
        accounts.store.byProvider["microsoft"] = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef", refreshToken = "r")
        accounts.provider.logins += steve
        accounts.password("Steve", "secret")
        await(accounts.session.state, "the sign-in") { it is SessionState.SignedIn }
        val signed = assertIs<SessionState.SignedIn>(accounts.session.state.value).session
        assertEquals("Alex", signed.name)
        assertEquals("microsoft", signed.providerKey)
    }

    @Test
    fun `a blank field fails as empty and asks the provider nothing`() {
        val accounts = Accounts()
        accounts.password("Steve", " ")
        assertEquals(SignInFlow.Failed(SignInError(SignInError.EMPTY)), accounts.signIns.flows.value["smartycraft"])
        assertTrue(accounts.provider.asked.isEmpty())
    }

    @Test
    fun `a provider this build does not carry fails as unavailable`() {
        val accounts = Accounts()
        accounts.signIns.password("elsewhere", PasswordSignIn("Steve", "secret", remember = true))
        assertEquals(SignInFlow.Failed(SignInError(SignInError.UNAVAILABLE)), accounts.signIns.flows.value["elsewhere"])
        assertTrue(accounts.provider.asked.isEmpty())
    }

    @Test
    fun `a refusal fails with the provider's own words`() {
        val accounts = Accounts()
        accounts.provider.logins += AuthException(AuthStatus.BAD_LOGIN, "API: Wrong password")
        accounts.password("Steve", "secret")
        val flow = await(accounts.signIns.flows, "the refusal") { it["smartycraft"] is SignInFlow.Failed }["smartycraft"]
        assertEquals(SignInFlow.Failed(SignInError(SignInError.REJECTED, "Wrong password")), flow)
        assertIs<SessionState.SignedOut>(accounts.session.state.value)
    }

    @Test
    fun `a second factor is asked for, a wrong code says so, and the right one signs in`() {
        val accounts = Accounts()
        accounts.provider.logins += TwoFactorRequiredException("uid-1", "Steve")
        accounts.provider.codes += AuthException(AuthStatus.WRONG_CODE, "wrong")
        accounts.provider.codes += steve.copy(twoFactor = true)
        accounts.password("Steve", "secret")
        await(accounts.signIns.flows, "the question") { it["smartycraft"] == SignInFlow.SecondFactor() }

        accounts.signIns.code(SignInCode("smartycraft", "000000"))
        await(accounts.signIns.flows, "the wrong code") {
            it["smartycraft"] == SignInFlow.SecondFactor(error = SignInError(SignInError.WRONG_CODE))
        }
        accounts.signIns.code(SignInCode("smartycraft", "123456"))
        await(accounts.session.state, "the sign-in") { it.name() == "Steve" }
        assertEquals(listOf("login Steve@Industrial", "code 000000 for uid-1", "code 123456 for uid-1"), accounts.provider.asked.toList())
        assertTrue(accounts.signIns.flows.value.isEmpty())
        accounts.signIns.code(SignInCode("smartycraft", "123456"))
        assertEquals(3, accounts.provider.asked.size, "a sign-in that ended holds nothing a code could finish")
    }

    @Test
    fun `a second factor the provider let lapse sends the form back to the start`() {
        val accounts = Accounts()
        accounts.provider.logins += TwoFactorRequiredException("uid-1", "Steve")
        accounts.provider.codes += AuthException(AuthStatus.TWO_FACTOR_EXPIRED, "expired")
        accounts.password("Steve", "secret")
        await(accounts.signIns.flows, "the question") { it["smartycraft"] is SignInFlow.SecondFactor }
        accounts.signIns.code(SignInCode("smartycraft", "123456"))
        await(accounts.signIns.flows, "the lapse") {
            it["smartycraft"] == SignInFlow.Failed(SignInError(SignInError.SECOND_FACTOR_EXPIRED))
        }
        accounts.signIns.code(SignInCode("smartycraft", "123456"))
        assertEquals(2, accounts.provider.asked.size, "a code with no sign-in waiting for it asks nothing")
    }

    @Test
    fun `a provider that cannot answer a second factor says so instead of asking`() {
        val accounts = Accounts()
        val plain = ScriptedProvider(secondFactor = false)
        plain.logins += TwoFactorRequiredException("uid-1", "Steve")
        val signIns = SignIns(
            AuthProviderRegistry(listOf(plain)), plain, OfflineAuthProvider(), accounts.store, accounts.settings,
            accounts.host, accounts.session, accounts.directory, scope,
        )
        signIns.password("smartycraft", PasswordSignIn("Steve", "secret", remember = true))
        await(signIns.flows, "the refusal") {
            it["smartycraft"] == SignInFlow.Failed(SignInError(SignInError.SECOND_FACTOR_UNSUPPORTED))
        }
    }

    @Test
    fun `a certificate the launcher does not trust is the host's question, and trusting it signs in`() {
        val accounts = Accounts()
        accounts.provider.logins += AuthException(AuthStatus.INTERNAL_ERROR, "certificate", isSslError = true)
        accounts.provider.logins += steve
        accounts.password("Steve", "secret")
        val start = System.nanoTime()
        while (accounts.host.certificateRetry == null && System.nanoTime() - start < 5_000_000_000L) Thread.sleep(10)
        assertNotNull(accounts.host.certificateRetry, "the host was asked")
        assertTrue(accounts.signIns.flows.value.isEmpty(), "the form shows no failure of its own while the question is asked")
        accounts.host.trust()
        await(accounts.session.state, "the sign-in after the grant") { it.name() == "Steve" }
    }

    @Test
    fun `cancelling a sign-in forgets how it ended`() {
        val accounts = Accounts()
        accounts.provider.logins += TwoFactorRequiredException("uid-1", "Steve")
        accounts.password("Steve", "secret")
        await(accounts.signIns.flows, "the question") { it["smartycraft"] is SignInFlow.SecondFactor }
        accounts.signIns.cancel("smartycraft")
        assertTrue(accounts.signIns.flows.value.isEmpty())
        accounts.signIns.code(SignInCode("smartycraft", "123456"))
        assertEquals(1, accounts.provider.asked.size, "the cancelled sign-in takes no code")
    }

    @Test
    fun `a device-code sign-in in a build with no such provider fails as unavailable`() {
        val accounts = Accounts()
        accounts.signIns.deviceCode(DeviceCodeSignIn(), "microsoft")
        assertEquals(SignInFlow.Failed(SignInError(SignInError.UNAVAILABLE)), accounts.signIns.flows.value["microsoft"])
    }

    @Test
    fun `removing one of two accounts moves the launcher to the other`() {
        val accounts = Accounts()
        accounts.store.byProvider["microsoft"] = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef", refreshToken = "r")
        accounts.store.byProvider["smartycraft"] = steve
        accounts.session.signIn(accounts.store.byProvider.getValue("microsoft"), "microsoft")
        accounts.directory.setFace(FaceChoice("microsoft"), accounts.session)
        await(accounts.directory.face, "the face") { it.providerKey == "microsoft" }

        accounts.directory.remove(AccountRemoval("microsoft", "t1"), accounts.session)
        await(accounts.directory.removals, "the removal") { it["microsoft"] == RemovalView("t1", RemovalOutcome.Moved) }
        assertEquals("Steve", accounts.session.state.value.name())
        assertNull(accounts.settings.current.preferredFaceProvider, "the face choice went with the account it named")
        await(accounts.directory.list, "the list read again") { it?.map { a -> a.providerKey } == listOf("smartycraft") }
    }

    // A keyring that fails half way lists two accounts and gives no face for them. The
    // removal must not take the account that is left, or the names, without asking.
    @Test
    fun `a removal that leaves no face clears nothing else and says so, for the sign-out question`() {
        val accounts = Accounts(SettingsData(offlinePlayerName = "Steve"))
        accounts.store.byProvider["microsoft"] = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef", refreshToken = "r")
        accounts.store.byProvider["smartycraft"] = steve
        accounts.session.signIn(accounts.store.byProvider.getValue("microsoft"), "microsoft")
        accounts.store.noFace = true

        accounts.directory.remove(AccountRemoval("microsoft", "t2"), accounts.session)
        await(accounts.directory.removals, "the removal") { it["microsoft"] == RemovalView("t2", RemovalOutcome.NoFaceLeft) }
        assertEquals(steve, accounts.store.accountFor("smartycraft"), "the account that is left stays")
        assertEquals("Steve", accounts.settings.current.offlinePlayerName, "and so does the offline name")
        assertEquals("Alex", accounts.session.state.value.name(), "the launcher stays as it was until the person answers")
    }

    @Test
    fun `after a sign-out the list is read with the face choice already gone`() {
        val accounts = Accounts(SettingsData(preferredFaceProvider = "microsoft"))
        accounts.store.byProvider["microsoft"] = SessionData(playerName = "Alex", uuid = "0123456789abcdef0123456789abcdef", refreshToken = "r")
        accounts.directory.refresh()
        await(accounts.directory.face, "the face read") { it.providerKey == "microsoft" }
        runBlocking { withTimeout(5.seconds) { accounts.session.signOut().join() } }
        await(accounts.directory.face, "the face choice gone from the list's view") { it.providerKey == null }
        await(accounts.directory.list, "the accounts gone") { it?.isEmpty() == true }
    }

    @Test
    fun `a keyring that will not open still lists the accounts by name`() {
        val accounts = Accounts()
        accounts.store.byProvider["smartycraft"] = steve.copy(balance = 7)
        accounts.store.lockedSecrets = true
        accounts.directory.refresh()
        val listed = await(accounts.directory.list, "the list") { it?.size == 1 }.orEmpty().single()
        assertEquals("Steve", listed.name)
        assertEquals(0, listed.balance, "what only the session knows is left out")
    }
}
