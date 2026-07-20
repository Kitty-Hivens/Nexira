package hivens.launcher.cache

import hivens.core.cache.Cache
import hivens.core.cache.CacheConfig
import hivens.core.cache.DefaultCache
import hivens.core.cache.NoOpDiskStore
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import jetbrains.exodus.env.Environment
import jetbrains.exodus.env.EnvironmentConfig
import jetbrains.exodus.env.Environments
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds typed, disk-backed caches that share one root dir, Json, scope, and
 * clock. Each call yields an independent [Cache] for one value type/namespace --
 * resolving heterogeneous cached types without `Any`/erasure (every namespace
 * carries its own [KSerializer] and disk subdirectory).
 */
class CacheFactory(
    private val rootDir: Path,            // <dataDir>/cache
    private val json: Json,
    private val scope: CoroutineScope,    // shared app scope (SupervisorJob + IO)
    private val clock: Clock = SystemClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // One Xodus environment for every cache namespace (each namespace is a named
    // store) under <cache>/xodus -- one transactional log-structured DB instead of a
    // JSON file per key. Pure-JVM (no JNA/JNI), so it fits the no-native-in-launcher
    // policy. Opened on first use; closed on JVM shutdown (and the OS releases the
    // dir lock on process death even if that hook is skipped).
    private var shutdownHook: Thread? = null
    private val env: Environment by lazy {
        val dir = rootDir.resolve("xodus")
        Files.createDirectories(dir)
        // Management disabled: the obfuscated distributable renames Xodus's MBean
        // interface, so its Standard-MBean registration throws NotCompliantMBeanException
        // on startup. Nothing here consumes those JMX beans, so skip them.
        val config = EnvironmentConfig().setManagementEnabled(false)
        Environments.newInstance(dir.toFile(), config).also { e ->
            val hook = Thread { runCatching { e.close() } }
            shutdownHook = hook
            Runtime.getRuntime().addShutdownHook(hook)
        }
    }

    /** Closes the cache environment and drops its shutdown hook. No-op if it was never opened. */
    fun close() {
        val hook = shutdownHook ?: return
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        runCatching { env.close() }
        shutdownHook = null
    }

    fun <V> create(namespace: String, serializer: KSerializer<V>, config: CacheConfig<V>): Cache<V> {
        val disk = XodusDiskStore(env, namespace, serializer, json)
        return DefaultCache(disk, config, scope, clock, namespace, ioDispatcher)
    }

    /** In-memory only (no disk persistence) -- single-flight + TTL + SWR, no serializer needed. */
    fun <V> createInMemory(namespace: String, config: CacheConfig<V>): Cache<V> =
        DefaultCache(NoOpDiskStore(), config, scope, clock, namespace, ioDispatcher)

    /** The shared cache environment, for a caller that needs a bespoke store (e.g. the content-scan cache). */
    fun environment(): Environment = env
}
