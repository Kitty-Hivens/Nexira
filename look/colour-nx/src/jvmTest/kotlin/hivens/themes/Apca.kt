package hivens.themes

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

/**
 * APCA lightness contrast (0.0.98G-4g, the SAPC constants) of [text] drawn on [ground],
 * as Lc: positive for dark text on a light ground, negative for light text on a dark one.
 * Both colours are taken as opaque.
 */
internal fun apcaLc(text: Color, ground: Color): Double {
    val yText = screenLuminance(text)
    val yGround = screenLuminance(ground)
    if (abs(yGround - yText) < DELTA_Y_MIN) return 0.0
    return if (yGround > yText) {
        val sapc = (yGround.pow(NORM_BG) - yText.pow(NORM_TXT)) * SCALE
        if (sapc < LO_CLIP) 0.0 else (sapc - OFFSET) * 100.0
    } else {
        val sapc = (yGround.pow(REV_BG) - yText.pow(REV_TXT)) * SCALE
        if (sapc > -LO_CLIP) 0.0 else (sapc + OFFSET) * 100.0
    }
}

/** Estimated screen luminance with APCA's soft clamp near black. */
private fun screenLuminance(c: Color): Double {
    val y = R_CO * c.red.toDouble().pow(TRC) + G_CO * c.green.toDouble().pow(TRC) + B_CO * c.blue.toDouble().pow(TRC)
    return if (y > BLK_THRS) y else y + (BLK_THRS - y).pow(BLK_CLMP)
}

private const val TRC = 2.4
private const val R_CO = 0.2126729
private const val G_CO = 0.7151522
private const val B_CO = 0.0721750
private const val BLK_THRS = 0.022
private const val BLK_CLMP = 1.414
private const val NORM_BG = 0.56
private const val NORM_TXT = 0.57
private const val REV_TXT = 0.62
private const val REV_BG = 0.65
private const val SCALE = 1.14
private const val LO_CLIP = 0.1
private const val OFFSET = 0.027
private const val DELTA_Y_MIN = 0.0005
