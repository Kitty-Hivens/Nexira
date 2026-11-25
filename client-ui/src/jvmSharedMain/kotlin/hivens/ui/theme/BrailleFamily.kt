package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.dejavu_sans
import org.jetbrains.compose.resources.Font

/**
 * DejaVu Sans, bundled solely because it carries the full Braille block (U+2800)
 * at a uniform cell width. JetBrains Mono has none, so Braille art would tofu or
 * fall back to a random system font. Used only for the decorative Braille console
 * filler: all braille cells share one advance, so picture art stays aligned.
 */
@Composable
fun nexiraBrailleFamily(): FontFamily = FontFamily(Font(Res.font.dejavu_sans))
