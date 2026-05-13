package hivens.core.security

/**
 * Platform-agnostic credential storage interface.
 *
 * Implementations wrap an OS-level secure store: Windows Credential Manager
 * (DPAPI), macOS Keychain, Linux libsecret/KWallet via Secret Service. The
 * `client-launcher` module ships the concrete implementations and a
 * `KeyringStorageFactory` that picks one at runtime; this interface lives in
 * `client-core` so domain code can depend on it without pulling JNA.
 *
 * Identity tuple: `(service, account)`. `service` is the launcher's brand-
 * scope identifier (`"AuraLauncher"`); `account` is the user-facing key
 * inside that service ("session" for the active access-token bundle, etc.).
 * Both must be non-blank.
 *
 * Failure mode is **non-throwing** — every method returns a result that
 * tells you whether the underlying store is reachable:
 *
 *   - [retrieve] → null when the secret is not stored OR the store is
 *     unreachable. Callers that need to distinguish those cases should
 *     consult [isAvailable] first.
 *   - [store], [clear] → boolean success. False means either "store is
 *     unreachable" or "operation rejected by the store" (e.g. user
 *     denied access via an OS prompt).
 *
 * No-throw is deliberate: credential code runs in startup hot paths where
 * a JNA/DBus blip should degrade gracefully to the file fallback, not
 * crash the launcher.
 */
interface IKeyringStorage {
    /**
     * True when the underlying store is reachable on this machine right
     * now. Cheap to call (no network, no UI prompt). Used by the factory
     * to pick the best available impl at startup, and by callers who
     * want to short-circuit before attempting [retrieve] on a known-dead
     * store.
     */
    fun isAvailable(): Boolean

    /**
     * Persist [secret] under [service]/[account]. Returns true on
     * confirmed success.
     */
    fun store(service: String, account: String, secret: String): Boolean

    /**
     * Look up the secret under [service]/[account]. Returns null when
     * absent or when the store is unreachable.
     */
    fun retrieve(service: String, account: String): String?

    /**
     * Remove the secret under [service]/[account]. Returns true if the
     * delete operation succeeded (including when there was nothing to
     * delete — clear is idempotent).
     */
    fun clear(service: String, account: String): Boolean
}
