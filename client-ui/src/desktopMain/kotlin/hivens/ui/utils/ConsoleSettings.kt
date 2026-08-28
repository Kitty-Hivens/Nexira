package hivens.ui.utils

import hivens.core.io.AtomicFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Persisted console preferences. Mirrors the `BackgroundManager` /
 * `CustomizationManager` pattern: one JSON file per domain, loaded
 * eagerly on the surface that owns the state, written back on every
 * change. Fields stay flat (no nested objects yet) so a future
 * migration can add new knobs without re-versioning the file
 * structure -- additive only.
 *
 * Slice 6 ships the high-frequency knobs (font size, wrap, gutter,
 * timestamps). Severity color overrides + user highlight rules
 * arrive in a follow-up because both demand UI built on top of the
 * Settings screen, not just the in-window gear.
 */
@Serializable
data class ConsoleSettings(
    /** Monospace font size in sp. Clamped to [MIN_FONT_SIZE, MAX_FONT_SIZE]. */
    val fontSize: Int = 12,

    /** Soft-wrap toggle. true = wrap to viewport (default); false = horizontal scroll. */
    val wrapText: Boolean = true,

    /** Severity gutter strip visibility. Hide for the most minimal aesthetic. */
    val showGutterStrip: Boolean = true,

    /** `[HH:MM:SS]` prefix on non-divider entries. Disabling helps when timestamps
     *  are noise (e.g. a single short capture session). */
    val showTimestamps: Boolean = true,

    /**
     * In-memory sliding-window size (lines kept live for the
     * AnnotatedString builder; everything older lives in the per-
     * session log file and pages back in on scroll-up). Capped at
     * [MAX_IN_MEMORY_LINES] so the rebuild cost on a flood stays
     * bounded; floor is [MIN_IN_MEMORY_LINES] to keep enough
     * context in the live view to be useful.
     */
    val maxInMemoryLines: Int = 5000,

    // --- Appearance overrides (Settings > Console). Null = use the theme. ---
    /** Override hex for INFO-severity text, e.g. "#EEEEEE". Null keeps the theme. */
    val infoColor: String? = null,
    val warnColor: String? = null,
    val errorColor: String? = null,

    /**
     * User highlight rules: a line whose text matches [HighlightRule.pattern]
     * is recoloured. First enabled match wins. Applied on top of severity colour.
     */
    val highlightRules: List<HighlightRule> = emptyList(),

    /**
     * User filter/mute rules: a line matching an enabled [FilterRule] is hidden
     * from the view entirely (persistent noise mute), separate from the in-window
     * one-shot search filter.
     */
    val filterRules: List<FilterRule> = emptyList(),

    /**
     * User-supplied empty-console art (ASCII / Braille), managed from the Settings
     * UI. Joins the built-in shapes + the bundled art file; one is shown at random.
     */
    val customArt: List<String> = emptyList(),
) {
    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 22

        const val MIN_IN_MEMORY_LINES = 1000
        const val MAX_IN_MEMORY_LINES = 50000
    }

    /** Clamp every bounded knob into its supported range; the in-window
     *  gear UI honors the same bounds, but a malformed JSON file should
     *  not break rendering. */
    fun coerced(): ConsoleSettings = copy(
        fontSize         = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
        maxInMemoryLines = maxInMemoryLines.coerceIn(MIN_IN_MEMORY_LINES, MAX_IN_MEMORY_LINES),
    )
}

/** One highlight rule: a [pattern] (substring, or treated as a regex when [regex])
 *  that recolours a matching console line to [colorHex], optionally [bold]. Disabled
 *  rules are skipped; the first enabled match on a line wins. */
@Serializable
data class HighlightRule(
    val pattern: String = "",
    val regex: Boolean = false,
    val colorHex: String = "#FFD166",
    val bold: Boolean = false,
    val enabled: Boolean = true,
)

/** One filter/mute rule: a [pattern] (substring, or regex when [regex]) whose matching
 *  lines are hidden from the console entirely. Disabled rules are skipped. */
@Serializable
data class FilterRule(
    val pattern: String = "",
    val regex: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The one owner of `console.json`.
 *
 * Three surfaces read these preferences -- Settings > Console, the standalone
 * console window and the pack's Logs tab -- and each used to load the file into
 * state of its own and write the whole record back on any edit. The shell's copy
 * was taken once at startup and never reloaded, so an edit made in Settings never
 * reached a running console, and the next flip of a switch in that console wrote
 * the startup copy back over the rules Settings had added. One published value
 * means an edit is seen everywhere the moment it is made, and the next edit is
 * built on the value every surface is already rendering.
 *
 * Persistence is debounced: the sliders report continuously while dragged, and a
 * durable write per pointer sample is both wasted and felt. The published value
 * is live regardless, so a killed tail loses at most the last quarter second of a
 * drag -- the same trade the background settings make.
 */
class ConsoleSettingsStore(
    configPath: Path,
    private val json: Json,
    private val scope: CoroutineScope,
    /** Where the write itself runs. A test drives it on its own scheduler. */
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(ConsoleSettingsStore::class.java)
    private val settingsFile = configPath.resolve("console.json")

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ConsoleSettings> = _settings.asStateFlow()

    private var writer: Job? = null

    /** The value the debounced write has not put on disk yet, if any. */
    @Volatile private var unwritten: ConsoleSettings? = null

    init {
        // A quit right after a toggle is a discrete edit, not a drag tail: the
        // debounce must not be what loses it. The scope this store writes on is
        // cancelled by its own shutdown hook, so the flush is a hook of its own --
        // the same shape the pack registry uses to close its environment.
        Runtime.getRuntime().addShutdownHook(Thread({ flush() }, "console-settings-flush"))
    }

    /** The value every surface renders from right now. */
    val current: ConsoleSettings get() = _settings.value

    /** Publish an edit and persist it. Bounded knobs are clamped on the way in. */
    fun update(next: ConsoleSettings) {
        val coerced = next.coerced()
        _settings.value = coerced
        unwritten = coerced
        writer?.cancel()
        writer = scope.launch {
            delay(WRITE_DEBOUNCE_MS.milliseconds)
            withContext(writeDispatcher) { save(coerced) }
            if (unwritten == coerced) unwritten = null
        }
    }

    private fun load(): ConsoleSettings {
        if (!Files.exists(settingsFile)) return ConsoleSettings()
        return try {
            json.decodeFromString<ConsoleSettings>(Files.readString(settingsFile)).coerced()
        } catch (e: Exception) {
            log.error("Failed to load console settings", e)
            ConsoleSettings()
        }
    }

    /** Write whatever the debounce still owes. Idempotent; runs on the caller's thread. */
    fun flush() {
        unwritten?.let {
            unwritten = null
            save(it)
        }
    }

    private fun save(settings: ConsoleSettings) {
        try {
            AtomicFiles.writeString(settingsFile, json.encodeToString(settings))
        } catch (e: Exception) {
            log.error("Failed to save console settings", e)
        }
    }

    private companion object {
        const val WRITE_DEBOUNCE_MS = 250L
    }
}
