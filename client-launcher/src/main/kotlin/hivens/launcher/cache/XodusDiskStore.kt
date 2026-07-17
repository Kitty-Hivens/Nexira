package hivens.launcher.cache

import hivens.core.cache.DiskStore
import hivens.core.cache.StoredEntry
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.StoreConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

/**
 * Disk backend for one cache namespace over a shared Xodus [Environment]: the
 * namespace is a named store, each key the SHA-256 of the cache key (URLs are long
 * and contain `/ : ?`), each value the same JSON envelope [JsonDiskStore] uses --
 * but in one transactional, log-structured DB instead of a file per key, so a write
 * is an O(log n) B-tree put with no PATH_MAX or atomic-rename dance. Pure-JVM (no
 * JNA/JNI). Tolerant by contract: any read failure returns null AND deletes the bad
 * entry so it self-heals instead of re-failing every launch.
 */
class XodusDiskStore<V>(
    private val env: Environment,
    private val storeName: String,
    private val serializer: KSerializer<V>,
    private val json: Json,
) : DiskStore<V> {

    private val log = LoggerFactory.getLogger(XodusDiskStore::class.java)
    private val envelopeSerializer = Envelope.serializer(serializer)

    override fun read(key: String): StoredEntry<V>? {
        val bytes = runCatching {
            env.computeInReadonlyTransaction { txn ->
                env.openStore(storeName, StoreConfig.WITHOUT_DUPLICATES, txn).get(txn, keyOf(key))?.toByteArray()
            }
        }.getOrNull() ?: return null
        return runCatching {
            val envelope = json.decodeFromString(envelopeSerializer, bytes.decodeToString())
            if (envelope.schemaVersion != SCHEMA_VERSION) {
                delete(key)
                null
            } else {
                StoredEntry(envelope.value, envelope.storedAt)
            }
        }.getOrElse { e ->
            log.debug("cache xodus entry {}#{} unreadable; dropping", storeName, key, e)
            delete(key)
            null
        }
    }

    override fun write(key: String, value: V, storedAtMillis: Long) {
        runCatching {
            val payload = json.encodeToString(envelopeSerializer, Envelope(SCHEMA_VERSION, storedAtMillis, value))
                .encodeToByteArray()
            env.executeInTransaction { txn ->
                env.openStore(storeName, StoreConfig.WITHOUT_DUPLICATES, txn)
                    .put(txn, keyOf(key), ArrayByteIterable(payload))
            }
        }.onFailure { log.warn("cache xodus write failed for {}#{}", storeName, key, it) }
    }

    override fun delete(key: String) {
        runCatching {
            env.executeInTransaction { txn ->
                env.openStore(storeName, StoreConfig.WITHOUT_DUPLICATES, txn).delete(txn, keyOf(key))
            }
        }
    }

    override fun clear() {
        runCatching { env.executeInTransaction { txn -> env.truncateStore(storeName, txn) } }
            .onFailure { log.warn("cache xodus clear failed for {}", storeName, it) }
    }

    private fun keyOf(key: String): ByteIterable =
        ArrayByteIterable(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))

    private fun ByteIterable.toByteArray(): ByteArray {
        val out = ByteArray(length)
        val itr = iterator()
        var i = 0
        while (itr.hasNext()) out[i++] = itr.next()
        return out
    }

    @Serializable
    private data class Envelope<V>(
        @SerialName("schema_version") val schemaVersion: Int,
        @SerialName("stored_at") val storedAt: Long,
        val value: V,
    )

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
