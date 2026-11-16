package hivens.accounts

import hivens.accounts.api.AccountRemoval
import hivens.accounts.api.AccountView
import hivens.accounts.api.FaceChoice
import hivens.accounts.api.RemovalOutcome
import hivens.accounts.api.RemovalView
import hivens.auth.AccountStore
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.core.data.releasingFace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The stored accounts as a list a widget reads, which face fronts the launcher, and
 * whether a sign-in keeps its account. Read again whenever the accounts may have
 * changed, which every part of this module that writes the store says through
 * [refresh]: the store itself tells nobody.
 *
 * The list is read from the account metadata first, names and uuids, and published
 * at once, so a keyring that is locked or slow leaves the accounts listed. What only
 * a stored session knows (the balance, the clan, whether it holds a token) is read
 * after, account by account, and an account whose session cannot be read stays as
 * its metadata says.
 */
class AccountDirectory(
    private val accounts: AccountStore,
    private val settings: ISettingsService,
    private val host: AccountsHost,
    private val scope: CoroutineScope,
) {
    private val _accounts = MutableStateFlow<List<AccountView>?>(null)

    /** Every stored account, as of the last read. Null until the first read lands. */
    val list: StateFlow<List<AccountView>?> = _accounts.asStateFlow()

    private val _face = MutableStateFlow(FaceChoice())

    /** The provider the person named to front the launcher, or none. */
    val face: StateFlow<FaceChoice> = _face.asStateFlow()

    private val _remember = MutableStateFlow(true)

    /** Whether a sign-in keeps its account, seeded from the person's last choice. */
    val remember: StateFlow<Boolean> = _remember.asStateFlow()

    // One read at a time, and the last one asked for always runs, so a change made
    // while a read is under way is never answered by the read that started before it.
    private val reading = Mutex()

    // The providers whose account is being removed, so a second press while the
    // first is still at the store does not find one account left and remove that too.
    private val removing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // A face choice and a removal each write the choice and sign the session in, so they run one
    // at a time in the order they were asked for: a removal right behind a choice sees it made.
    private val facing = Mutex()

    private val _removals = MutableStateFlow<Map<String, RemovalView>>(emptyMap())

    /** The latest removal of each provider's account and how it ended. */
    val removals: StateFlow<Map<String, RemovalView>> = _removals.asStateFlow()

    init {
        refresh()
    }

    /** Reads the accounts and the choices again. */
    fun refresh() {
        scope.launch {
            reading.withLock {
                try {
                    val (stored, current) = withContext(Dispatchers.IO) { accounts.listAccounts() to settings.getSettings() }
                    _accounts.value = stored.map { accountView(it) }
                    _face.value = FaceChoice(current.preferredFaceProvider)
                    _remember.value = current.saveCredentials
                    val full = withContext(Dispatchers.IO) {
                        stored.map { account ->
                            val session = try {
                                accounts.loadSession(account.providerId, account.accountId)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                log.warn("The session of the {} account could not be read, it is listed by name", account.providerId, e)
                                null
                            }
                            accountView(account, session)
                        }
                    }
                    _accounts.value = full
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("The stored accounts could not be read", e)
                }
            }
        }
    }

    /**
     * The face the launcher should wear right now: the person's choice first, licence
     * priority behind it. Reading the store's primary session bare decides by
     * priority alone, which silently discards the choice the moment anything is
     * signed in or out. Off the main thread.
     */
    internal fun faceSession(): SessionData? = accounts.primarySession(settings.getSettings().preferredFaceProvider)

    /**
     * Names [choice] to front the launcher and signs [session] in as the face that
     * comes of it. The store decides, so a provider whose account has since gone lands
     * on the same fallback as at startup.
     */
    fun setFace(choice: FaceChoice, session: SessionStore) {
        _face.value = choice
        inTurn {
            val face = withContext(Dispatchers.IO) {
                settings.updateSettings { it.copy(preferredFaceProvider = choice.providerKey) }
                faceSession()?.let { it to session.providerOf(it) }
            }
            face?.let { (signed, provider) -> session.signIn(signed, provider) }
        }
    }

    /**
     * Runs [block] after every face choice and removal asked for before it. Started on the caller's
     * thread, so the turn is taken in call order before anything suspends.
     */
    private fun inTurn(block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { facing.withLock { block() } }
    }

    /** Says whether a sign-in keeps its account from now on. Accounts already kept stay. */
    fun setRemember(value: Boolean) {
        _remember.value = value
        // The choice outlives the form, so it is saved on the flip rather than at a
        // sign-in: a person who unticks and leaves without signing in has made it.
        host.persist("remember me") { settings.updateSettings { it.copy(saveCredentials = value) } }
    }

    /**
     * Removes the account of [request]'s provider, lets go of the face choice that
     * named it, and signs [session] in as the face that is left. Where the store gives
     * no face for what is left, the launcher stays signed in as it was and the outcome
     * says so, for whoever asked to put the sign-out question to the person: nothing
     * else is cleared without their word. A removal asked for while one of the same
     * provider is under way does nothing.
     */
    fun remove(request: AccountRemoval, session: SessionStore) {
        val provider = request.providerKey
        if (!removing.add(provider)) return
        _removals.update { it + (provider to RemovalView(request.ticket, RemovalOutcome.Removing)) }
        inTurn {
            val outcome = try {
                val face = withContext(Dispatchers.IO) {
                    accounts.listAccounts().firstOrNull { it.providerId == provider }
                        ?.let { accounts.removeAccount(it.providerId, it.accountId) }
                    // The face choice goes with the account it named.
                    settings.updateSettings { it.releasingFace(provider) }
                    faceSession()?.let { it to session.providerOf(it) }
                }
                if (face != null) {
                    session.signIn(face.first, face.second)
                    RemovalOutcome.Moved
                } else {
                    RemovalOutcome.NoFaceLeft
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("The {} account could not be removed", provider, e)
                RemovalOutcome.Failed(e.message)
            } finally {
                removing.remove(provider)
                refresh()
            }
            _removals.update { it + (provider to RemovalView(request.ticket, outcome)) }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AccountDirectory::class.java)
    }
}
