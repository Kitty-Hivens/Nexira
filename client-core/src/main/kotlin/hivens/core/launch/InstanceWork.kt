package hivens.core.launch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Work that rewrites an instance's files, and during which it must not be launched.
 *
 * The one place that answers "is this instance busy", for the launch control that
 * has to say why it will not start a game and for the controller that has to refuse
 * one. Before it, each piece of work knew about itself only: an update from the
 * settings window lived in the operation service, one from the auto-updater lived
 * nowhere a screen could see, and nothing on the launch path asked either of them,
 * so Play started a game over files that were being replaced.
 */
enum class InstanceWork {
    /** Moving to another build, forward or back, including a rollback to a snapshot. */
    Update,

    /** Putting the installed build's files back as the pack describes them. */
    Repair,

    /** Undoing an update a crash interrupted, at startup. */
    Recovery,

    /** Replacing individual mods or resource packs with newer builds. */
    ContentUpdate,

    /** Putting a project from the catalogue into the instance, with what it requires. */
    ContentInstall,

    /** Removing the instance and its files. */
    Delete,
}

/**
 * Which instances have [InstanceWork] in progress, keyed by instance id.
 *
 * Marking is scoped to a block, so the mark cannot outlive the work: a throw or a
 * cancellation clears it on the way out. Two pieces of work on one instance can
 * overlap in principle, so each is counted rather than a single slot being set and
 * cleared, and [current] reports the one started most recently.
 */
class InstanceWorkRegistry {
    private val active = MutableStateFlow<Map<String, List<InstanceWork>>>(emptyMap())
    private val _current = MutableStateFlow<Map<String, InstanceWork>>(emptyMap())

    /** The work in progress per instance id. An instance absent from the map is free. */
    val current: StateFlow<Map<String, InstanceWork>> = _current.asStateFlow()

    /** The work in progress on [instanceId], or null when there is none. */
    fun workOn(instanceId: String): InstanceWork? = _current.value[instanceId]

    /** Runs [block] with [instanceId] marked as busy with [work]. */
    suspend fun <T> during(instanceId: String, work: InstanceWork, block: suspend () -> T): T {
        change(instanceId) { it + work }
        try {
            return block()
        } finally {
            unmark(instanceId, work)
        }
    }

    /**
     * Marks [instanceId] as busy with [work] unless other work holds it, as one
     * step. A look at [workOn] followed by [during] leaves a gap in which two pieces
     * of work both find the instance free and both start. [alongside] is the work
     * this one may share the instance with.
     */
    fun claim(instanceId: String, work: InstanceWork, alongside: Set<InstanceWork> = emptySet()): Claim =
        synchronized(this) {
            active.value[instanceId].orEmpty().lastOrNull { it !in alongside }?.let { return Claim.Taken(it) }
            change(instanceId) { it + work }
            Claim.Held(Mark(instanceId, work))
        }

    /** What [claim] got: the mark, or the work that holds the instance. */
    sealed interface Claim {
        class Held(val mark: Mark) : Claim
        data class Taken(val by: InstanceWork) : Claim
    }

    /** A mark [claim] took. [release] clears it, and releasing it again does nothing. */
    inner class Mark internal constructor(private val instanceId: String, private val work: InstanceWork) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) unmark(instanceId, work)
        }
    }

    private fun unmark(instanceId: String, work: InstanceWork) =
        change(instanceId) { list -> list.toMutableList().also { it.removeAt(it.lastIndexOf(work)) } }

    private fun change(instanceId: String, edit: (List<InstanceWork>) -> List<InstanceWork>) {
        synchronized(this) {
            active.update { all ->
                val next = edit(all[instanceId].orEmpty())
                if (next.isEmpty()) all - instanceId else all + (instanceId to next)
            }
            _current.value = active.value.mapValues { it.value.last() }
        }
    }
}
