package hivens.launcher.platform

import java.awt.Dimension

/**
 * Clamps a design-intent minimum-size to a safe fraction of the user's
 * native screen so a small laptop (e.g. 1366x768) does not end up with
 * a minimum the user cannot actually shrink the window down to.
 *
 * The launcher's 2-column Library + sidebar layout breaks below roughly
 * 960dp width / 600dp height; that's the design intent the caller is
 * expected to pass in -- already density-converted to raw pixels via
 * the Compose LocalDensity. If the
 * user's screen is smaller than the intent in either dimension, the
 * intent gets clamped to [maxScreenFraction] of the available screen
 * so the user retains headroom to drag the window edges.
 *
 * AWT's `Window.setMinimumSize` is a hint -- floating WMs (KDE, GNOME,
 * Win11, macOS Quartz) respect it; tiling WMs (Sway, Hyprland) and
 * some QML widget compositors typically override it entirely, in which
 * case this function still runs cheaply and harmlessly.
 */
fun computeSafeWindowMinSize(
    designWidthPx: Int,
    designHeightPx: Int,
    screen: Dimension,
    maxScreenFraction: Float = 0.9f,
): Dimension = Dimension(
    minOf(designWidthPx,  (screen.width  * maxScreenFraction).toInt()),
    minOf(designHeightPx, (screen.height * maxScreenFraction).toInt()),
)
