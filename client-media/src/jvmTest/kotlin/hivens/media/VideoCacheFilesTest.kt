package hivens.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoCacheFilesTest {

    private val hash = "ab12"

    @Test
    fun `only the finished file counts as a yt-dlp result`() {
        assertTrue(VideoCacheFiles.isFinishedFetch("$hash.mp4", hash))
        assertTrue(VideoCacheFiles.isFinishedFetch("$hash.webm", hash))

        // What a fragmented download leaves on the way, each non-empty and none a video.
        for (leftover in listOf("$hash.mp4.part", "$hash.mp4.ytdl", "$hash.mp4.part-Frag3", "$hash.mp4.part-Frag3.part", "$hash.mp4.temp")) {
            assertFalse(VideoCacheFiles.isFinishedFetch(leftover, hash), "$leftover was served as the video")
        }
        assertFalse(VideoCacheFiles.isFinishedFetch("cd34.mp4", hash), "another URL's file")
    }

    @Test
    fun `every writer's partial files are kept out of eviction`() {
        assertTrue(VideoCacheFiles.isPartial("x.mp4.part"))
        assertTrue(VideoCacheFiles.isPartial("x.mp4.part.state"), "the journal a paused transfer resumes from")
        assertTrue(VideoCacheFiles.isPartial("x.mp4.ytdl"))
        assertTrue(VideoCacheFiles.isPartial("x.mp4.part-Frag12"))
        assertFalse(VideoCacheFiles.isPartial("x.mp4"))
    }
}
