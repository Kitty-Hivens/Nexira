package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * A saved theme.
 *
 * Every field carries a default, and that is load-bearing rather than tidy. A
 * theme is authored by the user and stored whole, so a role added in a later
 * release would make every file already on disk fail to decode: the loader would
 * fall back to a preset, and the next save would write that preset over work
 * nobody agreed to lose. With defaults, an older file is simply a file that does
 * not mention the new role yet.
 *
 * The defaults are Celestia Dark's, so a role the file predates arrives at
 * something deliberate instead of white.
 */
@Serializable
data class CustomTheme(
    val name: String = "Celestia Dark",
    val primary: String = "#BB86FC",
    val secondary: String = "#03DAC6",
    // Used only in ThemePickerScreen preview, not applied to the actual theme.
    // Dark/Light toggle controls background and text colors at runtime.
    val background: String = "#121212",
    val surface: String = "#1E1E1E",
    val accent: String = "#BB86FC",
    val success: String = "#4CAF50",
    val error: String = "#CF6679",
) {
    companion object {
        fun parseHexColor(hex: String): Color {
            val cleanHex = hex.removePrefix("#")
            return try {
                val colorInt = cleanHex.toLong(16)
                when (cleanHex.length) {
                    6 -> Color(0xFF000000 or colorInt)
                    8 -> Color(colorInt)
                    else -> Color.White
                }
            } catch (_: Exception) {
                Color.White
            }
        }
    }
}

object ThemePresets {
    val CELESTIA_DARK = CustomTheme(
        name = "Celestia Dark",
        primary = "#BB86FC",
        secondary = "#03DAC6",
        background = "#121212",
        surface = "#1E1E1E",
        accent = "#BB86FC",
        success = "#4CAF50",
        error = "#CF6679"
    )

    val CYBERPUNK = CustomTheme(
        name = "Cyberpunk",
        primary = "#FF006E",
        secondary = "#00F5FF",
        background = "#0A0E27",
        surface = "#1A1F3A",
        accent = "#FFBE0B",
        success = "#00F5A0",
        error = "#FF006E"
    )

    val VAPORWAVE = CustomTheme(
        name = "Vaporwave",
        primary = "#FF71CE",
        secondary = "#01CDFE",
        background = "#05091B",
        surface = "#0E1428",
        accent = "#B967FF",
        success = "#05FFA1",
        error = "#FF006E"
    )

    val MATRIX = CustomTheme(
        name = "Matrix",
        primary = "#00FF41",
        secondary = "#008F11",
        background = "#0D0208",
        surface = "#1A1A1A",
        accent = "#00FF41",
        success = "#00FF41",
        error = "#FF0000"
    )

    val SYNTHWAVE = CustomTheme(
        name = "Synthwave",
        primary = "#F72585",
        secondary = "#7209B7",
        background = "#0F0E17",
        surface = "#1C1B29",
        accent = "#3A0CA3",
        success = "#4CC9F0",
        error = "#F72585"
    )

    val NEON_PINK = CustomTheme(
        name = "Neon Dreams",
        primary = "#FF10F0",
        secondary = "#FF6EC7",
        background = "#1A0033",
        surface = "#2D0052",
        accent = "#FF10F0",
        success = "#39FF14",
        error = "#FF073A"
    )

    val ABYSSAL = CustomTheme(
        name = "Abyssal",
        primary = "#4FC3F7",
        secondary = "#0D47A1",
        background = "#050A14",
        surface = "#0A1628",
        accent = "#00BCD4",
        success = "#26C6DA",
        error = "#EF5350"
    )

    /**
     * Blood Rain -- gothic dark-red palette. Sits opposite to the cool-electric
     * presets (Cyberpunk / Vaporwave / Synthwave / Neon Dreams). All accents
     * stay inside the warm-dark red family -- no cool counterpoint -- so the
     * mood reads as "blood rain on a moonless night" rather than "blood AND
     * water". Atmospheric darkness substitutes for a literal cold-blue rain.
     */
    val BLOOD_RAIN = CustomTheme(
        name = "Blood Rain",
        primary    = "#A01818",   // fresh blood
        secondary  = "#4A0810",   // deeper pooled blood
        background = "#0A0303",   // night void with red undertone
        surface    = "#1C0A0C",   // wet stone, crimson-tinted
        accent     = "#6B1525",   // burgundy -- tonal variation in the same family
        success    = "#4A5A35",   // dark moss -- life persisting in the rain
        error      = "#E53935"    // alarm-bright red, distinct from primary
    )

    // Lotus Dark -- the IntelliJ "Lotus Dark" theme mapped to our palette:
    // near-black ground, a soft-pink lead, and a Monokai-Pro-family pastel spread
    // (purple, cyan, green, rose). Its on-screen identity is the pink/pastel
    // syntax, not the dark-red accent hex buried in the upstream theme file.
    val LOTUS_DARK = CustomTheme(
        name = "Lotus Dark",
        primary    = "#FFB3D6",   // soft lotus pink (the caret colour leads)
        secondary  = "#AB9DF2",   // pastel purple
        background = "#141414",   // near-black
        surface    = "#1C1C1C",   // panels
        accent     = "#78DCE8",   // pastel cyan
        success    = "#A9DC76",   // pastel green
        error      = "#FF6188"    // rose-red
    )

    fun getAll() = listOf(
        CELESTIA_DARK,
        CYBERPUNK,
        VAPORWAVE,
        MATRIX,
        SYNTHWAVE,
        NEON_PINK,
        ABYSSAL,
        BLOOD_RAIN,
        LOTUS_DARK,
    )
}

/**
 * A user's saved theme is authored, not derived -- nothing regenerates it -- so it
 * has to be published whole or not at all.
 *
 * [publish] is supplied rather than performed here because this module has no
 * project dependencies and cannot reach the launcher's atomic-write helper. The
 * app root passes that helper in. Copying the tmp-fsync-rename sequence into this
 * module to keep it self-contained would put a second copy of a durability
 * primitive in the tree, and two copies drift.
 *
 * Contract: [publish] writes the whole content or leaves the previous file
 * untouched, and creates missing parent directories.
 */
class ThemeManager(
    configPath: Path,
    private val publish: (file: Path, content: String) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(ThemeManager::class.java)
    private val themesFile = configPath.resolve("themes.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * True when the file was stamped by a build newer than this one.
     *
     * The layout store already refuses to write such a file back, for the reason
     * that applies here unchanged: this build cannot represent what it did not
     * understand, so writing would discard it. This module cannot reach the
     * launcher's notice registry, so it reports the fact and the app root records
     * it, the same way [publish] is handed in rather than performed here.
     */
    @Volatile
    var readOnly: Boolean = false
        private set

    fun loadTheme(): CustomTheme {
        if (!Files.exists(themesFile)) return ThemePresets.CELESTIA_DARK
        return try {
            val text = Files.readString(themesFile)
            // Read the stamp off the raw object: a file written before stamping
            // began has none, and absent means the first version rather than an
            // error.
            val stamp = runCatching {
                json.parseToJsonElement(text).jsonObject[SCHEMA_KEY]?.jsonPrimitive?.intOrNull
            }.getOrNull() ?: 1
            if (stamp > SCHEMA_VERSION) {
                readOnly = true
                logger.warn(
                    "Theme at {} is {} {} > supported {} -- written by a newer build. Loading read-only; " +
                        "this session will not write it back.",
                    themesFile, SCHEMA_KEY, stamp, SCHEMA_VERSION,
                )
            }
            json.decodeFromString<CustomTheme>(text)
        } catch (e: Exception) {
            // Every field has a default, so reaching here means the file is not a
            // theme at all rather than merely an older one. Say which, because the
            // old message said nothing and the fallback looks identical to the
            // user having chosen the preset.
            logger.warn("Theme at {} could not be read ({}); falling back to the default preset", themesFile, e.toString())
            ThemePresets.CELESTIA_DARK
        }
    }

    fun saveTheme(theme: CustomTheme) {
        if (readOnly) {
            logger.warn("Not writing {} -- it belongs to a newer build and this session is read-only", themesFile)
            return
        }
        try {
            val body = json.encodeToJsonElement(CustomTheme.serializer(), theme).jsonObject
            val stamped = JsonObject(body + (SCHEMA_KEY to JsonPrimitive(SCHEMA_VERSION)))
            publish(themesFile, json.encodeToString(JsonObject.serializer(), stamped))
            logger.info("Theme saved: ${theme.name}")
        } catch (e: Exception) {
            logger.error("Failed to save theme", e)
        }
    }

    companion object {
        /** Bumped when a change to [CustomTheme] cannot be read by an older build. */
        const val SCHEMA_VERSION = 1
        internal const val SCHEMA_KEY = "schema_version"
    }
}
