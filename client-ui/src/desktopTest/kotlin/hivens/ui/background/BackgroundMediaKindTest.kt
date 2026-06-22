package hivens.ui.background

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Routing of a background file to the still path or the player. Unambiguous
 * extensions answer without I/O; png/webp are probed for frame count.
 */
class BackgroundMediaKindTest {

    @Test
    fun videoExtensionsAreTimeBased() {
        for (ext in listOf("mp4", "m4v", "mov", "webm", "mkv", "ogv")) {
            assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(File("/nope/clip.$ext")), ext)
        }
    }

    @Test
    fun gifIsTimeBasedByExtension() {
        assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(File("/nope/loop.gif")))
    }

    @Test
    fun stillExtensionsAreStatic() {
        for (ext in listOf("jpg", "jpeg", "bmp")) {
            assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/pic.$ext")), ext)
        }
    }

    @Test
    fun unknownExtensionFallsBackToStatic() {
        assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/file.xyz")))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(File("/nope/CLIP.MP4")))
        assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/PIC.JPG")))
    }

    @Test
    fun singleFramePngIsStaticViaProbe() {
        val png = writeTempPng()
        try {
            assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(png))
        } finally {
            png.delete()
        }
    }

    @Test
    fun missingProbeFileIsStatic() {
        assertEquals(BackgroundMediaKind.Static, backgroundMediaKind(File("/nope/ghost.png")))
    }

    // A valid 1x1 PNG -- the probe must read it as a single frame (still),
    // never spin up the player on a plain image whose extension needs probing.
    private fun writeTempPng(): File {
        val bytes = Base64.getDecoder().decode(ONE_PIXEL_PNG)
        val file = Files.createTempFile("bgkind", ".png").toFile()
        file.writeBytes(bytes)
        return file
    }

    private companion object {
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
