package hivens.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * What the drawing tests here draw with.
 *
 * nx-ui draws whatever theme it is handed and ships none: the themes that ship belong to the
 * themes module, which sits above this one. These tests hold a copy of the palette they were
 * written against, Celestia in both modes as it shipped when the themes moved out, so what they
 * measure stays what they measured. Whether the shipped themes keep their promises is held by
 * the themes module's own tests.
 */
internal val TestTheme = Theme(
    id = "celestia",
    name = "Celestia",
    dark = Scheme(
        steps = listOf(Color(0xFF121212), Color(0xFF181818), Color(0xFF1E1E1E), Color(0xFF222222), Color(0xFF2A2A2A)),
        inks = listOf(Color(0xFFEEEEEE), Color(0xFFB0B0B0)),
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
