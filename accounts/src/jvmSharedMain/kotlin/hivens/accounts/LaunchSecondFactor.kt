package hivens.accounts

import hivens.accounts.api.SecondFactorQuestion
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.core.api.TwoFactorRequiredException
import hivens.core.diag.ActionRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Answers a launch that waits on a second factor: signs the stored account of the
 * provider the launch names in for the launch's server, asks the person for the code when the provider wants
 * one, and hands the session that comes of it back to the launch through [gate].
 *
 * The flow is one round trip. A login against the target's server provokes the
 * demand and yields the `uid` the code must be signed against, the code unlocks the
 * session that came with that demand, and the session is saved and returned to the
 * waiting relaunch. An account that has since dropped its second factor logs in
 * cleanly and goes straight through, with the stored flag cleared so background sync
 * stops treating it as gated.
 *
 * A login can also come back without a demand because the provider answered from its
 * own short cache, holding the session a code unlocked moments ago. That session is
 * the one to relaunch with, and it says nothing about the account having dropped its
 * second factor: see [provesNoSecondFactor].
 *
 * What a widget sees is [question], which carries no secret: what is launched, and
 * where the code stands. With no [gate], a host that runs no launches, nothing is
 * ever asked.
 */
class LaunchSecondFactor(
    private val gate: TwoFactorLaunchGate?,
    private val accounts: AccountStore,
    private val registry: AuthProviderRegistry,
    /** The provider for a launch whose provider the registry does not carry. */
    private val auth: AuthProvider,
    private val scope: CoroutineScope,
    private val accountsChanged: () -> Unit = {},
) : AutoCloseable {
    private val _question = MutableStateFlow<SecondFactorQuestion?>(null)

    /** The code the person is asked for, or null while nothing is asked. */
    val question: StateFlow<SecondFactorQuestion?> = _question.asStateFlow()

    // The uid the asked code is signed against, for the request that asked it.
    @Volatile private var asked: Pair<TwoFactorLaunchGate.Request, String>? = null

    // A second launch asking the gate starts this over, and a cancellation is not a
    // failure: read as one, it called gate.cancel() on that newer request, so neither
    // launch ever happened.
    private val asking: Job? = gate?.let { slot -> scope.launch { slot.pending.collectLatest { request -> ask(slot, request) } } }

    /** The provider a launch's request signs in with. */
    private fun providerFor(request: TwoFactorLaunchGate.Request): AuthProvider = registry[request.provider] ?: auth

    /** Stops answering the gate. A launch still waiting stays waiting for whoever answers next. */
    override fun close() {
        asking?.cancel()
    }

    private suspend fun ask(gate: TwoFactorLaunchGate, request: TwoFactorLaunchGate.Request?) {
        asked = null
        _question.value = null
        if (request == null) return
        val provider = request.provider
        val stored = withContext(Dispatchers.IO) { accounts.accountFor(provider) }
        val pass = stored?.cachedPassword
        if (stored == null || pass.isNullOrEmpty()) {
            // Nothing to sign in with: a question that could never be answered is
            // worse than saying so and letting the launch fail visibly.
            ActionRing.record("2FA launch of ${request.label}: no stored credentials to sign in with")
            gate.cancel()
            return
        }
        // The account save sits inside the same handling: a vault write that throws
        // would otherwise leave the gate pending with no question.
        val fresh = try {
            withContext(Dispatchers.IO) {
                val session = providerFor(request).login(stored.playerName, pass, request.serverId)
                // Not made the active account: a launch signing in is no reason to
                // change which account fronts the launcher.
                accounts.saveAccount(session, provider, makeActive = false)
                // Passing twoFactor = false through saveAccount never cleared the
                // gate, because saveAccount ORs the stored value back in by design, so
                // the release is its own call, made on evidence only.
                if (session.provesNoSecondFactor()) accounts.clearTwoFactor(provider)
                session
            }
        } catch (e: CancellationException) {
            throw e
        } catch (failure: Exception) {
            if (failure is TwoFactorRequiredException) {
                asked = request to failure.uid.orEmpty()
                _question.value = SecondFactorQuestion(request.label, request.provider)
            } else {
                ActionRing.record("2FA launch of ${request.label} could not start: ${failure.message?.take(60)}")
                gate.cancel()
            }
            return
        }
        accountsChanged()
        gate.resume(fresh.copy(mintedNow = true))
    }

    /** Answers the asked question with [code]. Nothing happens while nothing is asked or a code is with the provider. */
    fun answer(code: String) {
        val gate = gate ?: return
        val (request, uid) = asked ?: return
        if (_question.value?.checking != false) return
        _question.update { it?.copy(checking = true, error = null) }
        val provider = request.provider
        scope.launch {
            val stored = withContext(Dispatchers.IO) { accounts.accountFor(provider) }
            // The save sits inside the same handling: a vault write can fail, and
            // letting that escape left the question open with no error and the gate stuck.
            val outcome = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        providerFor(request).completeTwoFactor(
                            username = stored?.playerName.orEmpty(),
                            password = stored?.cachedPassword.orEmpty(),
                            serverId = request.serverId,
                            uid = uid,
                            code = code,
                        ).also { accounts.saveAccount(it, provider, makeActive = false) }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            outcome
                .onSuccess { session ->
                    accountsChanged()
                    gate.resume(session)
                }
                .onFailure { failure -> _question.update { it?.copy(checking = false, error = failure.message) } }
        }
    }

    /** Gives the waiting launch up. */
    fun dismiss() {
        gate?.cancel()
    }
}
