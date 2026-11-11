package hivens.config

/** Launcher cosmetics + identity constants (window title, version, upstream affiliation). Pure UX surface; nothing here is sent on the wire. */
object Branding {
    const val TITLE = "Nexira"

    /**
     * X11 WM_CLASS / Wayland app_id. Must match `StartupWMClass=` in
     * `resources/nexira.desktop` and the AppStream metainfo `<id>`
     * slug, otherwise compositors cannot link the live window to the
     * .desktop entry (icon falls back to generic in workspace overviews).
     */
    const val WM_CLASS = "Nexira"

    /** Launcher's own semantic version. Baked at build time from `git describe --tags --dirty`. */
    const val VERSION: String = BuildConfig.FORK_VERSION

    /**
     * Upstream service name. Flows to the Minecraft client as
     * `-Dminecraft.launcher.brand`, and substitutes into localized
     * "An unofficial launcher for..." copy in the About screen.
     */
    const val UPSTREAM_NAME = "smartycraft"

    /** GitHub `owner/repo` slug. Centralized so a fork doesn't grep across UI for hard-coded URLs. */
    const val REPO_SLUG = "Kitty-Hivens/Nexira"

    val REPO_URL get() = "https://github.com/$REPO_SLUG"
    val ISSUE_NEW_URL get() = "$REPO_URL/issues/new"
}
