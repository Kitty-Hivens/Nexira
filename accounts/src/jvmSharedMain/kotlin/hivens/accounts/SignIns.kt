package hivens.accounts

import hivens.accounts.api.DeviceCodeSignIn
import hivens.accounts.api.PasswordSignIn
import hivens.accounts.api.SignInCode
import hivens.accounts.api.SignInError
import hivens.accounts.api.SignInFlow
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.DeviceCodeAuthProvider
import hivens.auth.OfflineAuthProvider
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.AuthStatus
import hivens.core.data.SessionData
import hivens.core.diag.ActionRing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Every way a person signs in by hand, as one flow per provider that a widget reads
 * and moves with commands: a name and a password with the second factor the provider
 * may ask for, a device code, or an offline name.
 *
 * Providers are found by what they can do and by their key in [registry], never by
 * name: a password sign-in goes to the provider of the key it is asked under and
 * signs in for that provider's own default server.
 *
 * A sign-in that works is kept as an account when it asks to be, and then the launcher
 * signs in as the face the stored accounts give, the person's own choice among them.
 * The password stays in here and is never handed out: a flow says where it stands and
 * why it failed, and nothing more. It is let go of as soon as the sign-in ends, done
 * or failed, and kept past that only while the provider waits for a second factor or
 * the person is asked about a certificate, until they answer, give up, or sign in again.
 *
 * One sign-in per provider at a time: a second one asked for while the first is with
 * the provider is dropped, since on SmartyCraft each login retires the uid the one
 * before it got.
 */
class SignIns(
    private val registry: AuthProviderRegistry,
    /** The provider bound for a password sign-in where the registry carries none of that key. */
    private val password: AuthProvider,
    private val offline: OfflineAuthProvider,
    private val accounts: AccountStore,
    private val settings: ISettingsService,
    private val host: AccountsHost,
    private val session: SessionStore,
    private val directory: AccountDirectory,
    private val scope: CoroutineScope,
) {
    private val _flows = MutableStateFlow<Map<String, SignInFlow>>(emptyMap())

    /** The sign-ins under way or failed, by provider key. */
    val flows: StateFlow<Map<String, SignInFlow>> = _flows.asStateFlow()

    /** A second factor a provider asked for, with what it is signed against. */
    private class Pending(val uid: String, val login: String, val password: String, val serverId: String, val remember: Boolean)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** A sign-in waiting on the person's answer about a certificate, to run again once they trust it. */
    private val retries = ConcurrentHashMap<String, PasswordSignIn>()

    private val jobs = ConcurrentHashMap<String, Job>()

    private fun put(provider: String, flow: SignInFlow?) =
        _flows.update { if (flow == null) it - provider else it + (provider to flow) }

    private fun busy(provider: String): Boolean = when (val flow = _flows.value[provider]) {
        SignInFlow.Working -> true
        is SignInFlow.DeviceCode -> true
        is SignInFlow.SecondFactor -> flow.checking
        else -> false
    }

    /** The provider of [key] that takes a password, or null where this build carries none. */
    private fun passwordProvider(key: String): AuthProvider? =
        (registry[key] ?: password.takeIf { it.id == key })?.takeIf { !it.capabilities.supportsDeviceCode && it.id != OfflineAuthProvider.PROVIDER_KEY }

    /** Signs in with a name and a password at the provider of [providerKey]. */
    fun password(providerKey: String, request: PasswordSignIn) {
        if (busy(providerKey)) return
        val provider = passwordProvider(providerKey)
        if (provider == null) {
            put(providerKey, SignInFlow.Failed(SignInError(SignInError.UNAVAILABLE)))
            return
        }
        if (request.login.isBlank() || request.password.isBlank()) {
            put(providerKey, SignInFlow.Failed(SignInError(SignInError.EMPTY)))
            return
        }
        pending.remove(providerKey)
        retries.remove(providerKey)
        put(providerKey, SignInFlow.Working)
        // No account name in any of these: the ring pre-fills a crash report and is
        // copied into the diagnostic bundle, and what it is for is what happened.
        ActionRing.record("Login attempt")
        val serverId = provider.defaultServerId
        jobs[providerKey] = scope.launch {
            try {
                val signed = withContext(Dispatchers.IO) {
                    provider.login(request.login, request.password, serverId)
                        .also { if (request.remember) accounts.saveAccount(it, providerKey) }
                }
                ActionRing.record("Login OK")
                finish(providerKey, signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TwoFactorRequiredException) {
                if (provider.capabilities.supports2FA) {
                    ActionRing.record("Login: 2FA required, prompting for code")
                    pending[providerKey] = Pending(e.uid.orEmpty(), request.login, request.password, serverId, request.remember)
                    put(providerKey, SignInFlow.SecondFactor())
                } else {
                    // The provider raised a second-factor demand it cannot complete.
                    ActionRing.record("Login: 2FA detected, rejected (unsupported on this provider)")
                    put(providerKey, SignInFlow.Failed(SignInError(SignInError.SECOND_FACTOR_UNSUPPORTED)))
                }
            } catch (e: AuthException) {
                ActionRing.record("Login failed (auth): ssl=${e.isSslError} msg=${e.message?.take(80)}")
                // The certificate question is the host's to ask, and the retry rides
                // along: accepting it means the person wanted to sign in. The request
                // waits in here, and the host is handed only the provider to retry.
                retries[providerKey] = request
                if (e.isSslError && host.certificateRefused { retry(providerKey) }) {
                    put(providerKey, null)
                } else {
                    retries.remove(providerKey)
                    val said = e.message?.replace("java.lang.Exception: ", "")?.substringAfter("API: ")
                    put(providerKey, SignInFlow.Failed(SignInError(SignInError.REJECTED, said)))
                }
            } catch (e: Exception) {
                ActionRing.record("Login failed (generic): msg=${e.message?.take(80)}")
                put(providerKey, SignInFlow.Failed(SignInError(SignInError.FAILED, e.message)))
            } finally {
                jobs.remove(providerKey)
            }
        }
    }

    /** Runs again the sign-in a certificate question held, once the person trusts it. */
    private fun retry(providerKey: String) {
        val request = retries.remove(providerKey) ?: return
        password(providerKey, request)
    }

    /** Answers the second factor the sign-in with [SignInCode.providerKey] stopped for. */
    fun code(answer: SignInCode) {
        val providerKey = answer.providerKey
        val waiting = pending[providerKey] ?: return
        val provider = passwordProvider(providerKey) ?: return
        if (busy(providerKey)) return
        put(providerKey, SignInFlow.SecondFactor(checking = true))
        jobs[providerKey] = scope.launch {
            try {
                val signed = withContext(Dispatchers.IO) {
                    provider.completeTwoFactor(
                        username = waiting.login, password = waiting.password,
                        serverId = waiting.serverId, uid = waiting.uid, code = answer.code,
                    ).also { if (waiting.remember) accounts.saveAccount(it, providerKey) }
                }
                ActionRing.record("Login OK after 2FA")
                pending.remove(providerKey)
                finish(providerKey, signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthException) {
                ActionRing.record("2FA verify failed: status=${e.status}")
                when (e.status) {
                    AuthStatus.WRONG_CODE -> put(providerKey, SignInFlow.SecondFactor(error = SignInError(SignInError.WRONG_CODE)))
                    // The sign-in is gone on the provider's side, so the form starts over.
                    AuthStatus.TWO_FACTOR_EXPIRED -> {
                        pending.remove(providerKey)
                        put(providerKey, SignInFlow.Failed(SignInError(SignInError.SECOND_FACTOR_EXPIRED)))
                    }
                    else -> put(providerKey, SignInFlow.SecondFactor(error = SignInError(SignInError.FAILED, e.message)))
                }
            } catch (e: Exception) {
                put(providerKey, SignInFlow.SecondFactor(error = SignInError(SignInError.FAILED, e.message)))
            } finally {
                jobs.remove(providerKey)
            }
        }
    }

    /** Signs in offline as [name], and keeps the name so a restart signs the same identity in. */
    fun offline(name: String) {
        val provider = OfflineAuthProvider.PROVIDER_KEY
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            put(provider, SignInFlow.Failed(SignInError(SignInError.EMPTY)))
            return
        }
        put(provider, null)
        scope.launch {
            // Caught like the sign-in above: a settings file that could not be written
            // is a failed sign-in, not a crash.
            val signed = try {
                withContext(Dispatchers.IO) {
                    offline.login(trimmed, "", "").also {
                        settings.updateSettings { it.copy(offlinePlayerName = trimmed) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActionRing.record("Play offline failed: msg=${e.message?.take(80)}")
                put(provider, SignInFlow.Failed(SignInError(SignInError.FAILED, e.message)))
                return@launch
            }
            ActionRing.record("Play offline")
            session.signIn(signed, provider)
        }
    }

    /**
     * Starts a device-code sign-in with the provider of [providerKey], which has to
     * offer one: found by what it can do, so a build that carries none fails the flow
     * as unavailable. The request can fail before any code is shown (a bad client id,
     * no network), which fails the flow, and the wait that follows keeps the code on
     * show with its error.
     */
    fun deviceCode(request: DeviceCodeSignIn, providerKey: String) {
        val found = registry[providerKey]?.takeIf { it.capabilities.supportsDeviceCode }
        val provider = found as? DeviceCodeAuthProvider
        if (found == null || provider == null) {
            put(providerKey, SignInFlow.Failed(SignInError(SignInError.UNAVAILABLE)))
            return
        }
        if (busy(providerKey)) return
        put(providerKey, SignInFlow.Working)
        jobs[providerKey] = scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { provider.requestDeviceCode() }
                put(providerKey, SignInFlow.DeviceCode(challenge.userCode, challenge.verificationUri))
                val signed = withContext(Dispatchers.IO) { provider.awaitToken(challenge) }
                // Off the caller's thread like the two calls above: it writes the keyring,
                // and a Secret Service that asks for an unlock would hold it for as long
                // as its prompt stays up.
                if (request.remember) withContext(Dispatchers.IO) { accounts.saveAccount(signed, providerKey) }
                ActionRing.record("Device-code sign-in OK")
                finish(providerKey, signed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Device-code sign-in failed", e)
                ActionRing.record("Device-code sign-in failed: ${e.message?.take(120)}")
                val error = SignInError(SignInError.FAILED, e.message)
                _flows.update { all ->
                    val shown = all[providerKey] as? SignInFlow.DeviceCode
                    all + (providerKey to (shown?.copy(error = error) ?: SignInFlow.Failed(error)))
                }
            } finally {
                jobs.remove(providerKey)
            }
        }
    }

    /** Stops the sign-in with [provider], lets go of what it held, and forgets how it ended. */
    fun cancel(provider: String) {
        jobs.remove(provider)?.cancel()
        pending.remove(provider)
        retries.remove(provider)
        put(provider, null)
    }

    /**
     * Signs the launcher in after a sign-in worked: as the face the stored accounts
     * give, which is the person's own choice among them, or as [signed] where none
     * is stored, a sign-in that was not kept.
     */
    private suspend fun finish(provider: String, signed: SessionData) {
        val face = withContext(Dispatchers.IO) { directory.faceSession()?.let { it to session.providerOf(it) } }
        if (face != null) session.signIn(face.first, face.second) else session.signIn(signed, provider)
        put(provider, null)
        directory.refresh()
    }

    private companion object {
        val log = LoggerFactory.getLogger(SignIns::class.java)
    }
}
