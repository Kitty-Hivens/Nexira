package hivens.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The themes that ship.
 *
 * Celestia is the hand palette the interface was measured against, both modes, as it
 * was. The others are what their authors declared and nothing more: their own ground
 * and surface open the ladder, and every colour they named is one of their colours.
 * They have no light scheme yet, and no ink of their own, so they read with
 * Celestia's dark inks until they are drawn again.
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

    val Cyberpunk = authored("cyberpunk", "Cyberpunk", 0xFF0A0E27, 0xFF1A1F3A, 0xFFFF006E, 0xFF00F5FF, 0xFFFFBE0B, 0xFF00F5A0)
    val Vaporwave = authored("vaporwave", "Vaporwave", 0xFF05091B, 0xFF0E1428, 0xFFFF71CE, 0xFF01CDFE, 0xFFB967FF, 0xFF05FFA1, 0xFFFF006E)
    val Matrix = authored("matrix", "Matrix", 0xFF0D0208, 0xFF1A1A1A, 0xFF00FF41, 0xFF008F11, 0xFFFF0000)
    val Synthwave = authored("synthwave", "Synthwave", 0xFF0F0E17, 0xFF1C1B29, 0xFFF72585, 0xFF7209B7, 0xFF3A0CA3, 0xFF4CC9F0)
    val NeonDreams = authored("neon-dreams", "Neon Dreams", 0xFF1A0033, 0xFF2D0052, 0xFFFF10F0, 0xFFFF6EC7, 0xFF39FF14, 0xFFFF073A)
    val Abyssal = authored("abyssal", "Abyssal", 0xFF050A14, 0xFF0A1628, 0xFF4FC3F7, 0xFF0D47A1, 0xFF00BCD4, 0xFF26C6DA, 0xFFEF5350)
    val BloodRain = authored("blood-rain", "Blood Rain", 0xFF0A0303, 0xFF1C0A0C, 0xFFA01818, 0xFF4A0810, 0xFF6B1525, 0xFF4A5A35, 0xFFE53935)
    val LotusDark = authored("lotus-dark", "Lotus Dark", 0xFF141414, 0xFF1C1C1C, 0xFFFFB3D6, 0xFFAB9DF2, 0xFF78DCE8, 0xFFA9DC76, 0xFFFF6188)

    val all: List<Theme> = listOf(Celestia, Cyberpunk, Vaporwave, Matrix, Synthwave, NeonDreams, Abyssal, BloodRain, LotusDark)

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
