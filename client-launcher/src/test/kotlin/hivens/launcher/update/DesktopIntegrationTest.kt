package hivens.launcher.update

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopIntegrationTest {

    @Test
    fun `desktop entry points Exec, icon and WM class at the appimage`() {
        val content = DesktopIntegration().desktopEntryContent("/home/u/Apps/Nexira.AppImage")
        assertTrue("Exec=\"/home/u/Apps/Nexira.AppImage\" %U" in content)
        assertTrue("Icon=/home/u/Apps/Nexira.AppImage" in content)
        assertTrue("StartupWMClass=Nexira" in content)
        assertTrue("MimeType=x-scheme-handler/nexira;" in content)
    }
}
