package hivens.launcher.network

import hivens.core.io.AtomicFiles
import hivens.core.security.SslBypassEntry
import hivens.core.security.SslBypassStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * [SslBypassStore] on a JSON file.
 *
 * The file is read in the constructor and **expired entries are dropped during
 * the load**, so a 30-day grant from a month ago does not quietly re-arm
 * itself. Loading at construction is also what removes the ordering hazard the
 * previous shape had: persistence used to be wired by an `initialize(path)`
 * call the bootstrap had to make before anything resolved an HTTP client, and a
 * request that beat it saw an empty grant set and took the strict client for a
 * host the user had already accepted. Nothing can now hold one of these before
 * it has read its own file.
 *
 * Passing null for [file] keeps the grants in memory, which is what a test
 * wants and what the launcher never does.
 *
 * Thread-safety: every mutation is under one lock. The surface is small -- a UI
 * accept, an occasional revoke, and an [isBypassed] check per request -- so a
 * single lock is the right shape.
 */
class JsonSslBypassStore(private val file: Path? = null) : SslBypassStore {

    private val log = LoggerFactory.getLogger(JsonSslBypassStore::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val lock = Any()
    private val entries = mutableListOf<SslBypassEntry>()

    private val _bypasses = MutableStateFlow<List<SslBypassEntry>>(emptyList())
    override val bypasses: StateFlow<List<SslBypassEntry>> = _bypasses.asStateFlow()

    init {
        synchronized(lock) {
            load()
            publish()
        }
    }

    override fun isBypassed(host: String): Boolean {
        val now = Instant.now()
        synchronized(lock) {
            return entries.any { it.host == host && runCatching { Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
        }
    }

    override fun grant(host: String, until: Instant) {
        synchronized(lock) {
            entries.removeAll { it.host == host }
            entries.add(SslBypassEntry(host, until.toString()))
            save()
            publish()
        }
        log.info("SSL bypass granted for {} until {}", host, until)
    }

    override fun revoke(host: String) {
        synchronized(lock) {
            if (entries.removeAll { it.host == host }) {
                save()
                publish()
            }
        }
    }

    private fun publish() {
        _bypasses.value = entries.toList()
    }

    private fun load() {
        val file = file ?: return
        if (!Files.exists(file)) return
        try {
            val stored = json.decodeFromString<List<SslBypassEntry>>(Files.readString(file))
            val now = Instant.now()
            // Parse each entry on its own so one corrupt timestamp drops that
            // entry rather than every still-valid grant beside it.
            var dropped = 0
            for (entry in stored) {
                val keep = runCatching { Instant.parse(entry.expiresAt).isAfter(now) }.getOrDefault(false)
                if (keep) entries.add(entry) else dropped++
            }
            if (dropped > 0) log.info("Dropped {} expired or malformed SSL bypass entries during load", dropped)
        } catch (e: Exception) {
            log.warn("Failed to read {} -- starting with an empty bypass set: {}", file.fileName, e.message)
        }
    }

    private fun save() {
        val file = file ?: return
        try {
            AtomicFiles.writeString(file, json.encodeToString(entries))
        } catch (e: Exception) {
            log.warn("Failed to write {}: {}", file.fileName, e.message)
        }
    }
}
