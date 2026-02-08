package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Кастомная тема
 */
data class CustomTheme(
    val name: String,
    val primary: String,
    val secondary: String,
    val background: String,
    val surface: String,
    val accent: String,
    val success: String,
    val error: String
) {
    fun toCelestiaColors(isDark: Boolean): CelestiaColors {
        return CelestiaColors(
            primary = parseHexColor(primary),
            primaryVariant = parseHexColor(primary).copy(alpha = 0.8f),
            secondary = parseHexColor(secondary),
            background = parseHexColor(background),
            surface = parseHexColor(surface),
            error = parseHexColor(error),
            onPrimary = if (isDark) Color.Black else Color.White,
            onSecondary = Color.Black,
            onBackground = if (isDark) Color.White else Color.Black,
            onSurface = if (isDark) Color.White else Color.Black,
            textPrimary = if (isDark) Color(0xFFEEEEEE) else Color(0xFF263238),
            textSecondary = if (isDark) Color(0xFFB0B0B0) else Color(0xFF78909C),
            glassBackground = parseHexColor(background),
            glassAlpha = if (isDark) 0.6f else 0.65f,
            success = parseHexColor(success)
        )
    }
    
    fun toJson(): String {
        return """{"name":"$name","primary":"$primary","secondary":"$secondary","background":"$background","surface":"$surface","accent":"$accent","success":"$success","error":"$error"}"""
    }
    
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
            } catch (e: Exception) {
                Color.White
            }
        }
        
        fun fromJson(json: String): CustomTheme? {
            return try {
                val name = json.substringAfter("\"name\":\"").substringBefore("\"")
                val primary = json.substringAfter("\"primary\":\"").substringBefore("\"")
                val secondary = json.substringAfter("\"secondary\":\"").substringBefore("\"")
                val background = json.substringAfter("\"background\":\"").substringBefore("\"")
                val surface = json.substringAfter("\"surface\":\"").substringBefore("\"")
                val accent = json.substringAfter("\"accent\":\"").substringBefore("\"")
                val success = json.substringAfter("\"success\":\"").substringBefore("\"")
                val error = json.substringAfter("\"error\":\"").substringBefore("\"")
                
                CustomTheme(name, primary, secondary, background, surface, accent, success, error)
            } catch (e: Exception) {
                null
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

    fun getAll() = listOf(
        CELESTIA_DARK,
        CYBERPUNK,
        VAPORWAVE,
        MATRIX,
        SYNTHWAVE,
        NEON_PINK
    )
}

class ThemeManager(configPath: Path) {
    private val logger = LoggerFactory.getLogger(ThemeManager::class.java)
    private val themesFile = configPath.resolve("themes.json")

    fun loadTheme(): CustomTheme {
        return try {
            if (Files.exists(themesFile)) {
                val content = Files.readString(themesFile)
                CustomTheme.fromJson(content) ?: ThemePresets.CELESTIA_DARK
            } else {
                ThemePresets.CELESTIA_DARK
            }
        } catch (e: Exception) {
            logger.error("Failed to load theme", e)
            ThemePresets.CELESTIA_DARK
        }
    }

    fun saveTheme(theme: CustomTheme) {
        try {
            Files.createDirectories(themesFile.parent)
            Files.writeString(themesFile, theme.toJson())
            logger.info("Theme saved: ${theme.name}")
        } catch (e: Exception) {
            logger.error("Failed to save theme", e)
        }
    }
}
