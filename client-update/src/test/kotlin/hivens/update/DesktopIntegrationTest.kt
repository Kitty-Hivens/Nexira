package hivens.update

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopIntegrationTest {

    private val integration = DesktopIntegration()
    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("desktop-integration-test-")
    }

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun tearDown() {
        tmp.deleteRecursively()
    }

    @Test
    fun `entry execs the appimage and names the theme icon`() {
        val content = integration.desktopEntryContent("/home/u/Apps/Nexira.AppImage", "nexira")
        assertTrue("Exec=\"/home/u/Apps/Nexira.AppImage\" %U" in content)
        assertTrue("Icon=nexira" in content)
        assertTrue("StartupWMClass=Nexira" in content)
        assertTrue("MimeType=x-scheme-handler/nexira;" in content)
    }

    @Test
    fun `png size reads IHDR dimensions and rejects a non-png`() {
        assertEquals(64 to 64, integration.pngSize(writePng(64, 64)))

        val notPng = tmp.resolve("binary").also { Files.writeString(it, "ELF not an image") }
        assertNull(integration.pngSize(notPng))
    }

    @Test
    fun `icon install lands in the size-bucketed apps dir and names the icon`() {
        val iconsRoot = tmp.resolve("icons")

        val name = integration.installIconInto(writePng(256, 256), iconsRoot)

        assertEquals("nexira", name)
        val installed = iconsRoot.resolve("hicolor").resolve("256x256").resolve("apps").resolve("nexira.png")
        assertTrue(Files.isRegularFile(installed), "icon not installed at $installed")
        assertEquals(256 to 256, integration.pngSize(installed))
    }

    @Test
    fun `the exec target is read back from the entry this class writes`() {
        val content = integration.desktopEntryContent("/home/u/My Apps/Nexira-x86_64.AppImage", "nexira")
        assertEquals("/home/u/My Apps/Nexira-x86_64.AppImage", integration.execTargetOf(content))
        assertEquals("/opt/nexira", integration.execTargetOf("[Desktop Entry]\nExec=/opt/nexira %U\n"))
        assertNull(integration.execTargetOf("[Desktop Entry]\nName=Nexira\n"))
    }

    @Test
    fun `an entry whose AppImage is gone is dead`() {
        val entry = tmp.resolve("dev.hivens.nexira.desktop")
        Files.writeString(entry, integration.desktopEntryContent(tmp.resolve("Nexira-2.4.5-x86_64.AppImage").toString(), "nexira"))

        assertTrue(integration.isDeadEntry(entry, tmp.resolve("Nexira-x86_64.AppImage").toString()))
    }

    /** A nightly beside a release: whichever the user launched last must not take the menu from the other. */
    @Test
    fun `an entry naming another copy that still exists is left alone`() {
        val release = Files.writeString(tmp.resolve("Nexira-x86_64.AppImage"), "RELEASE")
        val entry = tmp.resolve("dev.hivens.nexira.desktop")
        Files.writeString(entry, integration.desktopEntryContent(release.toString(), "nexira"))

        assertFalse(integration.isDeadEntry(entry, tmp.resolve("Nexira-nightly-x86_64.AppImage").toString()))
    }

    @Test
    fun `an entry already naming the running AppImage is left alone`() {
        val running = tmp.resolve("Nexira-x86_64.AppImage").toString()
        val entry = tmp.resolve("dev.hivens.nexira.desktop")
        Files.writeString(entry, integration.desktopEntryContent(running, "nexira"))

        assertFalse(integration.isDeadEntry(entry, running))
    }

    @Test
    fun `no entry is not a dead entry`() {
        assertFalse(integration.isDeadEntry(tmp.resolve("dev.hivens.nexira.desktop"), "/anywhere/Nexira-x86_64.AppImage"))
    }

    private fun writePng(w: Int, h: Int): Path {
        val file = tmp.resolve("src-${w}x${h}.png")
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        Files.newOutputStream(file).use { ImageIO.write(img, "png", it) }
        return file
    }
}
