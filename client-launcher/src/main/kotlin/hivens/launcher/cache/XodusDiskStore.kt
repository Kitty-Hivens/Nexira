package hivens.launcher.cache

import hivens.core.cache.DiskStore
import hivens.core.cache.StoredEntry
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.StoreConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
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

    /**
     * What the stored type looked like when the entry was written.
     *
     * Derived from the serializer rather than declared, because a number somebody
     * has to remember to raise is a number that does not get raised: the envelope
     * already carried a hand-written version, it covered the envelope's own shape
     * and not the value's, and so a widened record decoded from an old entry with
     * every new field at its default. A project cached before it learned to carry
     * its download count read as a project with no downloads, for the seven days
     * the stale window allows, and nothing anywhere said why.
     */
    private val shape = shapeOf(serializer.descriptor)

    override fun read(key: String): StoredEntry<V>? {
        val bytes = runCatching {
            env.computeInReadonlyTransaction { txn ->
                env.openStore(storeName, StoreConfig.WITHOUT_DUPLICATES, txn).get(txn, keyOf(key))?.toByteArray()
            }
        }.getOrNull() ?: return null
        return runCatching {
            val envelope = json.decodeFromString(envelopeSerializer, bytes.decodeToString())
            if (envelope.schemaVersion != SCHEMA_VERSION || envelope.shape != shape) {
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
            val payload = json.encodeToString(envelopeSerializer, Envelope(SCHEMA_VERSION, shape, storedAtMillis, value))
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
        /**
         * The value type's shape. Absent on an entry written before this existed,
         * which reads as a mismatch and drops it, which is what should happen to
         * every entry from before the shape was checked at all.
         */
        val shape: String = "",
        @SerialName("stored_at") val storedAt: Long,
        val value: V,
    )

    private companion object {
        const val SCHEMA_VERSION = 1

        /**
         * A short fingerprint of a serial descriptor: its own serial name, then
         * every element's name and descriptor, walked into nested records.
         *
         * Names and not types alone, because renaming a wire field changes what an
         * entry means while leaving its types identical.
         */
        fun shapeOf(descriptor: SerialDescriptor): String {
            val sink = StringBuilder()
            describe(descriptor, sink, HashSet())
            return MessageDigest.getInstance("SHA-256")
                .digest(sink.toString().toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * [path] holds the types on the way DOWN to here, and is unwound on the way
         * back up, so a type is skipped only where expanding it would not terminate.
         *
         * It used to be one set for the whole walk, which reads the same until you
         * notice that every list has the SAME serial name whatever it holds. The
         * first list a record declared was expanded and every later one collapsed to
         * a bare `kotlin.collections.ArrayList`, so nothing inside a second list was
         * ever fingerprinted: a version's files, its dependencies, a project's
         * gallery. Widening any of those left the fingerprint identical and the
         * stale entry served -- the exact failure this whole mechanism exists to
         * stop, in the records it was written for.
         */
        private fun describe(d: SerialDescriptor, sink: StringBuilder, path: MutableSet<String>) {
            sink.append(d.serialName).append('{')
            if (path.add(d.serialName)) {
                for (i in 0 until d.elementsCount) {
                    sink.append(d.getElementName(i)).append(':')
                    describe(d.getElementDescriptor(i), sink, path)
                    sink.append(',')
                }
                path.remove(d.serialName)
            }
            sink.append('}')
        }
    }
}
