package hivens.themes

import androidx.compose.ui.graphics.Color
import hivens.themes.api.Scheme
import hivens.themes.api.Theme

/**
 * The themes that ship.
 *
 * Celestia is the hand palette the interface was measured against, both modes, as it
 * was. Lotus is the Lotus theme for JetBrains IDEs, both modes, with its colours taken
 * from its MIT licensed VS Code port: every value below is one the original uses, and
 * only which of them make the ladder was chosen here. The others are what their
 * authors declared and nothing more: their own ground and surface open the ladder, and
 * every colour they named is one of their colours. They have no light scheme yet, and
 * no ink of their own, so they read with Celestia's dark inks until they are drawn
 * again.
 */
object Themes {

    private val celestiaDarkInks = listOf(Color(0xFFEEEEEE), Color(0xFFB0B0B0))

    val Celestia = Theme(
        id = "celestia",
        name = "Celestia",
        dark = Scheme(
            steps = listOf(Color(0xFF121212), Color(0xFF181818), Color(0xFF1E1E1E), Color(0xFF222222), Color(0xFF2A2A2A)),
            inks = celestiaDarkInks,
            colors = listOf(
                Color(0xFFBB86FC), Color(0xFF03DAC6), Color(0xFF6A84FF),
                Color(0xFF4CAF50), Color(0xFFE0B341), Color(0xFFCF6679),
            ),
        ),
        light = Scheme(
            steps = listOf(Color(0xFFFFFFFF), Color(0xFFF6F6FA), Color(0xFFECEEF2), Color(0xFFE4E6EC), Color(0xFFDADCE4)),
            inks = listOf(Color(0xFF263238), Color(0xFF4F626B)),
            colors = listOf(
                Color(0xFF5E68C0), Color(0xFF26A69A), Color(0xFF3C5BD9),
                Color(0xFF256B2B), Color(0xFF8A5E08), Color(0xFFD32F2F),
            ),
        ),
    )

    val Abyssal = authored("abyssal", "Abyssal", 0xFF050A14, 0xFF0A1628, 0xFF4FC3F7, 0xFF0D47A1, 0xFF00BCD4, 0xFF26C6DA, 0xFFEF5350)
    val BloodRain = authored("blood-rain", "Blood Rain", 0xFF0A0303, 0xFF1C0A0C, 0xFFA01818, 0xFF4A0810, 0xFF6B1525, 0xFF4A5A35, 0xFFE53935)

    // The original's pink is its accent, its focus and its error at once, so it leads.
    // Its dimmest grey text is the quiet ink in the dark, and in the light, where that
    // grey does not read, its ruler grey is.
    val Lotus = Theme(
        id = "lotus",
        name = "Lotus",
        dark = Scheme(
            steps = listOf(Color(0xFF121212), Color(0xFF161616), Color(0xFF1E1E1E), Color(0xFF222222)),
            inks = listOf(Color(0xFFFCFCFA), Color(0xFF939293)),
            colors = listOf(
                Color(0xFFFF6188), Color(0xFFAB9DF2), Color(0xFFA9DC76),
                Color(0xFFFFF48A), Color(0xFF55DBE4), Color(0xFFFC9867),
            ),
        ),
        light = Scheme(
            steps = listOf(Color(0xFFFFF0F6), Color(0xFFFFE3E9), Color(0xFFFFD6DF)),
            inks = listOf(Color(0xFF121212), Color(0xFF5A5A5A)),
            colors = listOf(
                Color(0xFFFF6188), Color(0xFF836DF2), Color(0xFF5CAD0A),
                Color(0xFFC4960C), Color(0xFF0FB7D9), Color(0xFFFC9867),
            ),
        ),
    )

    val Signal = Theme(
        id = "signal",
        name = "Signal",
        dark = Scheme(
            steps = listOf(Color(0xFF1C1C1C), Color(0xFF262626), Color(0xFF303030), Color(0xFF3B3B3B), Color(0xFF474747)),
            inks = listOf(Color(0xFFF2F2F2), Color(0xFFA6A6A6)),
            colors = listOf(Color(0xFFFF2A3D), Color(0xFFF2F2F2), Color(0xFF8C8C8C)),
        ),
        light = Scheme(
            steps = listOf(Color(0xFFFFFFFF), Color(0xFFF2F2F2), Color(0xFFE6E6E6), Color(0xFFD9D9D9), Color(0xFFCCCCCC)),
            inks = listOf(Color(0xFF111111), Color(0xFF5C5C5C)),
            colors = listOf(Color(0xFFE60012), Color(0xFF1A1A1A), Color(0xFF8C8C8C)),
        ),
    )

    val all: List<Theme> = listOf(Celestia, Abyssal, BloodRain, Lotus, Signal)

    val default: Theme get() = Celestia

    fun byId(id: String): Theme? = all.firstOrNull { it.id == id }

    private fun authored(id: String, name: String, ground: Long, surface: Long, vararg colors: Long) = Theme(
        id = id,
        name = name,
        dark = Scheme(
            steps = listOf(Color(ground), Color(surface)),
            inks = celestiaDarkInks,
            colors = colors.map { Color(it) },
        ),
    )
}
