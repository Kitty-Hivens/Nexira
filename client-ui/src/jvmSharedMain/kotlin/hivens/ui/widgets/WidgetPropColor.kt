package hivens.ui.widgets

import androidx.compose.ui.graphics.Color

// Hex string -> Compose Color for @PropColor widget props. "" / blank /
// malformed -> null so the caller falls back to a theme colour. Accepts
// #RRGGBB and #AARRGGBB, with or without the leading '#'.
fun String.toWidgetColorOrNull(): Color? {
    val clean = trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    val full = if (clean.length == 6) "FF$clean" else clean
    return runCatching { Color(full.toLong(16)) }.getOrNull()
}
