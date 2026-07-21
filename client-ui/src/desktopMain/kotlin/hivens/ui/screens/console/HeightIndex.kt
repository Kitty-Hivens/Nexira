package hivens.ui.screens.console

// Maps between a pixel offset in the scrolled content and the log line at that
// offset, both directions. The log canvas draws only the lines a viewport covers,
// so it must answer "which line starts at pixel Y" and "what is the top pixel of
// line N" without laying out the whole buffer.
//
// Two shapes:
//  - [ConstantHeightIndex] -- no-wrap: every line is one row, height fixed. Both
//    queries are a multiply/divide, O(1).
//  - [FenwickHeightIndex]  -- wrap: a line spans a variable number of rows, known
//    only once measured. A Fenwick (binary-indexed) tree over per-line heights
//    gives prefix-sum top-of-line and an offset->line lookup in O(log n), and a
//    single-line height correction in O(log n) as lines resolve from estimate to
//    measured.
interface HeightIndex {
    val count: Int
    val totalHeight: Int

    /** 0-based line whose vertical extent contains content-pixel [y]; clamped to bounds. */
    fun lineAtOffset(y: Int): Int

    /** Top content-pixel of line [index] (== sum of all earlier line heights). */
    fun topOfLine(index: Int): Int

    /** Record a line's measured height; a no-op where height is fixed. */
    fun setHeight(index: Int, heightPx: Int)

    /** Re-seed for a new line count, every line starting at [estimatePerLine]. */
    fun reset(count: Int, estimatePerLine: Int)
}

/**
 * No-wrap index: uniform [lineHeightPx]. [estimatePerLine] / [setHeight] are ignored --
 * every row is the same height, so there is nothing to correct. On a font-size change
 * the canvas builds a fresh instance rather than mutating this one.
 */
class ConstantHeightIndex(private val lineHeightPx: Int) : HeightIndex {
    override var count: Int = 0
        private set

    override val totalHeight: Int get() = count * lineHeightPx

    override fun lineAtOffset(y: Int): Int {
        if (count == 0 || lineHeightPx <= 0) return 0
        return (y / lineHeightPx).coerceIn(0, count - 1)
    }

    override fun topOfLine(index: Int): Int = index.coerceIn(0, count) * lineHeightPx

    override fun setHeight(index: Int, heightPx: Int) { /* fixed height: nothing to store */ }

    override fun reset(count: Int, estimatePerLine: Int) {
        this.count = count.coerceAtLeast(0)
    }
}

/**
 * Wrap index: per-line heights in a Fenwick tree. Unmeasured lines carry the
 * reset estimate; as each scrolls into view and is measured, [setHeight] corrects
 * it in O(log n) and the totals / lookups stay exact for the resolved region and
 * approximate (by the estimate) for the not-yet-seen tail. Sums use Long so a large
 * buffer of tall wrapped lines cannot overflow the running total.
 */
class FenwickHeightIndex(initialCount: Int = 0, estimatePerLine: Int = 0) : HeightIndex {
    override var count: Int = 0
        private set

    // 0-based per-line height; the authoritative value setHeight diffs against.
    private var heights: IntArray = IntArray(0)
    // 1-based Fenwick tree of the same heights (tree[0] unused).
    private var tree: LongArray = LongArray(1)

    init { reset(initialCount, estimatePerLine) }

    override val totalHeight: Int get() = prefix(count).toInt()

    override fun reset(count: Int, estimatePerLine: Int) {
        val n = count.coerceAtLeast(0)
        val est = estimatePerLine.coerceAtLeast(0)
        this.count = n
        heights = IntArray(n) { est }
        // O(n) tree build: seed each node with its own value, then push into parent.
        tree = LongArray(n + 1)
        for (i in 1..n) {
            tree[i] += est
            val parent = i + (i and -i)
            if (parent <= n) tree[parent] += tree[i]
        }
    }

    override fun setHeight(index: Int, heightPx: Int) {
        if (index !in 0 until count) return
        val h = heightPx.coerceAtLeast(0)
        val delta = h - heights[index]
        if (delta == 0) return
        heights[index] = h
        var x = index + 1
        while (x <= count) {
            tree[x] += delta
            x += x and -x
        }
    }

    override fun topOfLine(index: Int): Int = prefix(index.coerceIn(0, count)).toInt()

    override fun lineAtOffset(y: Int): Int {
        if (count == 0) return 0
        if (y <= 0) return 0
        // Fenwick binary lifting: walk the tree high-bit first, accumulating the
        // largest prefix whose sum stays <= y. `pos` ends as the count of leading
        // lines that fit under y, which is exactly the 0-based line containing y.
        var pos = 0
        var remaining = y.toLong()
        var step = Integer.highestOneBit(count)
        while (step > 0) {
            val next = pos + step
            if (next <= count && tree[next] <= remaining) {
                remaining -= tree[next]
                pos = next
            }
            step = step shr 1
        }
        return pos.coerceIn(0, count - 1)
    }

    // Sum of the first [k] line heights (heights[0 until k]).
    private fun prefix(k: Int): Long {
        var r = 0L
        var x = k.coerceIn(0, count)
        while (x > 0) {
            r += tree[x]
            x -= x and -x
        }
        return r
    }
}
