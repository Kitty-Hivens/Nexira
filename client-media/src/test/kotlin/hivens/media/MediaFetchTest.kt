package hivens.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The yt-dlp progress line -> [MediaFetch]. Pure, so the template the service
 * asks for is pinned without running the tool or reaching the network.
 */
class MediaFetchTest {

    private fun line(vararg fields: String) = "$YT_DLP_PROGRESS_MARKER ${fields.joinToString(" ")}"

    @Test
    fun `a full counter reads as a measured download`() {
        val fetch = parseYtDlpProgress(line("1048576", "4194304", "4194304"))
        assertEquals(MediaFetch.Downloading(1_048_576, 4_194_304), fetch)
        assertEquals(0.25f, fetch?.fraction)
    }

    @Test
    fun `an unknown total falls back to the estimate`() {
        // yt-dlp prints NA for a size the server did not give, and its estimate
        // arrives as a float.
        val fetch = parseYtDlpProgress(line("500000", "NA", "2000000.5"))
        assertEquals(MediaFetch.Downloading(500_000, 2_000_000), fetch)
    }

    @Test
    fun `no size at all leaves the job unmeasured`() {
        val fetch = parseYtDlpProgress(line("500000", "NA", "NA"))
        assertEquals(MediaFetch.Downloading(500_000, 0), fetch)
        assertNull(fetch?.fraction, "an unknown total must read as indeterminate, not as zero percent")
    }

    @Test
    fun `every other line the tool writes is ignored`() {
        assertNull(parseYtDlpProgress("[youtube] Extracting URL: https://example.invalid/watch"))
        assertNull(parseYtDlpProgress("[download] Destination: /tmp/video.mp4"))
        assertNull(parseYtDlpProgress(""))
    }

    @Test
    fun `a truncated counter is not a measurement`() {
        assertNull(parseYtDlpProgress(YT_DLP_PROGRESS_MARKER))
        assertNull(parseYtDlpProgress(line("NA", "NA", "NA")))
    }

    @Test
    fun `a phase with no size to measure reports none`() {
        assertNull(MediaFetch.Idle.fraction)
        assertNull(MediaFetch.Resolving.fraction)
        assertNull(MediaFetch.InstallingTool().fraction)
        assertEquals(0.5f, MediaFetch.InstallingTool(doneBytes = 5, totalBytes = 10).fraction)
    }

    @Test
    fun `a measure past its total is clamped`() {
        assertEquals(1f, MediaFetch.Downloading(120, 100).fraction)
    }
}
