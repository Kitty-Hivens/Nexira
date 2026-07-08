package hivens.launcher

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle of one install, keyed on [InstallSnapshot.key]. */
sealed interface InstallPhase {
    data class Running(val current: Int, val total: Int, val filename: String) : InstallPhase
    data class Succeeded(val instanceId: String) : InstallPhase
    data class Failed(val message: String) : InstallPhase
    data object Cancelled : InstallPhase
}

/**
 * Observable snapshot of a single in-flight or terminal install. The identity
 * fields (origin/packId/versionId/title/iconUrl) travel with the phase so a
 * driver or a re-entered screen can render the right pack without re-fetching
 * catalogue metadata.
 */
data class InstallSnapshot(
    val key: String,
    val origin: PackOrigin,
    val packId: String,
    val versionId: String,
    val title: String,
    val iconUrl: String?,
    val phase: InstallPhase,
)

/**
 * The install step the service drives. [PackInstallCoordinator.install] is the
 * production binding; keeping it a function (not the concrete coordinator) lets
 * the service be unit-tested against a fake without standing up the whole
 * installer graph.
 */
typealias PackInstallRunner = suspend (
    pack: CataloguePack,
    version: CataloguePackVersion,
    onReserveDir: (Path) -> Unit,
    progress: (current: Int, total: Int, filename: String) -> Unit,
) -> PackInstance

/**
 * App-scoped owner of pack installs. The install coroutine runs on the shared
 * process-lifetime [scope], NOT on the composition scope of whatever screen
 * kicked it off -- so leaving the Browse detail page no longer cancels a
 * download mid-flight. Progress is published on [installs]; an [InstallSnapshot]
 * carries its own identity so both the originating screen (on re-entry) and the
 * notification driver can render it.
 *
 * Cancellation is deliberate only: [cancel] (a user pressing Cancel) or process
 * shutdown when [scope] is torn down. On cancel the partial instance directory
 * -- captured via the installer's reserve hook -- is deleted, so an aborted
 * install does not leave an orphan under `instances/`. A successful install
 * registers its instance in the repository the way it always did; a failed one
 * does not.
 */
class PackInstallService(
    private val runInstall: PackInstallRunner,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(PackInstallService::class.java)

    private val _installs = MutableStateFlow<Map<String, InstallSnapshot>>(emptyMap())
    val installs: StateFlow<Map<String, InstallSnapshot>> = _installs

    // Live jobs keyed the same way as [_installs]. A terminal snapshot outlives
    // its job (the map keeps it until dismissed); the job is removed in finally.
    private val jobs = ConcurrentHashMap<String, Job>()

    fun keyOf(origin: PackOrigin, packId: String, versionId: String): String =
        "$origin:$packId:$versionId"

    /**
     * Start installing (pack, version), or no-op and return the existing key if
     * an install for the same pair is already running. The returned key is what
     * a caller passes to [cancel] / [dismiss] and what it matches against
     * [installs] to observe progress.
     */
    fun start(pack: CataloguePack, version: CataloguePackVersion): String {
        val key = keyOf(pack.origin, pack.id, version.id)
        jobs[key]?.let { if (it.isActive) return key }

        // Dirs the installer reserves before writing. Race-free precise cleanup:
        // on cancel we delete exactly these, never a sibling install's dir.
        val reservedDirs = ConcurrentHashMap.newKeySet<Path>()

        _installs.update {
            it + (key to InstallSnapshot(
                key = key,
                origin = pack.origin,
                packId = pack.id,
                versionId = version.id,
                title = pack.title,
                iconUrl = pack.iconUrl,
                phase = InstallPhase.Running(0, 0, ""),
            ))
        }

        val job = scope.launch {
            try {
                val instance = runInstall(
                    pack,
                    version,
                    { reservedDirs.add(it) },
                    { current, total, filename ->
                        updatePhase(key, InstallPhase.Running(current, total, filename))
                    },
                )
                updatePhase(key, InstallPhase.Succeeded(instance.id))
            } catch (e: CancellationException) {
                updatePhase(key, InstallPhase.Cancelled)
                cleanupReserved(reservedDirs)
                throw e
            } catch (e: Exception) {
                log.warn("install failed for {}", key, e)
                updatePhase(key, InstallPhase.Failed(e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = job
        return key
    }

    /** Abort a running install. The job's cancel handler removes its partial dir. */
    fun cancel(key: String) {
        jobs[key]?.cancel()
    }

    /** Evict a terminal snapshot once its outcome has been consumed by the UI. */
    fun dismiss(key: String) {
        _installs.update { it - key }
    }

    private fun updatePhase(key: String, phase: InstallPhase) {
        _installs.update { current ->
            val existing = current[key] ?: return@update current
            current + (key to existing.copy(phase = phase))
        }
    }

    private fun cleanupReserved(dirs: Set<Path>) {
        for (dir in dirs) {
            runCatching {
                if (Files.isDirectory(dir)) dir.toFile().deleteRecursively()
            }.onFailure { log.warn("failed to remove partial install dir {}", dir, it) }
        }
    }
}
