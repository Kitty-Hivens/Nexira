package hivens.launcher

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.Environments
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import jetbrains.exodus.env.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Pack registry on Xodus. Installed [PackInstance]s live one entry per id in a
 * durable environment under `<dataDir>/db` (separate from the disposable cache
 * env), so a mutation is an O(1) B-tree put instead of a full-file rewrite -- which
 * matters now that each instance carries an `installedManifest` baseline. Reads are
 * served from an in-memory [MutableStateFlow] the UI observes; writes update it and
 * persist the one changed entry off the caller's dispatcher.
 *
 * On first open it migrates a legacy `packs.json` into the DB (renaming it to
 * `packs.json.migrated`), gated by a one-shot marker so a crash mid-migration
 * re-runs it. A schema written by a newer build loads read-only, never clobbered.
 * A single unreadable entry is dropped, not fatal -- the rest of the registry loads.
 */
class XodusPackRepository(
    dbDir: Path,
    private val legacyPacksFile: Path,
    private val json: Json,
) : IPackRepository {

    private val log = LoggerFactory.getLogger(XodusPackRepository::class.java)
    private val env: Environment = run {
        Files.createDirectories(dbDir)
        Environments.newInstance(dbDir.toFile())
    }
    private val mutex = Mutex()

    // Set true when the DB schema is ahead of this build's: read best-effort, never
    // write back, so an older binary can't downgrade and clobber newer data.
    @Volatile
    private var readOnly = false

    private val state: MutableStateFlow<List<PackInstance>> = MutableStateFlow(load())

    init {
        Runtime.getRuntime().addShutdownHook(Thread { close() })
    }

    override fun observe(): Flow<List<PackInstance>> = state.asStateFlow()
    override suspend fun list(): List<PackInstance> = state.value
    override suspend fun get(id: String): PackInstance? = state.value.firstOrNull { it.id == id }

    override suspend fun put(instance: PackInstance) {
        mutex.withLock {
            state.update { current ->
                if (current.any { it.id == instance.id }) current.map { if (it.id == instance.id) instance else it }
                else current + instance
            }
            withContext(Dispatchers.IO) { writeInstance(instance) }
        }
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            state.update { it.filterNot { i -> i.id == id } }
            withContext(Dispatchers.IO) { deleteInstance(id) }
        }
    }

    /** Closes the environment (idempotent). Runs on JVM shutdown. */
    fun close() {
        runCatching { if (env.isOpen) env.close() }
    }

    private fun writeInstance(instance: PackInstance) {
        if (readOnly) return
        runCatching {
            val bytes = json.encodeToString(PackInstance.serializer(), instance).encodeToByteArray()
            env.executeInTransaction { txn -> instances(txn).put(txn, key(instance.id), ArrayByteIterable(bytes)) }
        }.onFailure { log.error("registry write failed for {}", instance.id, it) }
    }

    private fun deleteInstance(id: String) {
        if (readOnly) return
        runCatching { env.executeInTransaction { txn -> instances(txn).delete(txn, key(id)) } }
            .onFailure { log.error("registry delete failed for {}", id, it) }
    }

    private fun load(): List<PackInstance> {
        checkSchema()
        migrateLegacyIfNeeded()
        return readAll()
    }

    private fun checkSchema() {
        val stored = metaGet(SCHEMA_KEY)?.toIntOrNull() ?: return
        if (stored > SCHEMA_VERSION) {
            readOnly = true
            log.warn(
                "Pack registry schema {} > supported {} -- written by a newer build; loading read-only.",
                stored, SCHEMA_VERSION,
            )
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (readOnly || metaGet(MIGRATED_KEY) != null) return
        val legacy = if (Files.isRegularFile(legacyPacksFile)) {
            runCatching { json.decodeFromString(LegacyPacksFile.serializer(), Files.readString(legacyPacksFile)).instances }
                .getOrElse { e -> log.error("registry: legacy packs.json unreadable; migrating empty", e); emptyList() }
        } else {
            emptyList()
        }
        env.executeInTransaction { txn ->
            val inst = instances(txn)
            for (i in legacy) {
                inst.put(txn, key(i.id), ArrayByteIterable(json.encodeToString(PackInstance.serializer(), i).encodeToByteArray()))
            }
            val meta = meta(txn)
            meta.put(txn, key(MIGRATED_KEY), StringBinding.stringToEntry("1"))
            meta.put(txn, key(SCHEMA_KEY), StringBinding.stringToEntry(SCHEMA_VERSION.toString()))
        }
        if (legacy.isNotEmpty()) {
            runCatching {
                Files.move(
                    legacyPacksFile,
                    legacyPacksFile.resolveSibling(legacyPacksFile.fileName.toString() + ".migrated"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            log.info("registry: migrated {} instance(s) from {}", legacy.size, legacyPacksFile.fileName)
        }
    }

    // A read-write transaction (not read-only): openStore may need to create the
    // store on a fresh DB, which a read-only transaction forbids. No instance data
    // is written here.
    private fun readAll(): List<PackInstance> = env.computeInTransaction { txn ->
        val out = ArrayList<PackInstance>()
        instances(txn).openCursor(txn).use { cursor ->
            while (cursor.next) {
                runCatching { json.decodeFromString(PackInstance.serializer(), cursor.value.toByteArray().decodeToString()) }
                    .onSuccess { out.add(it) }
                    .onFailure { log.warn("registry: dropping unreadable entry {}", StringBinding.entryToString(cursor.key), it) }
            }
        }
        out
    }

    private fun metaGet(k: String): String? = env.computeInTransaction { txn ->
        meta(txn).get(txn, key(k))?.let { StringBinding.entryToString(it) }
    }

    private fun instances(txn: Transaction): Store = env.openStore(INSTANCES, StoreConfig.WITHOUT_DUPLICATES, txn)
    private fun meta(txn: Transaction): Store = env.openStore(META, StoreConfig.WITHOUT_DUPLICATES, txn)
    private fun key(s: String): ByteIterable = StringBinding.stringToEntry(s)
    private fun ByteIterable.toByteArray(): ByteArray = bytesUnsafe.copyOf(length)

    @Serializable
    private class LegacyPacksFile(
        @SerialName("schema_version") val schemaVersion: Int = 1,
        val instances: List<PackInstance> = emptyList(),
    )

    private companion object {
        const val INSTANCES = "instances"
        const val META = "meta"
        const val SCHEMA_KEY = "schema"
        const val MIGRATED_KEY = "migrated"
        const val SCHEMA_VERSION = 1
    }
}
