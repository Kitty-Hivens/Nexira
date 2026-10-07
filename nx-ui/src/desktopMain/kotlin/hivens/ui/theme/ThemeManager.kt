package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import hivens.ui.nx.parseHexOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The themes a person can choose from and the one they chose: everything that ships,
 * the one made from their wallpaper, and the ones that are their own.
 *
 * Their own are themes they brought in through a file or that an older build stored
 * in a shape this one has replaced. A selection naming a theme that no longer exists
 * lands on the default rather than on nothing, and so does the wallpaper theme while
 * there is no wallpaper to make it from.
 *
 * [wallpaper] is the last wallpaper's colours, best first. Kept with the selection so
 * a launcher that opens on the wallpaper theme opens in it, rather than in the default
 * until the picture has decoded.
 */
data class ThemeLibrary(
    val selected: String = Themes.default.id,
    val own: List<Theme> = emptyList(),
    val wallpaper: List<Int> = emptyList(),
) {
    /** The theme made from [wallpaper], null without one. Named in English: a screen shows its own word for it. */
    val fromWallpaper: Theme? by lazy { themeFromWallpaper(wallpaper, "Wallpaper") }

    val all: List<Theme> get() = Themes.all + listOfNotNull(fromWallpaper) + own

    val active: Theme get() = all.firstOrNull { it.id == selected } ?: Themes.default
}

/**
 * Reads and writes `themes.json`.
 *
 * [publish] is supplied rather than performed here because this module has no
 * project dependencies and cannot reach the launcher's atomic-write helper. The app
 * root passes that helper in. Contract: [publish] writes the whole content or leaves
 * the previous file untouched, and creates missing parent directories.
 */
class ThemeManager(
    configPath: Path,
    private val publish: (file: Path, content: String) -> Unit,
) {
    private val logger = LoggerFactory.getLogger(ThemeManager::class.java)
    private val themesFile = configPath.resolve("themes.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    /**
     * True when the file was stamped by a build newer than this one. This build cannot
     * represent what it did not understand, so writing would discard it. The app root
     * records the fact, the same way [publish] is handed in rather than performed here.
     */
    @Volatile
    var readOnly: Boolean = false
        private set

    fun load(): ThemeLibrary {
        if (!Files.exists(themesFile)) return ThemeLibrary()
        return try {
            val root = json.parseToJsonElement(Files.readString(themesFile)).jsonObject
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
                    json.decodeFromJsonElement(ThemeFile.serializer(), root).toLibrary()
                }
                stamp == 1 -> fromFirstVersion(root)
                else -> json.decodeFromJsonElement(ThemeFile.serializer(), root).toLibrary()
            }
        } catch (e: Exception) {
            logger.warn("Theme at {} could not be read ({}); falling back to the default theme", themesFile, e.toString())
            ThemeLibrary()
        }
    }

    fun save(library: ThemeLibrary) {
        if (readOnly) {
            logger.warn("Not writing {}: it belongs to a newer build and this session is read-only", themesFile)
            return
        }
        try {
            val body = json.encodeToJsonElement(ThemeFile.serializer(), ThemeFile.of(library)).jsonObject
            val stamped = JsonObject(body + (SCHEMA_KEY to JsonPrimitive(SCHEMA_VERSION)))
            publish(themesFile, json.encodeToString(JsonObject.serializer(), stamped))
            logger.info("Theme saved: {}", library.selected)
        } catch (e: Exception) {
            logger.error("Failed to save theme", e)
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
        /** Bumped when a change to the file cannot be read by an older build. */
        const val SCHEMA_VERSION = 2
        internal const val SCHEMA_KEY = "schema_version"

        private const val LEGACY_DEFAULT_NAME = "Celestia Dark"

        /** The names the first version stored for the themes that ship. */
        private val LEGACY_NAMES: Map<String, String> = mapOf(
            "Celestia Dark" to Themes.Celestia.id,
            "Cyberpunk" to Themes.Cyberpunk.id,
            "Vaporwave" to Themes.Vaporwave.id,
            "Matrix" to Themes.Matrix.id,
            "Synthwave" to Themes.Synthwave.id,
            "Neon Dreams" to Themes.NeonDreams.id,
            "Abyssal" to Themes.Abyssal.id,
            "Blood Rain" to Themes.BloodRain.id,
            "Lotus Dark" to Themes.LotusDark.id,
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

private fun hex(color: Color): String {
    val argb = color.toArgb()
    return if (argb ushr 24 == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}
