package hivens.ui.accounts

import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.StoredAccount
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SessionData
import hivens.core.data.SettingsData
import hivens.core.security.SslBypassEntry
import hivens.core.security.SslBypassStore
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import java.time.Instant

/** An account store holding [saved] as its one account, or nothing. */
internal class MemoryAccounts(private val saved: SessionData? = null) : AccountStore {
    override fun load(): SessionData? = saved
    override fun accountFor(providerId: String): SessionData? = saved
    override fun refreshStored(providerId: String, session: SessionData) = Unit
    override fun saveAccount(session: SessionData, providerId: String, makeActive: Boolean) = Unit
    override fun markTwoFactor(providerId: String) = Unit
    override fun clearTwoFactor(providerId: String) = Unit
    override fun listAccounts(): List<StoredAccount> = emptyList()
    override fun activeAccountId(): String? = null
    override fun primarySession(preferredProviderId: String?): SessionData? = saved
    override fun loadSession(providerId: String, accountId: String): SessionData? = saved
    override fun setActive(accountId: String) = Unit
    override fun removeAccount(providerId: String, accountId: String) = Unit
    override fun clear() = Unit
}

internal class MemorySettings(initial: SettingsData = SettingsData()) : ISettingsService {
    @Volatile var current: SettingsData = initial
    override fun getSettings(): SettingsData = current
    override fun saveSettings(settings: SettingsData) {
        current = settings
    }
}

internal class NoGrants : SslBypassStore {
    override val bypasses = MutableStateFlow<List<SslBypassEntry>>(emptyList())
    override fun isBypassed(host: String): Boolean = false
    override fun grant(host: String, until: Instant) = Unit
    override fun revoke(host: String) = Unit
}

/**
 * The engine services the accounts module reads from the host, as stand-ins with
 * nobody stored, for a test that brings the module in and is not about signing in.
 */
internal fun accountsEngineStandIns(accounts: AccountStore = MemoryAccounts()): Module = module {
    single { accounts }
    single<ISettingsService> { MemorySettings() }
    single<AuthProvider> { OfflineAuthProvider() }
    single { OfflineAuthProvider() }
    single { AuthProviderRegistry(emptyList()) }
    single<SslBypassStore> { NoGrants() }
}
