package hivens.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fetch is killed on cancel and on timeout, so whether a stopped download can
 * leave a truncated file under the finished name is decided entirely by one
 * argument.
 */
class YtDlpDownloadArgsTest {

    private val args = YtDlpService.downloadArgs(
        ytdlp = "/tmp/yt-dlp",
        outTemplate = "/cache/abc123.%(ext)s",
        pageUrl = "https://example.test/watch?v=1",
    )

    @Test
    fun `the part-file is never disabled`() {
        assertFalse(
            args.contains("--no-part"),
            "with --no-part yt-dlp writes straight to the finished name, and killing it mid-download " +
                "publishes a short file that the cache lookup then serves as complete forever: $args",
        )
    }

    @Test
    fun `the output template and page url are passed through`() {
        assertEquals("/cache/abc123.%(ext)s", args[args.indexOf("-o") + 1])
        assertEquals("https://example.test/watch?v=1", args.last())
    }

    @Test
    fun `progress is reported line-wise in a parseable template`() {
        // The default rewrites one line with a carriage return, which never reaches
        // a line reader -- the caller would show nothing for a multi-minute fetch.
        assertTrue(args.contains("--newline"))
        val template = args[args.indexOf("--progress-template") + 1]
        assertTrue(template.contains(YT_DLP_PROGRESS_MARKER), "the parser keys on the marker: $template")
        assertTrue(template.contains("%(progress.downloaded_bytes)s"))
    }
}
