package hivens.launcher.network

import hivens.core.io.AtomicFiles
import hivens.core.security.SslBypassEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Global network policy state -- the per-host SSL-bypass set. Each entry
 * carries its own expiry, so accepting a one-off cert outage on
 * `smartycraft.ru` does not silently weaken TLS for unrelated hosts.
 *
 * Persistence: when [initialize] is called with a path, grants /
 * revokes write the current state to that JSON file. On restart the
 * file is re-read and **expired entries are dropped during the load**
 * so a 30-day grant from a month ago doesn't quietly re-arm itself.
 * If [initialize] is never called (test mode), state is in-memory only.
 *
 * Thread-safety: all public methods synchronize on a shared lock.
 * Surface is small (UI accept + occasional `bypassFor` check on each
 * HTTP call) so a single lock is the right shape.
 *
 * Singleton (not Koin-injected) deliberately -- callers throughout
 * `client-ui` (composables) and `client-launcher` (DI selectors) reach
 * it without injection. DI'ing would ripple into every @Composable
 * that reads bypass state for marginal testability gain that's
 * already covered by isolated tests on [SslBypassEntry].
 */
object NetworkState {
    private val log = LoggerFactory.getLogger(NetworkState::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val lock = Any()
    private val bypasses = mutableListOf<SslBypassEntry>()
    private var persistenceFile: Path? = null

    /**
     * Push-side view of [bypasses]. Emits a fresh snapshot after every
     * grant / revoke / load so UI callers `collectAsState()` instead of
     * polling on a timer for state that mutates on the scale of
     * minutes-to-days.
     *
     * Time-based expiry is not auto-ticked here: a 30-day bypass entry
     * stays in the list until process restart or explicit revoke.
     */
    private val _bypassesState = MutableStateFlow<List<SslBypassEntry>>(emptyList())
    val bypassesState: StateFlow<List<SslBypassEntry>> = _bypassesState.asStateFlow()

    private fun publishBypasses() {
        _bypassesState.value = bypasses.toList()
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
            publishBypasses()
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
            publishBypasses()
        }
        log.info("SSL bypass granted for {} until {}", host, until)
    }

    /** Revoke any existing bypass for [host]. Idempotent. */
    fun revokeBypass(host: String) {
        synchronized(lock) {
            val removed = bypasses.removeAll { it.host == host }
            if (removed) {
                save()
                publishBypasses()
            }
        }
    }

    /** Snapshot of current bypass entries (a copy -- callers can iterate safely). */
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
            publishBypasses()
        }
    }

    private fun load() {
        val file = persistenceFile ?: return
        if (!Files.exists(file)) return
        try {
            val text = Files.readString(file)
            val list = json.decodeFromString<List<SslBypassEntry>>(text)
            val now = Instant.now()
            // Drop expired on load -- stale grants from prior sessions must not
            // silently re-arm. Parse each entry independently so one corrupt
            // timestamp drops only that entry, not every still-valid grant.
            var dropped = 0
            for (entry in list) {
                val keep = runCatching { Instant.parse(entry.expiresAt).isAfter(now) }.getOrDefault(false)
                if (keep) bypasses.add(entry) else dropped++
            }
            if (dropped > 0) {
                log.info("Dropped {} expired or malformed SSL bypass entries during load", dropped)
            }
        } catch (e: Exception) {
            log.warn("Failed to read ssl-bypasses.json -- starting with empty bypass set: {}", e.message)
        }
    }

    private fun save() {
        val file = persistenceFile ?: return
        try {
            AtomicFiles.writeString(file, json.encodeToString(bypasses))
        } catch (e: Exception) {
            log.warn("Failed to write ssl-bypasses.json: {}", e.message)
        }
    }
}
