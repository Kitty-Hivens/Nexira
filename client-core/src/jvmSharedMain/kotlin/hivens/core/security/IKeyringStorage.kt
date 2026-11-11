package hivens.core.security

/**
 * Platform-agnostic credential storage. Implementations wrap an OS
 * secure store: Windows Credential Manager (DPAPI), macOS Keychain,
 * Linux libsecret via Secret Service. Concrete implementations + the
 * runtime-picking `KeyringStorageFactory` live in `client-launcher`;
 * this interface lives in `client-core` so domain code depends on it
 * without pulling JNA.
 *
 * Identity tuple: `(service, account)` -- both non-blank. `service` is
 * the launcher's brand-scope identifier; `account` is the leaf key
 * within that service.
 *
 * Failure mode is non-throwing -- credential code runs in startup hot
 * paths where a JNA / D-Bus blip should degrade gracefully to the file
 * fallback, not crash the launcher:
 *   - [retrieve] returns null when the secret is absent OR when the
 *     store is unreachable; consult [isAvailable] to distinguish
 *   - [store] / [clear] return boolean success; false means either
 *     unreachable or rejected (e.g. user denied an OS prompt)
 */
interface IKeyringStorage {
    /**
     * True when the store is reachable now. Cheap; no network, no UI
     * prompt. Factory uses it at startup; callers use it to short-circuit
     * before [retrieve] on a known-dead store.
     */
    fun isAvailable(): Boolean

    /** Persist [secret] under [service]/[account]. Returns confirmed success. */
    fun store(service: String, account: String, secret: String): Boolean

    /** Look up [service]/[account]. Returns null when absent or unreachable. */
    fun retrieve(service: String, account: String): String?

    /** Remove [service]/[account]. Idempotent -- nothing-to-delete still returns true. */
    fun clear(service: String, account: String): Boolean
}
