package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.io.AtomicFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal, Koin-free read/modify/write of the launcher's on-disk state for the
 * recovery surface -- which must not depend on SettingsService/ThemeManager/the
 * widget kernel, since those are exactly what a recovery boot distrusts. Mirrors
 * [SettingsPeek]'s defensive JSON style. Every write preserves keys it does not
 * touch, so a recovery edit never clobbers unrelated settings.
 */
object RecoveryIo {

    // Customization leaf files, each of which its owner re-seeds when absent
    // (ThemeManager, BackgroundManager, ConsoleSettings). Kept as literals
    // mirroring those owners -- they are not Storage constants there.
    private val CUSTOMIZATION_FILES = listOf("themes.json", "background.json", "console.json")

    // Not one of the above, and not a reset the appearance button is allowed to
    // make: this file is what the user typed into their widgets, and nothing else
    // holds a copy of it.
    private const val WIDGET_STATE_FILE = "widget-state.json"

    private val pretty = Json { prettyPrint = true }

    /**
     * Whether this process has deleted state files out from under its own
     * in-memory copies of them.
     *
     * The resets below run while the launcher is up, holding stores loaded from
     * the very files being deleted. Their shutdown hooks then wrote those copies
     * straight back, so a reset survived exactly as long as it took to relaunch
     * and the next boot loaded the same broken state -- which is the whole of
     * what these buttons are for.
     *
     * Once set it stays set: after a reset nothing in memory is authoritative
     * any more, and the surface that offers these buttons relaunches anyway.
     */
    @Volatile
    var stateWasReset: Boolean = false
        private set

    // -- module registry --------------------------------------------------

    fun readDisabledModules(dataDir: Path): Set<String> = runCatching {
        val root = settingsRoot(dataDir) ?: return emptySet()
        (root["disabledModules"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()
    }.getOrDefault(emptySet())

    /** Read-modify-write settings.json, replacing only `disabledModules`. */
    fun writeDisabledModules(dataDir: Path, disabled: Set<String>) {
        val root = settingsRoot(dataDir) ?: JsonObject(emptyMap())
        writeSettings(dataDir, JsonObject(root + ("disabledModules" to modulesArray(disabled))))
    }

    // -- resets -----------------------------------------------------------

    /** Delete the widget layout graph so it re-seeds the bundled default next boot. */
    fun resetLayout(dataDir: Path) {
        stateWasReset = true
        deleteQuietly(dataDir.resolve(Storage.LAYOUT_GRAPH_FILE))
    }

    /** Delete theme / background / console so each re-seeds its default. */
    fun resetCustomization(dataDir: Path) {
        stateWasReset = true
        CUSTOMIZATION_FILES.forEach { deleteQuietly(dataDir.resolve(it)) }
    }

    /**
     * Delete the per-instance widget state: scratchpad bodies, checklist items,
     * whatever else a widget kept for the user.
     *
     * Its own reset rather than part of [resetCustomization], because none of that
     * is appearance and none of it exists anywhere else. It is offered at all
     * because a widget that chokes on its own stored entry has no other way out --
     * [hivens.ui.widgets.state.WidgetStateStore] already survives a corrupt FILE by
     * starting empty, so the whole-store case never needed a button.
     */
    fun resetWidgetState(dataDir: Path) {
        stateWasReset = true
        deleteQuietly(dataDir.resolve(WIDGET_STATE_FILE))
    }

    /**
     * Reset settings to defaults while KEEPING `disabledModules` -- else the reset
     * re-enables the very module the user disabled to recover, re-breaking the next
     * boot. Missing keys decode to their defaults, so an all-but-modules object is
     * a full reset.
     *
     * "Defaults" means what a fresh install gets, [FirstRunDefaults] included: a
     * reset is someone starting over, and dropping them into English on a light
     * desktop is not where a first launch would have put them either.
     */
    fun resetSettings(dataDir: Path) {
        val keep = readDisabledModules(dataDir)
        val root = FirstRunDefaults.firstRunOverrides()
        writeSettings(
            dataDir,
            if (keep.isEmpty()) root else JsonObject(root + ("disabledModules" to modulesArray(keep))),
        )
    }

    /**
     * Clears the reset latch. Internal for unit tests only.
     *
     * [stateWasReset] is process-global and deliberately one-way, so a single
     * test that calls a reset would otherwise silence every store's writes for
     * the rest of the fork and fail unrelated tests.
     */
    internal fun resetForTests() {
        stateWasReset = false
    }

    // -- helpers ----------------------------------------------------------

    private fun modulesArray(ids: Set<String>) = JsonArray(ids.sorted().map { JsonPrimitive(it) })

    private fun settingsRoot(dataDir: Path): JsonObject? = runCatching {
        val file = dataDir.resolve(Storage.SETTINGS_FILE)
        if (!Files.isRegularFile(file)) null
        else Json.parseToJsonElement(Files.readString(file)).jsonObject
    }.getOrNull()

    private fun writeSettings(dataDir: Path, root: JsonObject) {
        runCatching {
            AtomicFiles.writeString(dataDir.resolve(Storage.SETTINGS_FILE), pretty.encodeToString(JsonObject.serializer(), root))
        }
    }

    private fun deleteQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }
}
