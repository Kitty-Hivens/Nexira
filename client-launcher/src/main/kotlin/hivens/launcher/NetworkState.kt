package hivens.launcher

import hivens.core.security.SslBypassEntry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Global network policy state — currently the per-host SSL-bypass set.
 *
 * Replaced the prior `var sslBypassEnabled: Boolean` (a single global
 * toggle that, once accepted, opened TLS verification for every HTTPS
 * call until process exit) with a list of [SslBypassEntry] each
 * carrying its own expiry. Practical effect: accepting a one-off cert
 * outage on `smartycraft.ru` no longer silently weakens TLS for
 * unrelated hosts, and stale acceptances stop applying after the user-
 * set expiry instead of surviving forever in process memory.
 *
 * Persistence: when [initialize] is called with a path, all grants /
 * revokes write the current state to that JSON file. On launcher
 * restart the same file is re-read and **expired entries are dropped
 * during the load**, so a 30-day grant from a month ago doesn't quietly
 * re-arm itself. If [initialize] is never called (test mode), the state
 * is in-memory only.
 *
 * Thread-safety: all public methods synchronise on a shared lock. The
 * surface is small (4 methods) and contention is rare (UI accept,
 * occasional `bypassFor` check on each HTTP call) so a single lock is
 * the right shape.
 *
 * Singleton chosen deliberately — the previous boolean was also an
 * `object`, callers throughout `client-ui` (composables) and
 * `client-launcher` (DI selector) reach it without injection. Turning
 * it into a Koin-injected class would have rippled into every
 * @Composable that reads bypass state, for marginal testability gain
 * that's already covered by extracting [SslBypassEntry] logic into
 * its own data type with isolated tests.
 */
object NetworkState {
    private val log = LoggerFactory.getLogger(NetworkState::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val lock = Any()
    private val bypasses = mutableListOf<SslBypassEntry>()
    private var persistenceFile: Path? = null

    /**
     * User opt-in: skip the direct-channel attempt and route every smartycraft
     * request through the SOCKS5 proxy from the first call. For users in
     * censored regions / corporate firewalls where direct connections are
     * known to fail. Default false — direct works for ~99% of users
     * (see `reference_smartycraft_proxy` for empirical data).
     *
     * Persisted in-memory only for now. UI binding (Settings → Network →
     * "Force proxy mode") wires through here. Survives via [SettingsService]
     * persistence — the UI restores the saved value on each launch and calls
     * [setForceProxyMode] to re-arm.
     */
    @Volatile
    private var forceProxy: Boolean = false

    /** True when the user has opted into proxy-only mode. */
    fun forceProxyMode(): Boolean = forceProxy

    /** Set the force-proxy toggle. Settings UI calls this on toggle change. */
    fun setForceProxyMode(value: Boolean) {
        forceProxy = value
        log.info("Force proxy mode: {}", if (value) "ENABLED — skipping direct attempt" else "disabled (default)")
    }

    /**
     * Wire on-disk persistence + load any saved bypasses. Called from
     * `Main.kt` after PlatformPaths is ready. Calling twice replaces the
     * file path and re-loads from it; useful for tests but normal
     * launcher path calls this exactly once.
     */
    fun initialize(file: Path) {
        synchronized(lock) {
            persistenceFile = file
            bypasses.clear()
            load()
        }
    }

    /** True when [host] currently has an unexpired bypass entry. */
    fun bypassFor(host: String): Boolean {
        val now = Instant.now()
        synchronized(lock) {
            return bypasses.any { it.host == host && Instant.parse(it.expiresAt).isAfter(now) }
        }
    }

    /**
     * Grant or refresh a bypass for [host] valid until [until]. An
     * existing entry for the same host is replaced (no duplicates).
     */
    fun grantBypass(host: String, until: Instant) {
        synchronized(lock) {
            bypasses.removeAll { it.host == host }
            bypasses.add(SslBypassEntry(host, until.toString()))
            save()
        }
        log.info("SSL bypass granted for {} until {}", host, until)
    }

    /** Revoke any existing bypass for [host]. Idempotent. */
    fun revokeBypass(host: String) {
        synchronized(lock) {
            val removed = bypasses.removeAll { it.host == host }
            if (removed) save()
        }
    }

    /** Snapshot of current bypass entries (a copy — callers can iterate safely). */
    fun listBypasses(): List<SslBypassEntry> = synchronized(lock) { bypasses.toList() }

    /**
     * Test-only: wipe in-memory state without touching the persistence
     * file. Tests that don't want JVM-wide state bleed between cases
     * call this in setup/teardown.
     */
    fun clearForTests() {
        synchronized(lock) {
            bypasses.clear()
            persistenceFile = null
        }
    }

    private fun load() {
        val file = persistenceFile ?: return
        if (!Files.exists(file)) return
        try {
            val text = Files.readString(file)
            val list = json.decodeFromString<List<SslBypassEntry>>(text)
            val now = Instant.now()
            // Drop expired on load — stale grants from prior sessions must
            // not silently re-arm.
            list.filter { Instant.parse(it.expiresAt).isAfter(now) }.forEach(bypasses::add)
            if (list.size != bypasses.size) {
                log.info("Dropped {} expired SSL bypass entries during load", list.size - bypasses.size)
            }
        } catch (e: Exception) {
            log.warn("Failed to read ssl-bypasses.json — starting with empty bypass set: {}", e.message)
        }
    }

    private fun save() {
        val file = persistenceFile ?: return
        try {
            if (file.parent != null) Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(bypasses))
        } catch (e: Exception) {
            log.warn("Failed to write ssl-bypasses.json: {}", e.message)
        }
    }
}
