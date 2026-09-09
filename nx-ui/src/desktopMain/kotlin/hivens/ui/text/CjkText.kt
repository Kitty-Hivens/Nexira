package hivens.ui.text

/**
 * Does the bundled UI face cover every character of [text]?
 *
 * Roboto Flex ships subset to Latin, Cyrillic and Greek, so a string with
 * anything else in it has to be drawn by the bundled CJK face instead, which
 * carries those scripts plus the CJK ones and the symbol blocks. Whichever
 * family draws a string draws all of it, because a family cannot borrow coverage
 * from a sibling: Compose picks one face by weight and style and never by what
 * it contains, so a character the chosen face lacks leaves the bundle and lands
 * on whatever the host has, or on nothing.
 *
 * The question is asked of the string and not of the locale on purpose. A
 * Japanese track title turns up in a Russian interface all the time, and a title
 * ending in a white star is not in any language at all.
 *
 * Answered from [UI_FACE_RUNS], which tools/fonts/regenerate.py reads out of the
 * face itself. Nothing here is transcribed, because the first attempt at writing
 * these ranges by hand claimed archaic Greek, Ukrainian Ѐ, four dashes and half
 * the currency block, none of which the face actually carries, and every one of
 * those would have been a box on screen.
 */
fun uiFaceCovers(text: String): Boolean = text.codePoints().allMatch(::inUiFace)

/** Binary search over the flattened start/end pairs. */
private fun inUiFace(codePoint: Int): Boolean {
    var lo = 0
    var hi = UI_FACE_RUNS.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            codePoint < UI_FACE_RUNS[mid * 2] -> hi = mid - 1
            codePoint > UI_FACE_RUNS[mid * 2 + 1] -> lo = mid + 1
            else -> return true
        }
    }
    return false
}
