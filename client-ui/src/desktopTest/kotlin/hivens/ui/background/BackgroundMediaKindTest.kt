package hivens.ui.background

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Routing of a background file to the still path or to the player. The decoders
 * answer, not the file name: Skia says how many frames it finds, and what it
 * will not open is the player's.
 */
class BackgroundMediaKindTest {

    @Test
    fun singleFrameImageIsStatic() {
        withFixture(ONE_PIXEL_PNG, ".png") { assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(it)) }
        withFixture(STILL_GIF, ".gif") { assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(it)) }
    }

    @Test
    fun animatedImageIsTimeBased() {
        withFixture(ANIMATED_GIF, ".gif") { assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(it)) }
    }

    /**
     * The container the still path cannot read goes to the player rather than
     * to a decode that will fail. This is the case the old extension list got
     * wrong: anything it did not name fell through to the image path.
     */
    @Test
    fun whatSkiaWillNotOpenGoesToThePlayer() {
        for (suffix in listOf(".mp4", ".avi", ".mkv", ".xyz", "")) {
            withFixture(NOT_AN_IMAGE, suffix) {
                assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(it), "suffix '$suffix'")
            }
        }
    }

    /** The extension is not consulted at all, so a still under a video name is still a still. */
    @Test
    fun theNameDoesNotDecide() {
        withFixture(ONE_PIXEL_PNG, ".mp4") { assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(it)) }
        withFixture(ANIMATED_GIF, ".jpg") { assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(it)) }
    }

    /**
     * Nothing to classify: the still path reports the missing file, where every
     * other absent-source case is already reported.
     */
    @Test
    fun missingFileIsStatic() {
        assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/ghost.png")))
        assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/ghost.mp4")))
    }

    private fun withFixture(base64: String, suffix: String, assert: (File) -> Unit) {
        val file = Files.createTempFile("bgkind", suffix).toFile()
        try {
            file.writeBytes(Base64.getDecoder().decode(base64))
            assert(file)
        } finally {
            file.delete()
        }
    }

    private companion object {
        /** 1x1, one frame. */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

        /** 1x1, one frame, so an animated format holding a still. */
        const val STILL_GIF = "R0lGODdhAQABAIEAAAAAAP///wAAAAAAACwAAAAAAQABAAAIBAABBAQAOw=="

        /** 1x1, two frames. */
        const val ANIMATED_GIF =
            "R0lGODlhAQABAIEAAAAAAP///wAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAAAQABAAAIBAABBAQAIf" +
                "kEAQoAAQAsAAAAAAEAAQCB////AAAAAAAAAAAACAQAAQQEADs="

        /** Bytes no image codec claims. */
        const val NOT_AN_IMAGE = "bm90IGFuIGltYWdlLCBub3QgYSB2aWRlbywganVzdCBieXRlcw=="
    }
}
