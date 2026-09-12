package hivens.ui.theme

import org.jetbrains.skia.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a font file maps, read out of its own `cmap` table.
 *
 * Deliberately not [Typeface.getUTF32Glyph]. That asks the platform's font
 * manager, and the three platforms do not answer alike: CoreText reports the UI
 * face as drawing a tab, a byte order mark and a word joiner, none of which the
 * file maps at all. A coverage check built on it therefore failed on macOS only,
 * about a table that was correct on every platform, which is the worst shape a
 * failure can take: it accuses the thing that is right.
 *
 * The table under test is generated from these same bytes by
 * tools/fonts/regenerate.py, so the bytes are what it has to be compared
 * against. The selection rule mirrors fontTools `getBestCmap`, which the
 * generator uses: one subtable, the first that exists in its order of
 * preference, rather than the union of all of them.
 */
internal fun Typeface.mappedCodepoints(): Set<Int> {
    val data = getTableData("cmap") ?: error("$familyName carries no cmap table")
    val bytes = try { data.bytes } finally { data.close() }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

    val subtables = buffer.u16(2)
    val found = HashMap<Pair<Int, Int>, Int>()
    for (i in 0 until subtables) {
        val record = 4 + i * 8
        found.putIfAbsent(buffer.u16(record) to buffer.u16(record + 2), buffer.getInt(record + 4))
    }
    val offset = CMAP_PREFERENCE.firstNotNullOfOrNull { found[it] }
        ?: error("$familyName has no unicode cmap subtable")

    val covered = HashSet<Int>()
    when (val format = buffer.u16(offset)) {
        4 -> buffer.readFormat4(offset, covered)
        12 -> buffer.readFormat12(offset, covered)
        else -> error("$familyName uses cmap format $format, which this reader does not handle")
    }
    return covered
}

/** fontTools' own order, so this reader and the generator pick the same subtable. */
private val CMAP_PREFERENCE = listOf(
    3 to 10, 0 to 6, 0 to 4, 3 to 1, 0 to 3, 0 to 2, 0 to 1, 0 to 0,
)

private fun ByteBuffer.u16(at: Int): Int = getShort(at).toInt() and 0xFFFF

private fun ByteBuffer.readFormat4(offset: Int, out: MutableSet<Int>) {
    val segmentBytes = u16(offset + 6)
    val segments = segmentBytes / 2
    val ends = offset + 14
    // The two bytes between the end codes and the start codes are a reserved pad.
    val starts = ends + segmentBytes + 2
    val deltas = starts + segmentBytes
    val rangeOffsets = deltas + segmentBytes

    for (s in 0 until segments) {
        val end = u16(ends + s * 2)
        val start = u16(starts + s * 2)
        if (start > end || start == 0xFFFF) continue
        val delta = getShort(deltas + s * 2).toInt()
        val rangeOffset = u16(rangeOffsets + s * 2)
        for (cp in start..end) {
            val glyph = if (rangeOffset == 0) {
                (cp + delta) and 0xFFFF
            } else {
                val at = rangeOffsets + s * 2 + rangeOffset + (cp - start) * 2
                if (at + 2 > limit()) continue
                val raw = u16(at)
                if (raw == 0) 0 else (raw + delta) and 0xFFFF
            }
            // A codepoint the subtable maps to .notdef is a codepoint the face
            // does not carry, which is how fontTools reads it too.
            if (glyph != 0) out += cp
        }
    }
}

private fun ByteBuffer.readFormat12(offset: Int, out: MutableSet<Int>) {
    val groups = getInt(offset + 12)
    for (g in 0 until groups) {
        val at = offset + 16 + g * 12
        val first = getInt(at)
        val last = getInt(at + 4)
        val firstGlyph = getInt(at + 8)
        for (cp in first..last) {
            if (firstGlyph + (cp - first) != 0) out += cp
        }
    }
}
