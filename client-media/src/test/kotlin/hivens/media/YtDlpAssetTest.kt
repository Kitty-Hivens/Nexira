package hivens.media

import hivens.core.platform.Arch
import hivens.core.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YtDlpAssetTest {

    @Test
    fun windowsIsExe() {
        assertEquals("yt-dlp.exe", ytDlpAsset(Platform.WINDOWS, Arch.X64))
        assertEquals("yt-dlp.exe", ytDlpAsset(Platform.WINDOWS, Arch.ARM64))
    }

    @Test
    fun macOsIsUniversalBinary() {
        assertEquals("yt-dlp_macos", ytDlpAsset(Platform.MACOS, Arch.ARM64))
        assertEquals("yt-dlp_macos", ytDlpAsset(Platform.MACOS, Arch.X64))
    }

    @Test
    fun linuxPicksArchVariant() {
        assertEquals("yt-dlp_linux", ytDlpAsset(Platform.LINUX, Arch.X64))
        assertEquals("yt-dlp_linux_aarch64", ytDlpAsset(Platform.LINUX, Arch.ARM64))
    }

    @Test
    fun unknownPlatformHasNoAsset() {
        assertNull(ytDlpAsset(Platform.UNKNOWN, Arch.X64))
    }
}
