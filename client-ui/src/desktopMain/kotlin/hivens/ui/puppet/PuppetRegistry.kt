package hivens.ui.puppet

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of "puppet-controllable" UI elements. Composables
 * register themselves on entry (composition) and unregister on disposal
 * via the helpers in [PuppetModifiers]; the registry therefore always
 * mirrors the LIVE UI surface -- no stale entries for screens that
 * aren't currently mounted.
 *
 * Read by [PuppetServer] when handling HTTP queries; mutated only via
 * Composable side effects. Backed by [ConcurrentHashMap] so the HTTP
 * thread can read snapshots without blocking the UI thread, and
 * registration churn from rapid recomposition stays correct.
 *
 * **State mutation thread.** Calls into [click] / [setField] /
 * [setToggle] invoke caller-supplied lambdas that typically touch
 * `mutableStateOf` -- those MUST run on the AWT EDT, which is Compose
 * Desktop's UI thread. The HTTP layer in [PuppetServer] is responsible
 * for hopping onto the EDT before calling these methods; the registry
 * itself doesn't dispatch.
 */
internal object PuppetRegistry {

    private data class ClickEntry(
        val onClick: () -> Unit,
        val enabled: () -> Boolean,
    )

    private data class FieldEntry(
        val getValue: () -> String,
        val setValue: (String) -> Unit,
        val enabled: () -> Boolean,
    )

    private data class ToggleEntry(
        val getValue: () -> Boolean,
        val setValue: (Boolean) -> Unit,
        val enabled: () -> Boolean,
    )

    private val clicks  = ConcurrentHashMap<String, ClickEntry>()
    private val fields  = ConcurrentHashMap<String, FieldEntry>()
    private val toggles = ConcurrentHashMap<String, ToggleEntry>()

    @Volatile private var currentScreen: String = "Unknown"

    fun registerClick(id: String, enabled: () -> Boolean, onClick: () -> Unit) {
        clicks[id] = ClickEntry(onClick, enabled)
    }

    fun unregisterClick(id: String) { clicks.remove(id) }

    fun registerField(
        id: String,
        getValue: () -> String,
        setValue: (String) -> Unit,
        enabled: () -> Boolean,
    ) {
        fields[id] = FieldEntry(getValue, setValue, enabled)
    }

    fun unregisterField(id: String) { fields.remove(id) }

    fun registerToggle(
        id: String,
        getValue: () -> Boolean,
        setValue: (Boolean) -> Unit,
        enabled: () -> Boolean,
    ) {
        toggles[id] = ToggleEntry(getValue, setValue, enabled)
    }

    fun unregisterToggle(id: String) { toggles.remove(id) }

    fun setCurrentScreen(name: String) { currentScreen = name }

    fun snapshot(): PuppetSnapshot {
        val elements = buildList {
            clicks.forEach  { (id, e) -> add(PuppetElement(id, "click",  enabled = e.enabled())) }
            fields.forEach  { (id, e) -> add(PuppetElement(id, "field",  value = e.getValue(), enabled = e.enabled())) }
            toggles.forEach { (id, e) -> add(PuppetElement(id, "toggle", boolValue = e.getValue(), enabled = e.enabled())) }
        }.sortedBy { it.id }
        return PuppetSnapshot(currentScreen, elements)
    }

    fun click(id: String): Result<Unit> {
        val entry = clicks[id]
            ?: return Result.failure(NoSuchElementException("no click handler for id=$id"))
        if (!entry.enabled()) {
            return Result.failure(IllegalStateException("element id=$id is disabled"))
        }
        return runCatching { entry.onClick() }
    }

    fun setField(id: String, value: String): Result<Unit> {
        val entry = fields[id]
            ?: return Result.failure(NoSuchElementException("no field for id=$id"))
        if (!entry.enabled()) {
            return Result.failure(IllegalStateException("element id=$id is disabled"))
        }
        return runCatching { entry.setValue(value) }
    }

    fun setToggle(id: String, value: Boolean): Result<Unit> {
        val entry = toggles[id]
            ?: return Result.failure(NoSuchElementException("no toggle for id=$id"))
        if (!entry.enabled()) {
            return Result.failure(IllegalStateException("element id=$id is disabled"))
        }
        return runCatching { entry.setValue(value) }
    }
}
