package hivens.launcher.update

import hivens.core.platform.OS

/**
 * True when the launcher runs as a Linux AppImage -- the only environment
 * where the updater can swap the running binary in place. Gates the `.desktop`
 * entry install ([DesktopIntegration]).
 */
internal fun runningAsLinuxAppImage(): Boolean =
    OS.isLinux && !System.getenv("APPIMAGE").isNullOrBlank()
