package hivens.launcher.update

/**
 * True when the launcher runs as a Linux AppImage -- the only environment
 * where the update manager can swap the running binary in place. Gates both
 * the source-build channels ([SourceBuildService]) and the `.desktop` entry
 * install ([DesktopIntegration]).
 */
internal fun runningAsLinuxAppImage(): Boolean =
    System.getProperty("os.name", "").lowercase().contains("linux") &&
        !System.getenv("APPIMAGE").isNullOrBlank()
