package hivens.accounts

import hivens.auth.AccountStore
import hivens.auth.AuthCapabilities
import hivens.auth.AuthProvider
import hivens.auth.StoredAccount
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import java.util.concurrent.CopyOnWriteArrayList

/** An account store that keeps what it is told, one account per provider. */
internal class MemoryAccounts : AccountStore {
    val byProvider = LinkedHashMap<String, SessionData>()
    var preferredFallback: List<String> = listOf("microsoft", "smartycraft")

    /** A keyring that will not open: every session read throws, the metadata stays readable. */
    @Volatile var lockedSecrets = false
    /** A store that lists accounts but gives no face for them, as a keyring that fails half way does. */
    @Volatile var noFace = false

    @Synchronized override fun load(): SessionData? = byProvider.values.firstOrNull()
    @Synchronized override fun accountFor(providerId: String): SessionData? = byProvider[providerId]
    override fun refreshStored(providerId: String, session: SessionData) = Unit
    @Synchronized override fun saveAccount(session: SessionData, providerId: String, makeActive: Boolean) {
        byProvider[providerId] = session
    }
    override fun markTwoFactor(providerId: String) = Unit
    val cleared = CopyOnWriteArrayList<String>()
    override fun clearTwoFactor(providerId: String) {
        cleared += providerId
    }
    @Synchronized override fun listAccounts(): List<StoredAccount> =
        byProvider.map { (provider, s) -> StoredAccount(provider, s.uuid.ifBlank { s.playerName }, s.playerName, s.uuid, s.playerName) }
    override fun activeAccountId(): String? = null
    @Synchronized override fun primarySession(preferredProviderId: String?): SessionData? =
        if (noFace) null else preferredProviderId?.let { byProvider[it] } ?: preferredFallback.firstNotNullOfOrNull { byProvider[it] }
    @Synchronized override fun loadSession(providerId: String, accountId: String): SessionData? {
        if (lockedSecrets) error("the keyring is locked")
        return byProvider[providerId]
    }
    override fun setActive(accountId: String) = Unit
    @Synchronized override fun removeAccount(providerId: String, accountId: String) {
        byProvider.remove(providerId)
    }
    @Synchronized override fun clear() = byProvider.clear()
}

internal class MemorySettings(initial: SettingsData = SettingsData()) : ISettingsService {
    @Volatile var current: SettingsData = initial
    override fun getSettings(): SettingsData = current
    override fun saveSettings(settings: SettingsData) {
        current = settings
    }
    @Synchronized
    override fun updateSettings(transform: (SettingsData) -> SettingsData): SettingsData = transform(current).also { current = it }
}

/** A host that keeps what it was told. */
internal class RecordingHost : AccountsHost {
    val failed = CopyOnWriteArrayList<AccountTrouble>()
    override fun failed(trouble: AccountTrouble, cause: Throwable) {
        failed += trouble
    }
    override fun cleared(trouble: AccountTrouble) = Unit
    override fun persist(what: String, write: () -> Unit) = write()

    /** The retry a refused certificate handed over, run by [trust]. Null while nothing was refused. */
    @Volatile var certificateRetry: (() -> Unit)? = null
    override fun certificateRefused(retry: () -> Unit): Boolean {
        certificateRetry = retry
        return true
    }

    /** The person trusts the certificate. */
    fun trust() = certificateRetry?.also { certificateRetry = null }?.invoke()
}

/**
 * A password provider that answers each login with what [logins] says next and each
 * second factor with what [codes] says next: a session, or an exception to throw.
 */
internal class ScriptedProvider(
    override val id: String = "smartycraft",
    secondFactor: Boolean = true,
) : AuthProvider {
    override val displayName: String = "SmartyCraft"
    override val defaultServerId: String = "Industrial"
    override val capabilities = AuthCapabilities(supports2FA = secondFactor)
    val logins = ArrayDeque<Any>()
    val codes = ArrayDeque<Any>()
    val asked = CopyOnWriteArrayList<String>()

    override suspend fun login(username: String, password: String, serverId: String): SessionData {
        asked += "login $username@$serverId"
        return answer(logins.removeFirstOrNull() ?: error("no login scripted"))
    }

    override suspend fun completeTwoFactor(username: String, password: String, serverId: String, uid: String, code: String): SessionData {
        asked += "code $code for $uid"
        return answer(codes.removeFirstOrNull() ?: error("no code scripted"))
    }

    private fun answer(next: Any): SessionData = when (next) {
        is SessionData -> next
        is Exception -> throw next
        else -> error("not an answer: $next")
    }
}
