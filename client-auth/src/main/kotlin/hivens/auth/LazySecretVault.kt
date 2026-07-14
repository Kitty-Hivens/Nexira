package hivens.auth

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultTier

/**
 * A [SecretVault] that opens the real backend on first use, not at construction.
 *
 * Opening the OS keyring is a ~1.4s D-Bus / Secret Service probe. When the vault is
 * resolved eagerly during Compose's first composition (the account store is pulled
 * via koinInject in the shell), that cost lands on the UI thread and lengthens the
 * boot-threshold reveal -- a longer black screen. Every real consumer (auto-login,
 * save, logout) already runs off the UI thread, so deferring the open moves it
 * there. [lazy] is thread-safe: concurrent first touches open exactly once and the
 * rest wait.
 */
class LazySecretVault(opener: () -> SecretVault) : SecretVault {

    private val delegate: Lazy<SecretVault> = lazy(opener)
    private val vault: SecretVault get() = delegate.value

    override val tier: VaultTier get() = vault.tier
    override val backend: String get() = vault.backend

    override fun store(key: String, value: ByteArray): Boolean = vault.store(key, value)
    override fun retrieve(key: String): ByteArray? = vault.retrieve(key)
    override fun delete(key: String): Boolean = vault.delete(key)
    override fun contains(key: String): Boolean = vault.contains(key)

    /** Close only if the vault was ever opened -- never force the open just to close it. */
    override fun close() {
        if (delegate.isInitialized()) vault.close()
    }
}
