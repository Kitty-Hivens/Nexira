package hivens.launcher.cache

import hivens.core.cache.DiskStore
import hivens.core.cache.StoredEntry
import hivens.core.io.AtomicFiles
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Disk backend for one cache namespace: one JSON file per key under [dir],
 * named by the SHA-256 of the key (URLs contain `/ : ?` and can exceed PATH_MAX,
 * so a hash is the only robust filename; the original key is kept inside the
 * envelope for debuggability). Tolerant by contract: any read failure (missing,
 * corrupt, wrong schema) returns null AND deletes the offending file so it
 * self-heals instead of re-failing every launch. Writes are atomic.
 */
class JsonDiskStore<V>(
    private val dir: Path,
    private val serializer: KSerializer<V>,
    private val json: Json,
) : DiskStore<V> {

    private val log = LoggerFactory.getLogger(JsonDiskStore::class.java)
    private val envelopeSerializer = Envelope.serializer(serializer)

    override fun read(key: String): StoredEntry<V>? {
        val file = fileFor(key)
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val env = json.decodeFromString(envelopeSerializer, Files.readString(file))
            if (env.schemaVersion != SCHEMA_VERSION) {
                runCatching { Files.deleteIfExists(file) }
                return null
            }
            StoredEntry(env.value, env.storedAt)
        }.getOrElse { e ->
            log.debug("cache disk entry {} unreadable; dropping", file, e)
            runCatching { Files.deleteIfExists(file) }
            null
        }
    }

    override fun write(key: String, value: V, storedAtMillis: Long) {
        runCatching {
            val env = Envelope(SCHEMA_VERSION, key, storedAtMillis, value)
            AtomicFiles.writeString(fileFor(key), json.encodeToString(envelopeSerializer, env))
        }.onFailure { log.warn("cache disk write failed for key {}", key, it) }
    }

    override fun delete(key: String) {
        runCatching { Files.deleteIfExists(fileFor(key)) }
    }

    override fun clear() {
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { stream ->
                stream.filter {
                    val n = it.fileName.toString()
                    // Also sweep AtomicFiles' "<hash>.json.tmp" left by a write that
                    // crashed between temp-create and rename; reads never touch it.
                    n.endsWith(".json") || n.endsWith(".json.tmp")
                }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }.onFailure { log.warn("cache clear failed for {}", dir, it) }
    }

    private fun fileFor(key: String): Path = dir.resolve(sha256Hex(key) + ".json")

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class Envelope<V>(
        @SerialName("schema_version") val schemaVersion: Int,
        val key: String,
        @SerialName("stored_at") val storedAt: Long,
        val value: V,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
