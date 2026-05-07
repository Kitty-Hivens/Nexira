package hivens.config

/**
 * What the launcher calls itself in front of the user — title, version, upstream
 * affiliation. Pure cosmetics; nothing here is sent on the wire.
 */
object Branding {
    /** Human-readable name shown in window title, tray tooltip, About screen. */
    const val TITLE = "Aura Launcher"

    /**
     * This launcher's own semantic version — generated at build time from
     * `git describe --tags --dirty`, so it tracks the released artifact.
     */
    const val VERSION: String = BuildConfig.FORK_VERSION

    /**
     * Name of the upstream service we are an unofficial launcher *for*.
     * Substituted into localized About-screen copy ("An unofficial launcher
     * for SMARTYcraft") and forwarded to the Minecraft client as
     * `-Dminecraft.launcher.brand` so vanilla telemetry sees the right vendor.
     */
    const val UPSTREAM_NAME = "smartycraft"
}
