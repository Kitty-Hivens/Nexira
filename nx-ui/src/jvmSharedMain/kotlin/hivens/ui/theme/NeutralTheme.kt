package hivens.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * What a window is drawn in where nothing hands it a theme: a launcher whose document brings in no
 * themes module, or a value the boot window needs that no module answered.
 *
 * nx-ui ships no theme a person chooses, those are the themes module's. This one is the floor under
 * them, and neutral on purpose: a grey ladder, grey inks and one grey colour, so a window drawn in it
 * reads as drawn in no theme rather than in one somebody picked. Both modes, so the day and night
 * switch still has something to switch.
 */
val NeutralTheme: Theme = Theme(
    id = "neutral",
    name = "Neutral",
    dark = Scheme(
        steps = listOf(Color(0xFF161616), Color(0xFF1D1D1D), Color(0xFF242424), Color(0xFF2C2C2C)),
        inks = listOf(Color(0xFFE8E8E8), Color(0xFFA6A6A6)),
        colors = listOf(Color(0xFFBDBDBD)),
    ),
    light = Scheme(
        steps = listOf(Color(0xFFFAFAFA), Color(0xFFF0F0F0), Color(0xFFE6E6E6), Color(0xFFDCDCDC)),
        inks = listOf(Color(0xFF1F1F1F), Color(0xFF5E5E5E)),
        colors = listOf(Color(0xFF4A4A4A)),
    ),
)
