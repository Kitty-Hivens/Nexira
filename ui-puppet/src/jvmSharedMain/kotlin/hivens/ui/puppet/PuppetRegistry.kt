package hivens.ui.puppet

import java.util.concurrent.ConcurrentHashMap

/**
 * In-process registry of "puppet-controllable" UI elements. Composables
 * register themselves on entry (composition) and unregister on disposal
 * via the helpers beside it ([PuppetClick], [PuppetField],
 * [PuppetToggle]); the registry therefore always
 * mirrors the LIVE UI surface -- no stale entries for screens that
 * aren't currently mounted.
 *
 * Read by the launcher's puppet server when handling HTTP queries, and mutated only
 * through Composable side effects. Backed by [ConcurrentHashMap] so the HTTP
 * thread can read snapshots without blocking the UI thread, and
 * registration churn from rapid recomposition stays correct.
 *
 * **State mutation thread.** Calls into [click] / [setField] /
 * [setToggle] invoke caller-supplied lambdas that typically touch
 * `mutableStateOf` -- those MUST run on the AWT EDT, which is Compose
 * Desktop's UI thread. The server's HTTP layer is responsible
 * for hopping onto the EDT before calling these methods; the registry
 * itself doesn't dispatch.
 *
 * A module of its own so a module compiled into the launcher registers its controls under the
 * same ids the launcher's own do. It draws nothing and knows no server: the server and its wire
 * format stay with the launcher, which builds them in only for a puppet build.
 */
object PuppetRegistry {

    /** One registered element as [snapshot] reports it: [value] for a field, [boolValue] for a toggle. */
    data class Element(
        val id: String,
        val kind: String,
        val value: String? = null,
        val boolValue: Boolean? = null,
        val enabled: Boolean = true,
    )

    /** The screen last marked by [PuppetScreen] and every element registered now, by id. */
    data class Snapshot(val screen: String, val elements: List<Element>)

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

    fun snapshot(): Snapshot {
        val elements = buildList {
            clicks.forEach  { (id, e) -> add(Element(id, "click",  enabled = e.enabled())) }
            fields.forEach  { (id, e) -> add(Element(id, "field",  value = e.getValue(), enabled = e.enabled())) }
            toggles.forEach { (id, e) -> add(Element(id, "toggle", boolValue = e.getValue(), enabled = e.enabled())) }
        }.sortedBy { it.id }
        return Snapshot(currentScreen, elements)
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
