package hivens.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUrlTest {

    @Test
    fun videoExtensionsAreVideo() {
        for (ext in listOf("mp4", "m4v", "mov", "webm", "mkv", "ogv")) {
            assertTrue(isVideoUrl("https://cdn.example.com/clip.$ext"), ext)
        }
    }

    @Test
    fun imagesAndAnimatedImagesAreNotVideo() {
        for (u in listOf("a.png", "a.jpg", "a.jpeg", "a.bmp", "a.gif", "a.webp", "a.apng")) {
            assertFalse(isVideoUrl("https://cdn.example.com/$u"), u)
        }
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertTrue(isVideoUrl("https://cdn.example.com/CLIP.MP4"))
        assertTrue(isVideoUrl("https://cdn.example.com/Clip.WebM"))
    }

    @Test
    fun queryAndFragmentAreStripped() {
        assertTrue(isVideoUrl("https://cdn.example.com/clip.mp4?token=abc&exp=123"))
        assertTrue(isVideoUrl("https://cdn.example.com/clip.webm#t=10"))
    }

    @Test
    fun noFileExtensionIsNotVideo() {
        assertFalse(isVideoUrl("https://cdn.example.com/clip"))
        assertFalse(isVideoUrl("https://x/page?file=a.mp4"))
    }

    @Test
    fun videoServiceHostsAreDetected() {
        for (u in listOf(
            "https://www.youtube.com/watch?v=abc",
            "https://youtu.be/abc",
            "https://m.youtube.com/watch?v=abc",
            "https://music.youtube.com/watch?v=abc",
            "https://vimeo.com/12345",
            "https://player.vimeo.com/video/12345",
            "https://www.dailymotion.com/video/x1",
        )) {
            assertTrue(isVideoServiceUrl(u), u)
        }
    }

    @Test
    fun nonServiceUrlsAreNotService() {
        for (u in listOf(
            "https://cdn.example.com/clip.mp4",
            "https://notyoutube.com/watch",
            "https://example.com/youtube.com",
            "not a url",
        )) {
            assertFalse(isVideoServiceUrl(u), u)
        }
    }

    @Test
    fun playableCoversFilesAndServices() {
        assertTrue(isPlayableVideoUrl("https://cdn.example.com/clip.webm"))
        assertTrue(isPlayableVideoUrl("https://youtu.be/abc"))
        assertFalse(isPlayableVideoUrl("https://example.com/page.html"))
    }
}
