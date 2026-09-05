package hivens.launcher.instance

import hivens.core.data.PackInstance
import hivens.core.net.BlockMapStore
import hivens.core.net.TransferStaging
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** One measurement of an instance's on-disk size, with when it was taken. */
data class InstanceSize(val bytes: Long, val measuredAtMillis: Long)

/**
 * How much disk an installed pack takes, measured once and shared.
 *
 * The answer costs a walk of the whole instance tree, which for a pack with a
 * world in it is tens of gigabytes of directory entries -- so it is measured on
 * the app scope, published on [sizes], and kept. A surface that asks again gets
 * the number it already had; a re-measure happens only when the last one is older
 * than [freshForMs] or when a caller knows the files changed ([measure] with
 * `force`). The previous value stays published while a re-measure runs, so a
 * screen shows a slightly old number rather than a spinner.
 *
 * What is counted is the pack, not the directory. The launcher keeps bookkeeping
 * of its own beside the content -- per-file block maps under
 * [BlockMapStore.DIR_NAME], and the staging file plus journal of a transfer that
 * was interrupted -- and charging the user for machinery they did not install and
 * cannot see makes the figure answer a question nobody asked.
 */
class InstanceSizeService(
    private val dataDir: Path,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
    private val freshForMs: Long = DEFAULT_FRESH_FOR_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(InstanceSizeService::class.java)

    private val _sizes = MutableStateFlow<Map<String, InstanceSize>>(emptyMap())

    /** Last known size per [PackInstance.id]. */
    val sizes: StateFlow<Map<String, InstanceSize>> = _sizes

    // One walk per instance at a time: two surfaces asking at once share the
    // measurement instead of each walking the same tree.
    private val walks = ConcurrentHashMap<String, Job>()

    /**
     * Publish a size for [instance], walking the tree only when the last
     * measurement is missing or older than [freshForMs]. [force] re-measures
     * regardless -- for a caller that just rewrote the instance's files and knows
     * the published number no longer holds.
     */
    fun measure(instance: PackInstance, force: Boolean = false) {
        val id = instance.id
        if (!force && isFresh(_sizes.value[id])) return
        walks[id]?.let { if (it.isActive) return }

        val job = scope.launch {
            try {
                val bytes = withContext(ioDispatcher) { walk(instanceDirOf(instance)) }
                _sizes.update { it + (id to InstanceSize(bytes, clock.nowMillis())) }
            } catch (e: IOException) {
                // An unreadable instance dir leaves whatever was published before:
                // the row keeps its last honest number instead of blanking.
                log.warn("size: could not measure instance {}", instance.instanceDirName, e)
            } finally {
                walks.remove(id)
            }
        }
        walks[id] = job
    }

    /** Drop the published size for [instanceId] -- the instance is gone. */
    fun forget(instanceId: String) {
        walks.remove(instanceId)?.cancel()
        _sizes.update { it - instanceId }
    }

    private fun isFresh(size: InstanceSize?): Boolean =
        size != null && clock.nowMillis() - size.measuredAtMillis < freshForMs

    private fun instanceDirOf(instance: PackInstance): Path =
        dataDir.resolve("instances").resolve(instance.instanceDirName)

    /**
     * Sums the regular files under [dir] that belong to the pack. Block-map
     * directories are skipped whole (they are the launcher's own, and not
     * descending them also spares the walk their entries), staging files by name.
     * An entry that cannot be read counts as nothing rather than aborting the
     * walk -- a size is worth reporting slightly low, not at all.
     */
    private fun walk(dir: Path): Long {
        if (!Files.isDirectory(dir)) return 0L
        var total = 0L
        Files.walkFileTree(
            dir,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (path.fileName?.toString() == BlockMapStore.DIR_NAME) FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && !TransferStaging.isStaging(path.fileName.toString())) {
                        total += attrs.size()
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        return total
    }

    companion object {
        /**
         * How long a measurement is served without re-walking. Between operations
         * the only thing that grows an instance is a play session writing worlds
         * and logs, so a number a few minutes old is still the right one to show
         * while a fresher one is being taken.
         */
        const val DEFAULT_FRESH_FOR_MS: Long = 5 * 60 * 1000L
    }
}
