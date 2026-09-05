package hivens.launcher

import hivens.core.data.PackInstance
import hivens.launcher.instance.InstanceSizeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** Which long operation an instance is running. The two read differently: one puts a
 *  different build in place, the other puts the installed one back. */
enum class PackOperationKind { Update, Repair }

/** Lifecycle of one operation, published under the instance it runs on. */
sealed interface PackOperationPhase {
    data class Running(val current: Int, val total: Int, val path: String) : PackOperationPhase

    /** A finished update: the build now installed. */
    data class Updated(val version: String) : PackOperationPhase

    /** A finished repair. Says what it looked at, not just that it ran. */
    data class Repaired(val checked: Int, val repaired: Int) : PackOperationPhase
    data class Failed(val message: String) : PackOperationPhase
}

/** Observable state of the one operation an instance may have in flight or just finished. */
data class PackOperation(
    val instanceId: String,
    val kind: PackOperationKind,
    val phase: PackOperationPhase,
) {
    val isRunning: Boolean get() = phase is PackOperationPhase.Running
}

/**
 * App-scoped owner of the long operations that rewrite an installed instance --
 * an update apply, a repair. One at a time per instance, on the shared
 * process-lifetime [scope] rather than the composition that started it, with the
 * state published on [operations].
 *
 * Both properties are the point. A window is dismissed by Esc, by its scrim and
 * by its own close button, so a guard held in composable state is reset by every
 * one of those while the work is still running -- and the second run then races
 * the first for the same files, told the user nothing about the one already in
 * flight, and overwrote its progress. Publishing here instead means a re-entered
 * surface finds the operation it left running and narrates that one, and a second
 * start is refused where it is asked for rather than serialised deep in the
 * instance lock.
 *
 * A terminal phase outlives its job and stays until [dismiss], so a surface
 * reopened after the work finished still says how it ended.
 */
class PackOperationService(
    private val scope: CoroutineScope,
    private val sizes: InstanceSizeService,
) {
    private val log = LoggerFactory.getLogger(PackOperationService::class.java)

    private val _operations = MutableStateFlow<Map<String, PackOperation>>(emptyMap())
    val operations: StateFlow<Map<String, PackOperation>> = _operations

    // Live jobs keyed the same way as [_operations]. A terminal phase outlives its
    // job (the map keeps it until dismissed); the job is removed in finally.
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Run [block] as [kind] on [instance], or refuse (returning false) when that
     * instance already has an operation in flight. [block] gets a progress sink
     * and returns the terminal phase; a throw becomes [PackOperationPhase.Failed].
     */
    fun start(
        instance: PackInstance,
        kind: PackOperationKind,
        block: suspend (progress: (current: Int, total: Int, path: String) -> Unit) -> PackOperationPhase,
    ): Boolean {
        val id = instance.id
        jobs[id]?.let { if (it.isActive) return false }

        publish(id, kind, PackOperationPhase.Running(0, 0, ""))
        val job = scope.launch {
            try {
                publish(id, kind, block { current, total, path -> publish(id, kind, PackOperationPhase.Running(current, total, path)) })
            } catch (e: CancellationException) {
                // Only process shutdown cancels these. Drop the entry rather than
                // leaving a Running phase nothing will ever finish.
                _operations.update { it - id }
                throw e
            } catch (e: Exception) {
                log.warn("pack operation {} failed for {}", kind, id, e)
                publish(id, kind, PackOperationPhase.Failed(e.message ?: e::class.simpleName.orEmpty()))
            } finally {
                jobs.remove(id)
                // Whatever the outcome, the files this walked over are not the ones
                // the last size measurement saw.
                sizes.measure(instance, force = true)
            }
        }
        jobs[id] = job
        return true
    }

    /**
     * Evict a finished operation once its outcome has been read. A running one
     * stays: the surface that closes on top of it is not what ends it.
     */
    fun dismiss(instanceId: String) {
        _operations.update { current ->
            if (current[instanceId]?.isRunning != false) current else current - instanceId
        }
    }

    private fun publish(instanceId: String, kind: PackOperationKind, phase: PackOperationPhase) {
        _operations.update { it + (instanceId to PackOperation(instanceId, kind, phase)) }
    }
}
