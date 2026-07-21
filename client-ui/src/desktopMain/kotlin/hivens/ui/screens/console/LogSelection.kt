package hivens.ui.screens.console

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// A position in the displayed document: a filtered-line index plus a char offset
// within that line's text. Ordered top-to-bottom, then left-to-right.
internal data class DocPos(val line: Int, val offset: Int) : Comparable<DocPos> {
    override fun compareTo(other: DocPos): Int =
        if (line != other.line) line - other.line else offset - other.offset
}

/**
 * Selection model for the log canvas. Virtualization means off-screen lines aren't
 * composed, so Compose's own SelectionContainer can't span the buffer -- this owns
 * an anchor/focus in document coordinates instead. The canvas hit-tests pointer
 * positions to [DocPos] via the cached per-line layouts, draws the highlight per
 * visible line from [rangeOnLine], and copy assembles the text straight from the
 * model ([copyText]) so a whole-buffer Ctrl+A copies even the lines never drawn.
 */
internal class LogSelection {
    var anchor by mutableStateOf<DocPos?>(null)
        private set
    var focus by mutableStateOf<DocPos?>(null)
        private set

    val active: Boolean get() { val a = anchor; val f = focus; return a != null && f != null && a != f }

    val start: DocPos? get() { val a = anchor ?: return null; val f = focus ?: return null; return minOf(a, f) }
    val end: DocPos? get() { val a = anchor ?: return null; val f = focus ?: return null; return maxOf(a, f) }

    fun setCaret(pos: DocPos) { anchor = pos; focus = pos }
    fun beginAt(pos: DocPos) { anchor = pos; focus = pos }
    fun extendTo(pos: DocPos) { focus = pos }
    fun collapse() { anchor = null; focus = null }

    fun select(from: DocPos, to: DocPos) { anchor = from; focus = to }

    fun selectAll(lines: LineModels) {
        if (lines.lines.isEmpty()) { collapse(); return }
        anchor = DocPos(0, 0)
        val last = lines.lines.lastIndex
        focus = DocPos(last, lines.lines[last].text.length)
    }

    /** Char range of the selection intersected with line [lineIndex] (length [lineLen]),
     *  or null when that line is outside the selection. Drives the per-line highlight. */
    fun rangeOnLine(lineIndex: Int, lineLen: Int): IntRange? {
        val s = start ?: return null
        val e = end ?: return null
        if (lineIndex < s.line || lineIndex > e.line) return null
        val from = if (lineIndex == s.line) s.offset else 0
        val to = if (lineIndex == e.line) e.offset else lineLen
        val clampedFrom = from.coerceIn(0, lineLen)
        val clampedTo = to.coerceIn(clampedFrom, lineLen)
        if (clampedFrom >= clampedTo) return null
        return clampedFrom until clampedTo
    }

    /** Assemble the selected text from the model, line by line. Uses the displayed
     *  text (timestamp prefix included), matching the old field-copy behaviour. */
    fun copyText(lines: LineModels): String {
        val s = start ?: return ""
        val e = end ?: return ""
        if (s == e) return ""
        val sb = StringBuilder()
        for (i in s.line..e.line) {
            if (i !in lines.lines.indices) continue
            val text = lines.lines[i].text
            val from = (if (i == s.line) s.offset else 0).coerceIn(0, text.length)
            val to = (if (i == e.line) e.offset else text.length).coerceIn(from, text.length)
            sb.append(text, from, to)
            if (i != e.line) sb.append('\n')
        }
        return sb.toString()
    }
}
