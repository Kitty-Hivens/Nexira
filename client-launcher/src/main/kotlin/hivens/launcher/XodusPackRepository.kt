package hivens.launcher

import hivens.core.data.NewerBuildData
import hivens.core.data.ReadOnlyStore
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackIdentity
import hivens.core.data.PackInstance
import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.bindings.StringBinding
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import jetbrains.exodus.env.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *
 * The environment is opened on first use, not when the object is built. Xodus
 * takes an exclusive lock on the directory, and building it eagerly made a second
 * process on the same data dir fail inside the dependency graph, before anything
 * could say why. [holdOpen] false opens the environment for each operation and
 * closes it after, which is how a short-lived process (the command line) keeps the
 * lock for milliseconds rather than for as long as it runs.
 */
class XodusPackRepository(
    private val dbDir: Path,
    private val legacyPacksFile: Path,
    private val json: Json,
    private val holdOpen: Boolean = true,
    /** The schema this build writes. A parameter so a test can play an older and a newer build. */
    private val schemaVersion: Int = SCHEMA_VERSION,
) : IPackRepository {

    private val log = LoggerFactory.getLogger(XodusPackRepository::class.java)

    /** Guards [openEnv]: who opens, uses and (with [holdOpen] off) closes the environment. */
    private val envLock = Any()
    private var openEnv: Environment? = null
    private var hookInstalled = false

    /** Set by [close]. A write after it fails rather than opening the database again during shutdown. */
    private var closed = false

    private val mutex = Mutex()
    private val shutdownHook = Thread { close() }

    // Set true when the DB schema is ahead of this build's: read best-effort, never
    // write back, so an older binary can't downgrade and clobber newer data.
    @Volatile
    private var readOnly = false

    private val state: MutableStateFlow<List<PackInstance>> by lazy { MutableStateFlow(load()) }

    /**
     * Runs [block] against the environment, opening it first if it is not open. The
     * open waits [LOCK_WAIT_MS] for a lock another process holds briefly before
     * giving up, so one command-line call does not fail the launcher starting
     * beside it.
     */
    private fun <T> withEnv(block: (Environment) -> T): T = synchronized(envLock) {
        check(!closed) { "pack registry is closed" }
        val env = openEnv ?: open().also { openEnv = it }
        try {
            block(env)
        } finally {
            if (!holdOpen) {
                runCatching { env.close() }
                openEnv = null
            }
        }
    }

    private fun open(): Environment {
        Files.createDirectories(dbDir)
        // Durable (fsync'd) commits: the registry is the user's installed-pack library,
        // so an install must survive a power loss -- unlike the disposable caches.
        // Management off: Xodus registers a reflection-only Standard MBean we never
        // consume, and a classpath shrinker that strips its by-name MBean interface
        // (as the old release ProGuard pass did) makes registration throw
        // NotCompliantMBeanException before the shell starts. Disabling it sidesteps both.
        val config = EnvironmentConfig()
            .setLogDurableWrite(true)
            .setManagementEnabled(false)
            .setLogLockTimeout(LOCK_WAIT_MS)
        val env = Environments.newInstance(dbDir.toFile(), config)
        if (holdOpen && !hookInstalled) {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
            hookInstalled = true
        }
        return env
    }

    override fun observe(): StateFlow<List<PackInstance>> = state.asStateFlow()
    override suspend fun list(): List<PackInstance> = state.value
    override suspend fun get(id: String): PackInstance? = state.value.firstOrNull { it.id == id }

    override suspend fun put(instance: PackInstance) {
        PackIdentity.require(instance)
        mutex.withLock { store(instance) }
    }

    override suspend fun update(id: String, transform: (PackInstance) -> PackInstance): PackInstance? =
        mutex.withLock {
            val current = state.value.firstOrNull { it.id == id } ?: return@withLock null
            val next = transform(current)
            require(next.id == id) { "update of $id returned ${next.id}" }
            PackIdentity.require(next)
            next.takeIf { store(it) }
        }

    /** Under [mutex]. @return false when the durable write failed and memory was reverted. */
    private suspend fun store(instance: PackInstance): Boolean {
        val previous = state.value
        state.update { current ->
            if (current.any { it.id == instance.id }) current.map { if (it.id == instance.id) instance else it }
            else current + instance
        }
        // Keep memory and disk in lockstep: if the durable write fails, revert the
        // in-memory state so the UI never claims an install the DB never got. Past the
        // memory change the write is not cancellable: a cancellation landing on the
        // dispatch threw before the write and before the revert, and left an edit that
        // was on screen and gone at the next start.
        val written = withContext(NonCancellable + Dispatchers.IO) { writeInstance(instance) }
        if (!written) state.value = previous
        return written
    }

    override suspend fun delete(id: String) {
        mutex.withLock {
            val previous = state.value
            state.update { it.filterNot { i -> i.id == id } }
            if (!withContext(NonCancellable + Dispatchers.IO) { deleteInstance(id) }) state.value = previous
        }
    }

    /** Closes the environment and drops its shutdown hook. Idempotent. Runs on JVM shutdown. */
    fun close() {
        // removeShutdownHook throws once shutdown is underway (i.e. when the hook itself
        // calls close); swallow it -- removal only matters on the explicit-close path.
        if (hookInstalled) runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        synchronized(envLock) {
            closed = true
            runCatching { openEnv?.takeIf { it.isOpen }?.close() }
            openEnv = null
        }
    }

    /** @return true on success (or when read-only). A failure is logged; the caller reverts state. */
    private fun writeInstance(instance: PackInstance): Boolean {
        if (readOnly) return true
        return runCatching {
            val bytes = json.encodeToString(PackInstance.serializer(), instance).encodeToByteArray()
            withEnv { env -> env.executeInTransaction { txn -> instances(env, txn).put(txn, key(instance.id), ArrayByteIterable(bytes)) } }
        }.onFailure { log.error("registry write failed for {}", instance.id, it) }.isSuccess
    }

    private fun deleteInstance(id: String): Boolean {
        if (readOnly) return true
        return runCatching { withEnv { env -> env.executeInTransaction { txn -> instances(env, txn).delete(txn, key(id)) } } }
            .onFailure { log.error("registry delete failed for {}", id, it) }.isSuccess
    }

    /**
     * Empty when the database cannot be opened at all, with the reason logged. A
     * throw here would come back on every read, and the reads are on screens. The
     * registry is then read-only for the session, so nothing is written over a
     * library this process never saw.
     */
    private fun load(): List<PackInstance> = runCatching {
        checkSchema()
        migrateLegacyIfNeeded()
        // On its own: a stamp that could not be written is no reason to show an
        // empty library over entries that read fine. The next open tries again.
        runCatching { stampSchema() }.onFailure { log.warn("Pack registry schema stamp could not be written", it) }
        readAll()
    }.getOrElse { e ->
        log.error("Pack registry could not be opened; the library is empty and read-only this session", e)
        readOnly = true
        emptyList()
    }

    private fun checkSchema() {
        val stored = metaGet(SCHEMA_KEY)?.toIntOrNull() ?: return
        if (stored > schemaVersion) {
            readOnly = true
            NewerBuildData.record(ReadOnlyStore.PackLibrary)
            log.warn(
                "Pack registry schema {} > supported {} -- written by a newer build; loading read-only.",
                stored, schemaVersion,
            )
        }
    }

    /**
     * Records this build's schema on a database that carries an older one, or none.
     *
     * The stamp used to be written only inside the legacy migration, which runs once,
     * so a database made before a bump kept the old number for good. The newer-build
     * check above then never fired for it, and an older build went on writing entries
     * of its own shape over the newer ones.
     */
    private fun stampSchema() {
        if (readOnly) return
        val stored = metaGet(SCHEMA_KEY)?.toIntOrNull()
        if (stored != null && stored >= schemaVersion) return
        withEnv { env -> env.executeInTransaction { txn ->
            meta(env, txn).put(txn, key(SCHEMA_KEY), StringBinding.stringToEntry(schemaVersion.toString()))
        } }
        log.info("Pack registry schema stamped {} (was {})", schemaVersion, stored ?: "none")
    }

    private fun migrateLegacyIfNeeded() {
        if (readOnly || metaGet(MIGRATED_KEY) != null) return
        val legacy: List<PackInstance> = when {
            !Files.isRegularFile(legacyPacksFile) -> emptyList()
            else -> runCatching {
                json.decodeFromString(LegacyPacksFile.serializer(), Files.readString(legacyPacksFile)).instances
            }.getOrElse { e ->
                // Present but unreadable: do NOT mark migrated or touch packs.json. A
                // transient IO/parse failure must retry next launch -- never silently
                // drop the user's registry into an empty DB.
                log.error("registry: packs.json present but unreadable; leaving migration for a later launch", e)
                return
            }
        }
        withEnv { env -> env.executeInTransaction { txn ->
            val inst = instances(env, txn)
            for (i in legacy) {
                inst.put(txn, key(i.id), ArrayByteIterable(json.encodeToString(PackInstance.serializer(), i).encodeToByteArray()))
            }
            val meta = meta(env, txn)
            meta.put(txn, key(MIGRATED_KEY), StringBinding.stringToEntry("1"))
            meta.put(txn, key(SCHEMA_KEY), StringBinding.stringToEntry(schemaVersion.toString()))
        } }
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
    private fun readAll(): List<PackInstance> = withEnv { env -> env.computeInTransaction { txn ->
        val out = ArrayList<PackInstance>()
        instances(env, txn).openCursor(txn).use { cursor ->
            while (cursor.next) {
                runCatching { json.decodeFromString(PackInstance.serializer(), cursor.value.toByteArray().decodeToString()) }
                    .onSuccess { out.add(PackIdentity.normalize(it)) }
                    .onFailure { log.warn("registry: dropping unreadable entry {}", StringBinding.entryToString(cursor.key), it) }
            }
        }
        out
    } }

    private fun metaGet(k: String): String? = withEnv { env -> env.computeInTransaction { txn ->
        meta(env, txn).get(txn, key(k))?.let { StringBinding.entryToString(it) }
    } }

    private fun instances(env: Environment, txn: Transaction): Store = env.openStore(INSTANCES, StoreConfig.WITHOUT_DUPLICATES, txn)
    private fun meta(env: Environment, txn: Transaction): Store = env.openStore(META, StoreConfig.WITHOUT_DUPLICATES, txn)
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

        /** How long an open waits for a lock another process holds. */
        const val LOCK_WAIT_MS = 5_000L
    }
}
