package hivens.launcher.update

import hivens.core.api.interfaces.IUpdateApplicator
import hivens.launcher.platform.OS
import java.nio.file.Path

/**
 * Factory entry point for [IUpdateApplicator] selection.
 *
 * Picks the right platform implementation based on [OS]; falls back to
 * [NoOpUpdateApplicator] on anything unrecognized. The Koin module wires
 * the result of [forCurrentPlatform] as a singleton so callers (notably
 * `UpdateDialog` in `client-ui`) get the right concrete via injection.
 *
 * The previous flat `object UpdateApplicator` (320 lines, three platform
 * flows + helpers crammed together) was unmockable and grew into the same
 * smell that Config-split untangled for `AppConfig`.
 */
object UpdateApplicators {
    fun forCurrentPlatform(): IUpdateApplicator = when {
        OS.isWindows -> WindowsUpdateApplicator()
        OS.isMacOS   -> MacUpdateApplicator()
        OS.isLinux   -> LinuxUpdateApplicator()
        else         -> NoOpUpdateApplicator()
    }
}

/**
 * Stand-in for unsupported platforms. Throws on [scheduleUpdate]; callers
 * should treat the exception as "ask the user to download and install
 * manually". Splitting this out keeps the factory branch logic exhaustive
 * and gives Koin something to bind even when [OS] reports `Unknown`.
 */
class NoOpUpdateApplicator : IUpdateApplicator {
    override fun scheduleUpdate(installerPath: Path) {
        throw UnsupportedOperationException("Update not supported on ${OS.getName()}")
    }
}
