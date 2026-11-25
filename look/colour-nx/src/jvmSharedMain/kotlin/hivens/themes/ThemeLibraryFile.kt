package hivens.themes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import hivens.themes.api.Scheme
import hivens.themes.api.Theme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * What `themes.json` held when it was read: the library, and whether its selection is one the
 * file really holds. [selectionRead] is false where there is no file, where it cannot be read,
 * and where a newer build wrote it, and then the selection is the default and says nothing of
 * what anyone chose.
 */
data class StoredThemes(
    val library: ThemeLibrary,
    val selectionRead: Boolean = false,
)

/**
 * Reads and writes `themes.json`: the person's own themes, the last wallpaper's colours, and
 * a copy of the chosen theme for a build from before the choice moved into the themes settings.
 *
 * [publish] is supplied rather than performed here: the application has one atomic-write
 * helper, and a second copy of that sequence would be a durability primitive kept in two
 * places. Contract: [publish] writes the whole content or leaves the previous file untouched,
 * and creates missing parent directories.
 */
class ThemeLibraryFile(
    configPath: Path,
    private val publish: (file: Path, content: String) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(ThemeLibraryFile::class.java)
    private val themesFile = configPath.resolve(FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * True when the file was stamped by a build newer than this one. This build cannot
     * represent what it did not understand, so writing would discard it. The host records
     * the fact for the notice it shows, the same way [publish] is handed in rather than
     * performed here.
     */
    @Volatile
    var readOnly: Boolean = false
        private set

    fun load(): StoredThemes {
        if (!Files.exists(themesFile)) return StoredThemes(ThemeLibrary())
        return try {
            val root = json.parseToJsonElement(readUtf8(themesFile)).jsonObject
            // A file written before stamping began has none, and absent means the
            // first version rather than an error.
            val stamp = root[SCHEMA_KEY]?.jsonPrimitive?.intOrNull ?: 1
            when {
                stamp > SCHEMA_VERSION -> {
                    readOnly = true
                    logger.warn(
                        "Theme at {} is {} {} > supported {}: written by a newer build. Loading read-only.",
                        themesFile, SCHEMA_KEY, stamp, SCHEMA_VERSION,
                    )
                    StoredThemes(json.decodeFromJsonElement(ThemeFile.serializer(), root).toLibrary())
                }
                stamp == 1 -> StoredThemes(fromFirstVersion(root), selectionRead = true)
                else -> StoredThemes(json.decodeFromJsonElement(ThemeFile.serializer(), root).toLibrary(), selectionRead = true)
            }
        } catch (e: Exception) {
            logger.warn("Theme at {} could not be read ({}), so the default theme is used", themesFile, e.toString())
            StoredThemes(ThemeLibrary())
        }
    }

    /** Writes [library], its selection as the copy of the choice an older build reads. Whether the file now holds it. */
    fun save(library: ThemeLibrary): Boolean {
        if (readOnly) {
            logger.warn("Not writing {}: it belongs to a newer build and this session is read-only", themesFile)
            return false
        }
        return try {
            val body = json.encodeToJsonElement(ThemeFile.serializer(), ThemeFile.of(library)).jsonObject
            val stamped = JsonObject(body + (SCHEMA_KEY to JsonPrimitive(SCHEMA_VERSION)))
            publish(themesFile, json.encodeToString(JsonObject.serializer(), stamped))
            logger.info("Theme saved: {}", library.selected)
            true
        } catch (e: Exception) {
            logger.error("Failed to save theme", e)
            false
        }
    }

    /**
     * The first version stored one theme as eight named colours. A name that matches
     * a theme that ships selects it. Anything else was somebody's own, and becomes
     * their own theme here: its ground and surface open the ladder and every colour it
     * named is one of its colours. Nothing in it is dropped for not fitting.
     */
    private fun fromFirstVersion(root: JsonObject): ThemeLibrary {
        fun field(key: String): String? = root[key]?.jsonPrimitive?.contentOrNull
        val name = field("name") ?: LEGACY_DEFAULT_NAME
        LEGACY_NAMES[name]?.let { return ThemeLibrary(selected = it) }
        val ground = field("background")?.let(::parseHexOrNull) ?: return ThemeLibrary()
        val surface = field("surface")?.let(::parseHexOrNull) ?: ground
        val colors = listOf("primary", "secondary", "accent", "success", "error")
            .mapNotNull { key -> field(key)?.let(::parseHexOrNull) }
            .distinct()
            .ifEmpty { Themes.default.dark.colors }
        val own = Theme(
            id = "own-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "theme" },
            name = name,
            dark = Scheme(steps = listOf(ground, surface), inks = Themes.Celestia.dark.inks, colors = colors),
        )
        return ThemeLibrary(selected = own.id, own = listOf(own))
    }

    companion object {
        /** The file's name in the data directory. */
        const val FILE_NAME = "themes.json"

        /** Bumped when a change to the file cannot be read by an older build. */
        const val SCHEMA_VERSION = 2
        internal const val SCHEMA_KEY = "schema_version"

        private const val LEGACY_DEFAULT_NAME = "Celestia Dark"

        /**
         * The names the first version stored for the themes it shipped. A theme that no
         * longer ships lands on the default here, rather than coming back as somebody's
         * own from the colours the file kept of it.
         */
        private val LEGACY_NAMES: Map<String, String> = mapOf(
            "Celestia Dark" to Themes.Celestia.id,
            "Cyberpunk" to Themes.default.id,
            "Vaporwave" to Themes.default.id,
            "Matrix" to Themes.default.id,
            "Synthwave" to Themes.default.id,
            "Neon Dreams" to Themes.default.id,
            "Abyssal" to Themes.Abyssal.id,
            "Blood Rain" to Themes.BloodRain.id,
            "Lotus Dark" to Themes.Lotus.id,
        )
    }
}

@Serializable
internal data class ThemeFile(
    val selected: String = Themes.default.id,
    val themes: List<ThemeRecord> = emptyList(),
    val wallpaper: List<String> = emptyList(),
) {
    fun toLibrary(): ThemeLibrary = ThemeLibrary(
        selected = selected,
        own = themes.mapNotNull { it.toTheme() },
        wallpaper = wallpaper.mapNotNull { parseHexOrNull(it)?.toArgb() },
    )

    companion object {
        fun of(library: ThemeLibrary) = ThemeFile(
            selected = library.selected,
            themes = library.own.map { ThemeRecord.of(it) },
            wallpaper = library.wallpaper.map { hex(Color(it)) },
        )
    }
}

@Serializable
internal data class ThemeRecord(
    val id: String,
    val name: String,
    val dark: SchemeRecord,
    val light: SchemeRecord? = null,
) {
    /** Null when the record cannot make a scheme, so one bad entry costs only itself. */
    fun toTheme(): Theme? = runCatching {
        Theme(id = id, name = name, dark = dark.toScheme(), light = light?.toScheme())
    }.getOrNull()

    companion object {
        fun of(theme: Theme) = ThemeRecord(theme.id, theme.name, SchemeRecord.of(theme.dark), theme.light?.let(SchemeRecord::of))
    }
}

@Serializable
internal data class SchemeRecord(
    val steps: List<String> = emptyList(),
    val inks: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
) {
    fun toScheme(): Scheme = Scheme(
        steps = steps.mapNotNull(::parseHexOrNull),
        inks = inks.mapNotNull(::parseHexOrNull),
        colors = colors.mapNotNull(::parseHexOrNull),
    )

    companion object {
        fun of(scheme: Scheme) = SchemeRecord(scheme.steps.map(::hex), scheme.inks.map(::hex), scheme.colors.map(::hex))
    }
}

/** What [hex] wrote, and a colour a person wrote by hand: `#RRGGBB` or `#AARRGGBB`, the `#` optional. Null for anything else. */
private fun parseHexOrNull(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6 && digits.length != 8) return null
    return (if (digits.length == 6) "FF$digits" else digits).toLongOrNull(16)?.let { Color(it) }
}

private fun hex(color: Color): String {
    val argb = color.toArgb()
    return if (argb ushr 24 == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}

/** Whole-file UTF-8 read that rejects malformed input, as `Files.readString` does and Android's `java.nio.file.Files` does not offer. */
private fun readUtf8(file: Path): String =
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
