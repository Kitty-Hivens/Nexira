package hivens.packs

import hivens.launcher.PackOperation
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.packs.api.OperationStatus
import hivens.packs.api.PackOperationView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The last long operation on each pack and how it ended, since commands give no
 * answer of their own.
 *
 * An update, a repair and a reattach are the operation service's, which publishes
 * them while they run and after they end. A delete is not one of its operations, so
 * the delete command tells this directly. Whichever changed last for a pack is what
 * the pack shows: a delete after an update replaces the update's outcome, and the
 * next operation the service starts replaces the delete's.
 */
class PackOutcomes(operations: StateFlow<Map<String, PackOperation>>, scope: CoroutineScope) {

    private val lock = Any()

    private val _all = MutableStateFlow(operations.value.mapValues { (_, op) -> op.toView() })

    /** The last operation per pack id. */
    val all: StateFlow<Map<String, PackOperationView>> = _all.asStateFlow()

    // What the service last published per pack, to tell its own changes from a
    // delete written over them.
    private var seen: Map<String, PackOperation> = operations.value

    init {
        scope.launch {
            operations.collect { now ->
                synchronized(lock) {
                    val changed = now.filter { (id, op) -> seen[id] != op }
                    // An entry the service dropped goes only where it is still what shows.
                    val dropped = (seen.keys - now.keys).filter { id -> _all.value[id] == seen.getValue(id).toView() }
                    seen = now
                    _all.update { current -> current - dropped.toSet() + changed.mapValues { (_, op) -> op.toView() } }
                }
            }
        }
    }

    /** A delete of [packId] started. */
    fun deleting(packId: String) = put(packId, OperationStatus.Running(done = 0, total = 0))

    /** The delete of [packId] ended as [outcome]. */
    fun deleted(packId: String, outcome: DeleteOutcome) = put(packId, outcome.toStatus())

    /** The delete of [packId] threw [cause], or was cancelled. */
    fun deleteFailed(packId: String, cause: Throwable) {
        val status = if (cause is CancellationException) {
            OperationStatus.Failed(CODE_CANCELLED)
        } else {
            OperationStatus.Failed(CODE_FAILED, cause.message ?: cause.javaClass.simpleName)
        }
        put(packId, status)
    }

    private fun put(packId: String, status: OperationStatus) {
        synchronized(lock) { _all.update { it + (packId to PackOperationView(KIND_DELETE, status)) } }
    }

    internal companion object {
        const val KIND_DELETE = "delete"
        const val CODE_FAILED = "failed"
        const val CODE_CANCELLED = "cancelled"
    }
}
