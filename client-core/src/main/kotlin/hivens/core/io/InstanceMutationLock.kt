package hivens.core.io

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-instance-directory lock that serializes STRUCTURAL mutations of an
 * instance's content (a sync, an optional-content relabel, an add/delete) so two
 * of them never interleave on the same instance. Reads do NOT take it: they open
 * with delete-sharing ([openSharedZip]) and cannot corrupt a concurrent rename, so
 * gating them would only add latency.
 *
 * A coroutine [Mutex] (not a thread lock): the mutators suspend across IO, and a
 * thread-owned lock unlocked from a different dispatcher thread after a suspension
 * point would throw. Keyed by the normalized absolute path, so the same instance
 * always maps to the same mutex regardless of how the caller spelled the path.
 */
object InstanceMutationLock {
    private val locks = ConcurrentHashMap<Path, Mutex>()

    suspend fun <T> withLock(instanceDir: Path, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(instanceDir.toAbsolutePath().normalize()) { Mutex() }
        return mutex.withLock { block() }
    }
}
