package hivens.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The report is only worth printing if its parsers name the right things. The
 * class archive is the bucket that decides whether an idle launcher looks fat or
 * reasonable, and it is a plain file mapping that reads like any other library
 * until its suffix is checked. Everything anonymous stays in one bucket on
 * purpose: an earlier version told the Java heap apart by address and got it
 * wrong the moment a smaller ceiling moved the heap below the window it assumed.
 */
class MemoryReportTest {

    private val smaps = """
        68cc00000-690c00000 rw-p 00000000 00:00 0 
        Size:             266240 kB
        Rss:              172544 kB
        Pss:              172544 kB
        1dc9d000-245a9000 r--s 00000000 fd:02 1234    /home/user/.local/share/nexira/app-2.4.0-abc.jsa
        Size:             106448 kB
        Rss:              106448 kB
        Pss:              106448 kB
        55ff20197000-55ff29b8d000 rw-p 00000000 00:00 0                          [heap]
        Size:             159192 kB
        Rss:              136192 kB
        Pss:              136192 kB
        7f2d24e1b000-7f2d26e1b000 r-xp 00000000 fd:02 5678   /usr/lib/libgallium-26.2.1-arch3.1.so
        Size:              32768 kB
        Rss:               19456 kB
        Pss:                2048 kB
        7f2d5aef3000-7f2d5bef3000 r-xp 00000000 fd:02 9012   /tmp/.mount_Nexira/usr/lib/server/libjvm.so
        Size:              16384 kB
        Rss:               13824 kB
        Pss:               13824 kB
        7f2d14cf2000-7f2d16cf2000 rw-p 00000000 00:00 0 
        Size:              32768 kB
        Rss:               17408 kB
        Pss:               17408 kB
    """.trimIndent()

    @Test
    fun `anonymous mappings stay in one bucket instead of being guessed at`() {
        val groups = MemoryReport.groupSmaps(smaps).associate { it.name to it.usedMb }
        assertEquals(185, groups[MemoryReport.ANONYMOUS], "both anonymous regions, high and low")
        assertTrue(groups.keys.none { it.contains("java heap") }, "the kernel cannot name the heap for us")
    }

    @Test
    fun `the class archive is its own bucket and not just another file`() {
        val groups = MemoryReport.groupSmaps(smaps).associate { it.name to it.usedMb }
        assertEquals(103, groups["class archive"])
        assertEquals(133, groups[MemoryReport.MALLOC_ARENA])
        assertEquals(19, groups["gpu stack"])
        assertEquals(13, groups["jvm library"])
    }

    @Test
    fun `resident and proportional totals come from the same text`() {
        assertEquals(454, MemoryReport.sumSmapsField(smaps, "Rss:"))
        assertEquals(437, MemoryReport.sumSmapsField(smaps, "Pss:"))
    }

    @Test
    fun `native memory categories are read as committed, which is what was taken`() {
        val nmt = """
            Native Memory Tracking:

            Total: reserved=7835043KB, committed=494291KB
                   malloc: 36519KB #122136, peak=101470KB #65367

            -                 Java Heap (reserved=6082560KB, committed=270336KB)
                                        (mmap: reserved=6082560KB, committed=270336KB, at peak)

            -                        GC (reserved=184931KB, committed=60059KB)

            -        Shared class space (reserved=131072KB, committed=120484KB, readonly=0KB)

            -                 Safepoint (reserved=8KB, committed=8KB)
        """.trimIndent()
        val categories = MemoryReport.parseNativeMemorySummary(nmt).associate { it.name to it.committedMb }
        assertEquals(264, categories["java heap"])
        assertEquals(58, categories["gc"])
        assertEquals(117, categories["shared class space"])
        assertTrue("safepoint" !in categories, "a category under a megabyte is noise in a console report")
    }

    @Test
    fun `a tracker that was never enabled yields no categories rather than a row of garbage`() {
        val refusal = "Native memory tracking is not enabled"
        assertEquals(emptyList(), MemoryReport.parseNativeMemorySummary(refusal))
    }

    @Test
    fun `durations read as durations`() {
        assertEquals("42s", MemoryReport.formatDuration(42_000))
        assertEquals("4m 12s", MemoryReport.formatDuration(252_000))
        assertEquals("2h 5m", MemoryReport.formatDuration(7_500_000))
    }

    @Test
    fun `the summary names the host, the heap and the fixed cost`() {
        val snapshot = MemoryReport.collect()
        val text = MemoryReport.summaryLines(snapshot).joinToString("\n")
        assertTrue("heap" in text, text)
        assertTrue("threads" in text, text)
        assertTrue("classes" in text, text)
        assertNotNull(snapshot.collectors.firstOrNull(), "a running JVM always has a collector")
    }

    @Test
    fun `the full report explains an absent tracker instead of leaving a hole`() {
        val snapshot = MemoryReport.collect().copy(nativeCategories = emptyList())
        val text = MemoryReport.fullLines(snapshot).joinToString("\n")
        assertTrue("NativeMemoryTracking" in text, text)
    }
}
