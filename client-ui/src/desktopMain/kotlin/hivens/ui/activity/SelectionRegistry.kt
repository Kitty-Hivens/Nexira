package hivens.ui.activity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a bulk action does, so the surface can label and tone it without knowing the view. */
enum class SelectionActionKind { Enable, Disable, Delete }

/**
 * One selected thing, as much of it as a surface needs to show. [icon] is
 * anything the image loader accepts -- an address, or the bytes an archive
 * carries inside itself, which is what most content actually has.
 */
data class SelectionItem(val key: String, val title: String, val icon: Any? = null)

/**
 * [blockedReason] null means the action can run. Set, it is the sentence the
 * surface shows on hover -- a control that is merely dead teaches nothing, and
 * the user is left guessing which of the things they picked is the problem.
 */
data class SelectionAction(
    val kind: SelectionActionKind,
    val blockedReason: String? = null,
    val run: () -> Unit,
)

/**
 * What the user has selected in the view they are looking at.
 *
 * Handlers ride along here, unlike on an [hivens.core.activity.Activity], because
 * a selection is replaced wholesale on every change rather than re-reported as a
 * running job advances -- there is no throttle for a lambda's reference equality
 * to defeat.
 */
data class Selection(
    val items: List<SelectionItem>,
    val actions: List<SelectionAction>,
    val clear: () -> Unit,
)

/**
 * The second half of what the activity surface narrates, and the shorter-lived
 * one. An activity belongs to the launcher and outlives any screen; a selection
 * belongs to the view that owns it and dies when the user leaves.
 *
 * Keeping them apart is what lets one object carry both: the surface applies its
 * own rule for which takes the body, and neither registry has to know the other
 * exists.
 */
class SelectionRegistry {
    private val _selection = MutableStateFlow<Selection?>(null)

    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    /** Publish, or pass null to say nothing is selected. */
    fun set(value: Selection?) {
        _selection.value = value?.takeIf { it.items.isNotEmpty() }
    }

    /**
     * Drop the selection if it is still the one [owner] published. A view clears
     * on its way out, and without the guard a slow teardown would wipe whatever
     * the next view had already put up.
     */
    fun clearIf(owner: Selection?) {
        if (owner != null && _selection.value === owner) _selection.value = null
    }
}
