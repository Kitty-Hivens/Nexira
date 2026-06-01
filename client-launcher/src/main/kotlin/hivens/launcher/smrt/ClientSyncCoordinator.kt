package hivens.launcher.smrt

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes file-mutating sync of a single `clients/<id>/` directory across the
 * two entry points that touch it -- a foreground launch
 * ([hivens.launcher.launch.LauncherController]) and background auto-sync
 * ([hivens.launcher.AutoSyncService]). Both can fire for the same pack at the
 * same time; without a lock, one path's strict-prune (`Files.delete`) races the
 * other's download / helper inject (`Files.copy`) over the same `mods/`, which
 * corrupts the install (a just-pruned jar, a half-copied helper, a
 * `NoSuchFileException` surfaced as a generic launch error).
 *
 * A process-wide registry of per-directory mutexes (keyed by the normalized
 * absolute path). An `object` rather than a DI singleton so it needs no
 * constructor plumbing through both call sites; the lock is global by nature.
 * The map is bounded by the number of installed packs, so no eviction needed.
 */
object ClientSyncCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withClientLock(clientDir: Path, block: suspend () -> T): T {
        val key = clientDir.toAbsolutePath().normalize().toString()
        val mutex = locks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock { block() }
    }
}
