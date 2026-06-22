package hivens.ui.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

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

class ConsoleSettingsManager(
    configPath: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(ConsoleSettingsManager::class.java)
    private val settingsFile = configPath.resolve("console.json")

    fun load(): ConsoleSettings {
        if (!Files.exists(settingsFile)) return ConsoleSettings()
        return try {
            json.decodeFromString<ConsoleSettings>(Files.readString(settingsFile)).coerced()
        } catch (e: Exception) {
            log.error("Failed to load console settings", e)
            ConsoleSettings()
        }
    }

    fun save(settings: ConsoleSettings) {
        try {
            Files.createDirectories(settingsFile.parent)
            Files.writeString(settingsFile, json.encodeToString(settings.coerced()))
        } catch (e: Exception) {
            log.error("Failed to save console settings", e)
        }
    }
}
