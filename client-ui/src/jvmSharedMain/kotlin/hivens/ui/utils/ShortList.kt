package hivens.ui.utils

/**
 * The first few names, then a count for the rest.
 *
 * For a one-line status strip that has to report a set: a pack can fail on dozens
 * of entries at once, and the whole list there renders as an ellipsis and says
 * nothing at all. A few names are enough to recognise which of the pack's own
 * files these are, which is what the reader does something about. Whatever is
 * cut is still in the log and in the action ring.
 */
internal fun shortNameList(names: List<String>, shown: Int = 3): String {
    val head = names.take(shown)
    val rest = names.size - head.size
    return if (rest <= 0) head.joinToString() else "${head.joinToString()} +$rest"
}
