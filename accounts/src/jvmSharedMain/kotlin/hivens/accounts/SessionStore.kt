@file:OptIn(HostApi::class)

package hivens.accounts

import hivens.accounts.api.SessionState
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.OfflineAuthProvider
import hivens.auth.RefreshableAuthProvider
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.core.diag.ActionRing
import hivens.widget.api.HostApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Who the launcher is signed in as, and every way that changes: the sign-in it
 * makes on its own at startup, a sign-in made by hand, and a sign-out.
 *
 * One for the app, because the accounts are the app's. Every window and every
 * composition reads the same session, so a second composition neither signs in
 * again, which for a stored SmartyCraft account is a login that invalidates the
 * first one's session, nor stays signed in after another signed out.
 *
 * It holds the session with its tokens, so it is a host service: the host and the
 * modules compiled into it reach it, a widget and a jar module read [state], which
 * carries none of them.
 *
 * The startup sign-in is [AutoLoginCoordinator]'s decision, applied here when the
 * store is made: a network failure retries on its backoff for as long as the store
 * lives, so a launcher left open signs itself in once the network is back, while a
 * rejection, missing credentials or an untrusted certificate stop. A change to the
 * certificate grants starts it over at once with a fresh backoff, since a grant is
 * the person's own decision. A sign-in made by hand always wins over one the store
 * makes: every write the startup sign-in makes leaves a signed-in state alone, in
 * one atomic step, whichever thread it lands on.
 *
 * Nothing here fails quietly. A startup sign-in that throws leaves the launcher
 * signed out rather than loading for ever, and a sign-out that throws leaves it as
 * it was. Either is logged and told to [host].
 *
 * Asking whether to sign out is not this store's business: the command signs out,
 * and whatever offers it asks first.
 */
class SessionStore(
    private val accounts: AccountStore,
    private val settings: ISettingsService,
    private val auth: AuthProvider,
    private val refreshable: RefreshableAuthProvider?,
    certificateGrants: StateFlow<Any?>,
    private val host: AccountsHost,
    parent: CoroutineScope,
    /** Told whenever the stored accounts may have changed, so a list of them reads the store again. */
    private val accountsChanged: () -> Unit = {},
) : LaunchSessions, AutoCloseable {

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    /** The session in full, with the provider it came from. Null while nobody is signed in. */
    private class Signed(val session: SessionData, val provider: String)

    private val lock = Any()

    // Both written under [lock] in one step, so a reader of either never sees the other behind.
    private var current: Signed? = null
    private var resolving = true
    private val _state = MutableStateFlow<SessionState>(SessionState.Resolving)

    /** Who the launcher is signed in as, that nobody is, or that startup is still finding out. Nothing secret. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** The signed-in session with its tokens, or null when nobody is signed in or startup is still finding out. */
    val session: SessionData? get() = synchronized(lock) { current?.session }

    override val identity: LaunchIdentity
        get() = synchronized(lock) {
            when {
                current != null -> LaunchIdentity.SignedIn(checkNotNull(current).session)
                resolving -> LaunchIdentity.Resolving
                else -> LaunchIdentity.Nobody
            }
        }

    init {
        scope.launch { certificateGrants.collectLatest { signInFromStore() } }
    }

    /**
     * Makes [session] the one the launcher is signed in as, as an account of
     * [provider]. Wins over anything the startup sign-in finds.
     */
    fun signIn(session: SessionData, provider: String = providerGuess(session)) {
        set(Signed(session, provider), resolved = true)
        host.cleared(AccountTrouble.SignIn)
    }

    /**
     * Signs out of everything: clears every stored account, the face choice and the
     * offline name, then leaves the launcher signed out.
     *
     * The face choice names a provider that no longer has an account, and nothing
     * shows the setting again until two accounts are back, so it would decide the
     * face of a session set up long after. The offline name goes too, whichever
     * identity was in front: it is the only record of the offline identity, and
     * startup signs that identity back in from it when no account is stored, so
     * keeping it had the next start sign straight back in under a name the person
     * had just signed out of. The offline mode switch stays, since it says how to
     * play rather than who plays.
     *
     * Off the calling thread, since every secret the store deletes is a keyring
     * call, and the returned job is that work. When the accounts cannot be cleared
     * the launcher stays signed in, which is true, and says so. When they are gone
     * and only the names stayed, it signs out, since the accounts are what a session
     * stands on, and says what is left: the offline name the next start may sign in
     * under. Each attempt says its own outcome: one that clears what an earlier one
     * could not takes that one's notice back.
     */
    fun signOut(): Job =
        scope.launch {
            try {
                withContext(Dispatchers.IO) { accounts.clear() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Signing out failed: the stored accounts could not be cleared", e)
                host.failed(AccountTrouble.SignOut, e)
                // Some may have gone before the failure, so the list is read again.
                accountsChanged()
                return@launch
            }
            host.cleared(AccountTrouble.SignOut)
            try {
                withContext(Dispatchers.IO) {
                    settings.updateSettings { it.copy(preferredFaceProvider = null, offlinePlayerName = null) }
                }
                host.cleared(AccountTrouble.NameKept)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Signed out, but the face choice and the offline name could not be cleared", e)
                host.failed(AccountTrouble.NameKept, e)
            }
            set(null, resolved = true)
            // After the names, so the list is read with the face choice already gone.
            accountsChanged()
        }

    /**
     * The provider [session] most likely came from, by its shape alone: an offline
     * identity is offline's, a session that carries a refresh token is the refreshing
     * provider's, and any other is the password provider's. For a caller that cannot
     * ask the store, [providerOf] asks it.
     */
    fun providerGuess(session: SessionData): String = when {
        session.offline -> OfflineAuthProvider.PROVIDER_KEY
        session.refreshToken != null -> (refreshable as? AuthProvider)?.id ?: auth.id
        else -> auth.id
    }

    /** The provider whose stored account [session] is, or [providerGuess] where none matches. Reads the store. */
    fun providerOf(session: SessionData): String = providerOf(session, accounts, ::providerGuess)

    /** Stops the startup sign-in and a sign-out still under way. */
    override fun close() {
        scope.cancel()
    }

    private fun set(signed: Signed?, resolved: Boolean) = synchronized(lock) {
        current = signed
        if (resolved) resolving = false
        _state.value = viewOf(signed)
    }

    private fun viewOf(signed: Signed?): SessionState = when {
        signed != null -> SessionState.SignedIn(signed.session.toView(signed.provider))
        resolving -> SessionState.Resolving
        else -> SessionState.SignedOut
    }

    private suspend fun signInFromStore() {
        var attempt = 0
        while (synchronized(lock) { current == null }) {
            val resolution = try {
                resolve()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("The startup sign-in failed, the launcher stays signed out", e)
                host.failed(AccountTrouble.SignIn, e)
                settleSignedOut()
                return
            }
            when (resolution) {
                is Resolved.Success -> {
                    // The form stays usable while a pass is in flight, so the person may
                    // have signed in by hand while this one waited on the network.
                    val taken = synchronized(lock) {
                        (current == null).also { if (it) set(Signed(resolution.session, resolution.provider), resolved = true) }
                    }
                    if (taken) host.cleared(AccountTrouble.SignIn) else ActionRing.record("Auto-login answered after a manual sign-in; the manual one stays")
                    return
                }
                Resolved.Stop -> {
                    settleSignedOut()
                    return
                }
                Resolved.NetworkDown -> {
                    // Resolving covers only the first attempt. After it the form is
                    // usable while the retries go on quietly, and a sign-in made by
                    // hand meanwhile stays.
                    synchronized(lock) { if (current == null && resolving) set(null, resolved = true) }
                    val delayMs = AutoLoginCoordinator.retryDelayMs(attempt)
                    attempt += 1
                    ActionRing.record("Auto-login: network down, retry #$attempt in ${delayMs / 1000}s")
                    delay(delayMs.milliseconds)
                }
            }
        }
    }

    // Signed out, unless somebody signed in by hand meanwhile.
    private fun settleSignedOut() {
        val kept = synchronized(lock) {
            (current != null).also { if (!it) set(null, resolved = true) }
        }
        if (kept) ActionRing.record("Auto-login found no session after a manual sign-in; the manual one stays")
    }

    private sealed interface Resolved {
        class Success(val session: SessionData, val provider: String) : Resolved
        data object NetworkDown : Resolved
        data object Stop : Resolved
    }

    /** One pass: what the stored account and the settings say, with the store told what the pass changed. */
    private suspend fun resolve(): Resolved {
        val current = withContext(Dispatchers.IO) { settings.getSettings() }
        val saved = withContext(Dispatchers.IO) { accounts.primarySession(current.preferredFaceProvider) }
        val resolution = withContext(Dispatchers.IO) {
            AutoLoginCoordinator.resolveSession(
                settings    = current,
                saved       = saved,
                authService = auth,
                msaProvider = refreshable,
            )
        }
        return when (resolution) {
            is AutoLoginCoordinator.Resolution.Success -> {
                keep(resolution, saved)
                Resolved.Success(resolution.session, withContext(Dispatchers.IO) { providerOf(resolution.session) })
            }
            AutoLoginCoordinator.Resolution.NetworkDown -> Resolved.NetworkDown
            // The certificate decision belongs to the person. Dropping to the sign-in
            // form routes them to the prompt that asks for it, which also grants the
            // bypass and restores the silent sign-in from the next start.
            AutoLoginCoordinator.Resolution.NoCredentials,
            AutoLoginCoordinator.Resolution.Rejected,
            AutoLoginCoordinator.Resolution.CertificateUntrusted -> Resolved.Stop
        }
    }

    /** Writes back to the account store what a successful startup sign-in changed in it. */
    private suspend fun keep(resolution: AutoLoginCoordinator.Resolution.Success, saved: SessionData?) {
        val session = resolution.session
        // The account the pass signed in from, the same provider as what it stored.
        val provider = withContext(Dispatchers.IO) { saved?.let { providerOf(it) } ?: providerOf(session) }
        var changed = false
        // A silent Microsoft refresh rotates the refresh token, and the next start
        // has to use the fresh one. A rotation is not a choice of account, so it
        // never takes the active slot: it used to, and "active" came to mean
        // whichever account the boot happened to refresh.
        if (session.refreshToken != null && session.refreshToken != saved?.refreshToken) {
            withContext(Dispatchers.IO) {
                accounts.saveAccount(session, provider, makeActive = false)
            }
            changed = true
        }
        // The sign-in retired the uid the store holds, and whatever is signed later
        // from the stored account, a skin upload among them, needs the one it minted.
        if (resolution.signedIn) {
            withContext(Dispatchers.IO) {
                runCatching {
                    accounts.refreshStored(provider, session)
                }.onFailure {
                    log.warn("Auto-login could not record the refreshed session", it)
                }
            }
            changed = true
        }
        // The sign-in that just ran met the second factor, and it is the only thing
        // that could have said so. Unrecorded, the next start would spend another
        // login, and each one invalidates the session the player has in hand.
        if (session.twoFactor && saved?.twoFactor != true) {
            withContext(Dispatchers.IO) { accounts.markTwoFactor(provider) }
            ActionRing.record("Auto-login met the second factor: the account is marked")
            changed = true
        }
        if (changed) accountsChanged()
    }

    private companion object {
        val log = LoggerFactory.getLogger(SessionStore::class.java)
    }
}
