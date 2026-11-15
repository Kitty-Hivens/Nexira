package hivens.accounts

import hivens.accounts.api.SecondFactorQuestion
import hivens.auth.AuthProviderRegistry
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A launch that waits on a second factor, answered by the accounts module: it signs
 * the stored account in for the launch, asks for a code only when the provider wants
 * one, and hands the session back to the launch. A widget sees the question, never
 * the session or the password it was signed in with.
 */
class LaunchSecondFactorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private val gate = TwoFactorLaunchGate()
    private val store = MemoryAccounts()
    private val provider = ScriptedProvider()
    private val relaunched = CopyOnWriteArrayList<SessionData>()

    private val stored = SessionData(playerName = "Steve", accessToken = "old-token-old", cachedPassword = "secret", twoFactor = true)

    private fun asking() = LaunchSecondFactor(gate, store, AuthProviderRegistry(listOf(provider)), provider, scope)

    private fun <T> await(flow: StateFlow<T>, what: String, predicate: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(5.seconds) { flow.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("$what never came: last was ${flow.value}", e)
        }
    }

    private fun awaitTrue(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (!check()) {
            if (System.nanoTime() > until) throw AssertionError("$what never happened")
            Thread.sleep(10)
        }
    }

    @Test
    fun `a demanded code is asked for, a wrong one says so, and the right one relaunches with its session`() {
        store.byProvider["smartycraft"] = stored
        provider.logins += TwoFactorRequiredException("uid-7", "Steve")
        provider.codes += AuthException(AuthStatus.WRONG_CODE, "wrong code")
        val fresh = SessionData(playerName = "Steve", accessToken = "new-token-new", twoFactor = true)
        provider.codes += fresh
        val asking = asking()
        gate.request("Industrial", "smartycraft", "Industrial") { relaunched += it }

        await(asking.question, "the question") { it == SecondFactorQuestion("Industrial", "smartycraft") }
        asking.answer("000000")
        await(asking.question, "the wrong code") { it?.error == "wrong code" && !it.checking }
        asking.answer("123456")
        awaitTrue("the relaunch") { relaunched.isNotEmpty() }
        assertEquals(fresh, relaunched.single())
        assertEquals(fresh, store.accountFor("smartycraft"), "the session the code bought is kept")
        await(asking.question, "the question gone") { it == null }
        assertEquals(listOf("login Steve@Industrial", "code 000000 for uid-7", "code 123456 for uid-7"), provider.asked.toList())
    }

    @Test
    fun `an account that no longer asks goes straight through, released only on that evidence`() {
        store.byProvider["smartycraft"] = stored
        provider.logins += SessionData(playerName = "Steve", accessToken = "new-token-new")
        asking()
        gate.request("Industrial", "smartycraft", "Industrial") { relaunched += it }
        awaitTrue("the relaunch") { relaunched.isNotEmpty() }
        assertTrue(relaunched.single().mintedNow, "minted for the launch")
        assertEquals(listOf("smartycraft"), store.cleared.toList())
    }

    @Test
    fun `with nothing stored to sign in with, the launch is given up rather than asked about`() {
        val asking = asking()
        gate.request("Industrial", "smartycraft", "Industrial") { relaunched += it }
        await(gate.pending, "the launch given up") { it == null }
        assertNull(asking.question.value)
        assertTrue(relaunched.isEmpty())
    }

    @Test
    fun `dismissing gives the launch up`() {
        store.byProvider["smartycraft"] = stored
        provider.logins += TwoFactorRequiredException("uid-7", "Steve")
        val asking = asking()
        gate.request("Industrial", "smartycraft", "Industrial") { relaunched += it }
        await(asking.question, "the question") { it != null }
        asking.dismiss()
        await(asking.question, "the question gone") { it == null }
        assertNull(gate.pending.value)
        assertTrue(relaunched.isEmpty())
    }

    @Test
    fun `closed, it stops answering the gate`() {
        store.byProvider["smartycraft"] = stored
        val asking = asking()
        asking.close()
        gate.request("Industrial", "smartycraft", "Industrial") { relaunched += it }
        Thread.sleep(200)
        assertTrue(provider.asked.isEmpty(), "a closed one signed in for a launch")
        assertNull(asking.question.value)
    }

    @Test
    fun `with no gate nothing is ever asked`() {
        val asking = LaunchSecondFactor(null, store, AuthProviderRegistry(listOf(provider)), provider, scope)
        asking.answer("123456")
        asking.dismiss()
        assertNull(asking.question.value)
        assertTrue(provider.asked.isEmpty())
    }
}
