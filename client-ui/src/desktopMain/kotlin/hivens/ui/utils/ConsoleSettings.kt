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
) {
    companion object {
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 22
    }

    /** Clamp font size into the supported range; the in-window slider is
     *  bound to this range too, but a malformed JSON file should not break
     *  rendering. */
    fun coerced(): ConsoleSettings = copy(
        fontSize = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
    )
}

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
