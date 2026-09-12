package hivens.ui.text

/**
 * Does [text] hold anything the bundled CJK face draws and the UI face does not?
 *
 * Roboto Flex ships subset to Latin, Cyrillic and Greek, so a string with
 * anything else in it has to be drawn by the bundled CJK face instead. Whichever
 * family draws a string draws all of it, because a family cannot borrow coverage
 * from a sibling: Compose picks one face by weight and style and never by what it
 * contains, so a character the chosen face lacks leaves the bundle and lands on
 * whatever the host has, or on nothing.
 *
 * The question is deliberately NOT "does the UI face cover every character". That
 * is the question one step removed, and it answers yes-switch for characters no
 * bundled face carries: a byte-order mark at the head of a tag read off a file, a
 * narrow no-break space out of a tagger, a word joiner out of pasted text, the
 * modifier colon a tagger substitutes for the one a filename cannot hold. None of
 * those draws anything in either face, and none of them is a reason to move a
 * whole string onto a 7.8 MB face that will not draw them either. Asking whether
 * switching HELPS answers no for all of them and yes for a non-breaking hyphen,
 * which the CJK face really does carry.
 *
 * The question is asked of the string and not of the locale, because a Japanese
 * track title turns up in a Russian interface all the time.
 *
 * Answered from [CJK_ONLY_RUNS], which tools/fonts/regenerate.py reads out of the
 * two shipped binaries. Nothing here is transcribed: the first attempt at writing
 * ranges by hand claimed archaic Greek, Ukrainian Ye, four dashes and half the
 * currency block, none of which the face carries, and every one would have been a
 * box on screen.
 */
fun needsCjkFace(text: String): Boolean = text.codePoints().anyMatch(::inCjkOnly)

/** Binary search over the flattened start/end pairs. */
private fun inCjkOnly(codePoint: Int): Boolean {
    var lo = 0
    var hi = CJK_ONLY_RUNS.size / 2 - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            codePoint < CJK_ONLY_RUNS[mid * 2] -> hi = mid - 1
            codePoint > CJK_ONLY_RUNS[mid * 2 + 1] -> lo = mid + 1
            else -> return true
        }
    }
    return false
}
