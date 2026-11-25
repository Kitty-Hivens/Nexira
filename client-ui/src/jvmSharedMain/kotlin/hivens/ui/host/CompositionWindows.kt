package hivens.ui.host

import hivens.widget.model.CompositionName
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("hivens.ui.host.CompositionWindows")

/**
 * The compositions of the document shown in a window of their own, the windows of the launcher itself beside the main
 * one: what the kernel's window keys answer and change. One for the process, so a window stays open while the
 * interface starts over. [has] says which compositions the document has.
 *
 * The host draws a window for each composition [open] names and takes it down when it leaves. Safe to call from any
 * thread.
 */
class CompositionWindows(private val has: (CompositionName) -> Boolean) {
    private val _open = MutableStateFlow<Set<CompositionName>>(emptySet())

    val open: StateFlow<Set<CompositionName>> = _open.asStateFlow()

    private val _raises = MutableSharedFlow<CompositionName>(extraBufferCapacity = RAISES)

    /** A composition already open that was asked for again, so its window comes to the front. */
    val raises: SharedFlow<CompositionName> = _raises

    /** Opens a window for [name], or raises the one it has. False for a composition the document does not have. */
    fun show(name: CompositionName): Boolean {
        if (!has(name)) {
            log.warn("No window for '{}': the document has no such composition", name.value)
            return false
        }
        var added = false
        _open.update { if (name in it) it else (it + name).also { added = true } }
        if (!added) {
            val _ = _raises.tryEmit(name)
        }
        return true
    }

    fun close(name: CompositionName) {
        _open.update { it - name }
    }

    /**
     * Closes every window, after the interface crashed: a window stays open across the restart that follows, so one
     * that crashed it would open again by itself and crash every restart until the crash guard gave up on the
     * interface. Asked for again, it opens as before.
     */
    fun closeAfterCrash() {
        _open.value = emptySet()
    }

    private companion object {
        const val RAISES = 8
    }
}
