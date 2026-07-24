package hivens.ui.utils

/**
 * A run of styled plain text produced by [parseAnsi]: `[start, end)` offsets into
 * the stripped text, an optional foreground colour (`#rrggbb`), and a bold flag.
 * Only runs that actually carry a colour or bold are emitted.
 */
class AnsiRun(
    val start: Int,
    val end: Int,
    val colorHex: String?,
    val bold: Boolean,
)

/** [text] is the input with every escape sequence removed; [runs] carry the SGR
 *  foreground colour / bold state over that text. */
class AnsiParse(val text: String, val runs: List<AnsiRun>)

private const val ESC = '\u001B'
private const val BEL = '\u0007'

// Standard + bright 16-colour foreground palette (xterm-ish), tuned to read on a
// dark console. Index 0-7 = SGR 30-37, 8-15 = SGR 90-97.
private val ANSI_16 = arrayOf(
    "#000000", "#cd0000", "#00cd00", "#cdcd00", "#3b3bff", "#cd00cd", "#00cdcd", "#e5e5e5",
    "#7f7f7f", "#ff5555", "#55ff55", "#ffff55", "#5c5cff", "#ff55ff", "#55ffff", "#ffffff",
)

/** xterm 256-colour index to `#rrggbb`: 0-15 palette, 16-231 the 6x6x6 cube, 232-255 greys. */
private fun xterm256(n: Int): String = when {
    n < 16 -> ANSI_16[n.coerceIn(0, 15)]
    n in 16..231 -> {
        val i = n - 16
        fun c(v: Int) = if (v == 0) 0 else 55 + v * 40
        "#%02x%02x%02x".format(c(i / 36), c((i % 36) / 6), c(i % 6))
    }
    else -> (8 + (n.coerceIn(232, 255) - 232) * 10).let { "#%02x%02x%02x".format(it, it, it) }
}

/**
 * Strips ANSI escape sequences from [raw] and turns SGR foreground-colour / bold
 * state into a list of [AnsiRun] over the resulting plain text. Handles the 16
 * base colours (30-37 / 90-97), 256-colour (`38;5;n`), truecolour (`38;2;r;g;b`),
 * bold (`1` / `22`), and reset (`0` / `39`); background codes and other SGR
 * attributes are ignored. Non-SGR CSI (cursor moves) and OSC (window title)
 * sequences are dropped without affecting the run style. The common no-escape
 * line takes a fast path.
 */
fun parseAnsi(raw: String): AnsiParse {
    if (raw.indexOf(ESC) < 0) return AnsiParse(raw, emptyList())

    val out = StringBuilder(raw.length)
    val runs = ArrayList<AnsiRun>()
    var color: String? = null
    var bold = false
    var runStart = 0
    var i = 0

    fun closeRun() {
        if (out.length > runStart && (color != null || bold)) {
            runs.add(AnsiRun(runStart, out.length, color, bold))
        }
        runStart = out.length
    }

    while (i < raw.length) {
        val c = raw[i]
        if (c != ESC) {
            out.append(c)
            i++
            continue
        }
        if (i + 1 >= raw.length) break
        when (raw[i + 1]) {
            '[' -> {
                var j = i + 2
                val paramStart = j
                while (j < raw.length && raw[j] in '0'..'?') j++   // parameter bytes 0x30-0x3F
                val paramEnd = j
                while (j < raw.length && raw[j] in ' '..'/') j++   // intermediate bytes 0x20-0x2F
                val finalByte = if (j < raw.length) raw[j] else ' '
                if (finalByte == 'm') {
                    closeRun()
                    val (newColor, newBold) = applySgr(raw.substring(paramStart, paramEnd), color, bold)
                    color = newColor
                    bold = newBold
                }
                i = if (j < raw.length) j + 1 else raw.length
            }
            ']' -> {
                var j = i + 2
                while (j < raw.length && raw[j] != BEL && !(raw[j] == ESC && j + 1 < raw.length && raw[j + 1] == '\\')) j++
                i = if (j < raw.length && raw[j] == ESC) j + 2 else j + 1
            }
            else -> i += 2
        }
    }
    closeRun()
    return AnsiParse(out.toString(), runs)
}

/** Folds an SGR parameter string onto the current (colour, bold) state, returning
 *  the new state. An empty parameter list means reset (SGR `0`). */
private fun applySgr(params: String, curColor: String?, curBold: Boolean): Pair<String?, Boolean> {
    var color = curColor
    var bold = curBold
    val parts = if (params.isEmpty()) listOf("0") else params.split(';')
    var k = 0
    while (k < parts.size) {
        when (val p = parts[k].toIntOrNull() ?: 0) {
            0 -> { color = null; bold = false }
            1 -> bold = true
            22 -> bold = false
            39 -> color = null
            in 30..37 -> color = ANSI_16[p - 30]
            in 90..97 -> color = ANSI_16[p - 90 + 8]
            38 -> when (parts.getOrNull(k + 1)?.toIntOrNull()) {
                5 -> { parts.getOrNull(k + 2)?.toIntOrNull()?.let { color = xterm256(it) }; k += 2 }
                2 -> {
                    val r = parts.getOrNull(k + 2)?.toIntOrNull()
                    val g = parts.getOrNull(k + 3)?.toIntOrNull()
                    val b = parts.getOrNull(k + 4)?.toIntOrNull()
                    if (r != null && g != null && b != null) {
                        color = "#%02x%02x%02x".format(r and 0xFF, g and 0xFF, b and 0xFF)
                    }
                    k += 4
                }
            }
        }
        k++
    }
    return color to bold
}
