package hivens.ui.diag

/**
 * Process-global switch for skinema (FFmpeg-via-Panama) media. Set once at boot
 * from [hivens.core.data.SettingsData.disabledModules] before the first shell
 * composition, then read by every skinema construction site -- background video,
 * music, the media widget, wallpaper-import optimize. When off, each site takes
 * its existing "no media" branch, so a launcher whose skinema natives are broken
 * (e.g. missing FFmpeg DLLs on Windows) starts clean instead of failing per
 * surface. A plain flag, not a settings read: the call sites are non-composable
 * and non-Koin, and the value is fixed for the process lifetime.
 */
object SkinemaGate {
    @Volatile
    var enabled: Boolean = true
}
