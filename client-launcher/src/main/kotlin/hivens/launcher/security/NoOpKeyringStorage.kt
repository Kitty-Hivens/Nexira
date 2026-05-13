package hivens.launcher.security

import hivens.core.security.IKeyringStorage

/**
 * Fallback [IKeyringStorage] that always reports unavailable and
 * silently fails every operation. Returned by
 * [KeyringStorageFactory] when no platform-specific implementation
 * is reachable on the current host (CI without a desktop session,
 * KVM-headless server, exotic Linux without libsecret, etc.).
 *
 * Singleton — there's no per-instance state. Use
 * [NoOpKeyringStorage.INSTANCE] to avoid pointless object allocations
 * on every factory miss.
 */
object NoOpKeyringStorage : IKeyringStorage {
    override fun isAvailable(): Boolean = false
    override fun store(service: String, account: String, secret: String): Boolean = false
    override fun retrieve(service: String, account: String): String? = null
    override fun clear(service: String, account: String): Boolean = false
}
