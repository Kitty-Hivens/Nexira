package hivens.ui.i18n

import hivens.core.data.PackAuthRequirement
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object EnglishStrings : AppStrings {

    // App
    override val appName = "Nexira"

    // Login
    override val loginTitle        = "Nexira"
    override val loginUsername     = "Username"
    override val loginPassword     = "Password"
    override val loginRemember     = "Remember password"
    override val loginButton       = "Log in"
    override val loginErrorEmpty   = "Enter your username and password"
    override val loginErrorGeneric = "Login error"
    override val loginRegister     = "Create an account"
    override val loginPlayOffline  = "Play offline"
    override val loginMicrosoft    = "Sign in with Microsoft"
    override val msaTitle          = "Sign in with Microsoft"
    override val msaInstruction    = "Open this page and enter the code:"
    override val msaCopyCode       = "Copy code"
    override val msaOpenBrowser    = "Open page"
    override val msaWaiting        = "Waiting for confirmation..."

    // Navigation
    override val navLogout   = "Log out"
    override val navBack     = "Back"
    override val navForward  = "Forward"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WELCOME BACK, $name"
    override val dashboardServers              = "Available servers"
    override val dashboardServersEmpty         = "No servers found"
    override val dashboardLoginRequiredTitle   = "Sign in to see servers"
    override val dashboardLoginRequiredHint    = "The SmartyCraft server list lives behind authentication. Sign in from the Profile section."

    // Launch Control
    override val launchReady       = "Ready to play"
    override val launchButton      = "Play"
    override val launchAbort       = "Cancel"
    override val launchRunning     = "Game running"
    override val launchStop        = "Stop"
    override val launchDownloading = "Downloading:"
    override val launchPreparing   = "Preparing"
    override val launchFailed      = "Launch failed"

    // Launcher States
    override val stateInit        = "Initializing..."
    override val stateAuth        = "Authenticating..."
    override val stateAuthFail    = "Authentication error (offline?)"
    override val stateNoPassword  = "No password found, using current session."
    override val stateSync        = "Syncing files..."
    override val stateJvm         = "Preparing JVM..."
    override val stateLaunching   = "Starting process..."
    override fun stateExitCode(code: Int)  = "Game exited with code $code"
    override fun stateError(msg: String)   = "Error: $msg"
    override fun stateHelperUnavailable(mcVersion: String) =
        "No open-smrt helper for Minecraft $mcVersion. Launch blocked so the proprietary Smarty mod isn't run; disable the helper swap in Settings to play with it."
    override fun stateAuthlibUnavailable(mcVersion: String) =
        "Could not get the SmartyCraft authlib for Minecraft $mcVersion. Launch blocked: the join would be rejected. Check your connection and SmartyCraft sign-in, then try again."
    override fun stateMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY ->
            "This pack needs a SmartyCraft account. Sign in to play."
        else ->
            "This pack needs to sign in with '$providerKey'."
    }
    override fun authSuccess(uuid: String) = "Login successful. UUID: $uuid"

    // Profile
    override val profileTitle              = "Profile"
    override val profileStatusLabel        = "Status"
    override val profileStatusOnline       = "Authenticated"
    override val profileStatusOffline      = "Offline"
    override val profileBalance            = "Balance"
    override val profileTopUp              = "Top up balance"
    override val profileUploadSkin         = "Upload skin"
    override val profileUploadSkinLoading  = "Uploading..."
    override val profileSkinLoading        = "Loading skin..."
    override val profileRefresh            = "Refresh"
    override val profileUploadSuccess      = "Skin uploaded successfully"
    override fun profileUploadError(msg: String) = "Upload error: $msg"

    // Settings
    override val settingsTitle              = "Global settings"
    override val settingsSectionUI          = "Interface"
    override val settingsSectionBehavior    = "Behavior"
    override val settingsThemePicker        = "Choose theme"
    override val settingsThemePickerSub     = "Customize the color scheme"
    override val settingsDarkTheme          = "Dark theme"
    override val settingsDarkThemeDesc      = "Dark interface theme"
    override val settingsThemeModeTitle             = "Theme source"
    override val settingsThemeModeManual            = "Manual"
    override val settingsThemeModeSystem            = "System"
    override val settingsThemeModeWallpaper         = "Wallpaper"
    override val settingsThemeModeSystemUnavailable = "System scheme is not available in this environment"
    override val settingsPaletteFromWallpaper       = "Colors from wallpaper"
    override val settingsPaletteFromWallpaperDesc   = "Off: a theme keeps its own colors"
    override val settingsCustomChrome               = "In-app title bar"
    override val settingsCustomChromeDesc           = "Replace the window's title bar with the app's own top bar. Applies on the next launch."
    override val settingsCustomChromeTiling         = "Your window manager draws no title bar, so this changes nothing here."
    override val settingsCloseAfterLaunch   = "Hide the launcher after the game starts"
    override val settingsCloseAfterLaunchDesc = "Hides the launcher to the system tray once the game starts, or minimizes the window where there is no tray."
    override val settingsSaved              = "Settings saved"
    override val settingsLanguage           = "Language"

    // Theme Picker
    override val themePickerTitle           = "Choose theme"
    override val themePickerApply           = "Apply"
    override val themePickerPreview         = "Preview"
    override val themePickerSelected        = "Selected"
    override val themePickerColorPrimary    = "Primary"
    override val themePickerColorSecondary  = "Secondary"
    override val themePickerColorBackground = "Background"
    override val themePickerColorSurface    = "Surface"
    override val themePickerColorAccent     = "Accent"
    override val themePickerColorSuccess    = "Success"
    override val themePickerColorError      = "Error"
    override val themePickerBtnSample       = "Sample Button"
    override val themePickerBtnOutlined     = "Outlined Button"

    // News
    override val newsTitle   = "Project news"
    override val newsEmpty   = "No news yet..."
    override val newsFilterPlaceholder = "Filter news"
    override val newsFilterClear        = "Clear filter"
    override val railCollapse           = "Collapse panel"
    override val railExpand             = "Expand panel"
    override val windowMinimize         = "Minimize"
    override val windowMaximize         = "Maximize"
    override val windowRestore          = "Restore"
    override val windowClose            = "Close"
    override val crumbHome              = "Home"
    override val crumbLoading           = "Loading…"
    override val paginationPrev         = "Previous page"
    override val paginationNext         = "Next page"

    // Server Detail
    override val serverDetailTitle         = "Server information"
    override val serverDetailNoImage       = "No image"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Information missing"
    override fun serverDetailMissingPath(file: String) = "Create $file in:"

    // Server Settings
    override val serverSettingsSubtitle        = "Launch settings"
    override val serverSettingsSectionSystem   = "System"
    override val serverSettingsSectionMods     = "Modifications"
    override val serverSettingsRam             = "RAM"
    override fun serverSettingsRamValue(mb: Int) = "RAM: $mb MB"
    override val serverSettingsJava            = "Java version"
    override fun serverSettingsJavaAuto(version: String) = "Automatic ($version)"
    override val serverSettingsJavaHint        = "Leave empty to use bundled Java"
    override val serverSettingsOpenFolder      = "Open folder"
    override val serverSettingsReset           = "Reset client"

    override val serverSettingsResetConfirmTitle = "Reset this client?"
    override val serverSettingsResetConfirmBody  = "All downloaded files for this server's client are deleted permanently. This cannot be undone."
    override val backgroundResetConfirmTitle     = "Reset background?"
    override val backgroundResetConfirmBody      = "The entire custom background configuration returns to its defaults."
    override val logoutConfirmTitle              = "Log out?"
    override val logoutConfirmBody               = "Your saved sign-in is removed from this device. You will need to enter your credentials again to log back in."

    override val serverSettingsNoMods          = "No optional mods"
    override val serverSettingsPickJava        = "Select Java"

    // Update
    override val updateTitle           = "Update available"
    override val updateTitleCritical   = "Critical update"
    override val updateTitleMandatory  = "Mandatory update"
    override val updateCriticalBanner  = "This update contains critical security fixes."
    override val updateMandatoryBanner =
        "Server-side compatibility broke for older versions. The launcher cannot continue without this update."
    override fun updateMandatoryBannerWithReason(reason: String) =
        "Required by the upstream protocol: $reason"
    override val updateChangelog       = "Full changelog"
    override val updateHighlights      = "What's new"
    override val updateViewOnGitHub    = "View on GitHub"
    override val updateLater           = "Later"
    override val updateExit            = "Quit"
    override val updateDownload        = "Download and install"
    override val updateDownloadNow     = "Download now"
    override val updateDownloading     = "Downloading..."
    override val updateInstall         = "Install and restart"
    override val updateRetry           = "Retry"
    override val updateErrorTitle      = "Download error"
    override val updateErrorUnknown    = "Unknown error"
    override val updateScheduleFailed  = "Failed to schedule update"
    override fun updateVersion(version: String) = "Version $version"
    override val updateDetails         = "Details"

    // Desktop entry install (Advanced)
    override val updateManagerInstallDesktop = "Install .desktop entry"
    override val updateManagerDesktopDone    = "Desktop entry installed"

    // Console
    override val consoleTitle = "Debug Console"
    override val consoleEmptyHint = "All quiet. Launch a pack and the logs will stream in here."
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Game Output ($filtered/$total)"
    override val consoleCopyAll = "Copy All"
    override val consoleClear   = "Clear"
    override val consoleWrap    = "Wrap"
    override val consoleSaveToFile = "Save to file"
    override val consoleSearchPlaceholder = "Search…"
    override val consoleCopied = "Copied"
    override val consoleCommandPlaceholder = "command to game (Enter, ↑↓ history, Esc)"
    override val consoleMenuCopyLine = "Copy line"
    override val consoleMenuCopySelection = "Copy selection"
    override val consoleSelectAll = "Select all"
    override val consoleSettingsLabel = "Console settings"
    override val consoleShowGutter = "Show severity strip"
    override val consoleHideGutter = "Hide severity strip"
    override val consoleShowTimestamps = "Show timestamps"
    override val consoleHideTimestamps = "Hide timestamps"
    override val consoleStatusFollow = "follow"
    override val consoleStatusPaused = "paused"
    override fun consoleStatusLines(filtered: Int, total: Int) = "lines: $filtered/$total"
    override fun consoleStatusLinesWithHistory(filtered: Int, total: Int, history: Int) =
        "lines: $filtered/$total  +$history in history"
    override fun consoleStatusFiltered(warn: Int, error: Int) = "WARN $warn  ERROR $error"
    override fun consoleStatusMatch(current: Int, total: Int) = "match $current/$total"

    // Tray
    override val trayConsole  = "Open console"
    override val trayExit     = "Exit"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics      = "Diagnostics"
    override val settingsOpenLogs                = "Open logs folder"
    override val settingsOpenCrashReports        = "Crash reports"
    override val settingsCreateDiagnosticBundle  = "Create diagnostic bundle"
    override val settingsDiagnosticBundleHint    = "Bundles redacted logs, crash reports, action history and system info into one ZIP for support."
    override val settingsReportOnGithub          = "Report on GitHub with bundle"

    override val reportDescribeHeading  = "Description"
    override val reportCrashHint        = "What were you doing when the launcher crashed?"
    override val reportBundleHint       = "Describe the problem."
    override val reportLanguageNudge    = "Please write in English if you can."
    override val reportBundleCreated    = $$"The diagnostic bundle `$bundle` was created in the launcher's data directory, and its full path is on your clipboard."
    override val reportBundleAttach     = "**Drag the ZIP into this window before submitting** (GitHub accepts drag-and-drop)."

    // File Manager
    override fun fileDownloading(n: Int) = "Downloading updates ($n ${twoFormPlural(n, "file", "files")})..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Offline mode"
    override val settingsOfflineModeDesc   = "Launch without authentication. Files won't be synced."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Offline mode — authentication skipped"
    override val stateOfflineSkipSync      = "Offline mode — file sync skipped, using local files"
    override fun stateForeignContentRemoved(count: Int, names: String) =
        "Removed $count file(s) the pack does not include: $names"
    override val stateContentChanged       = "The pack was modified, so the launch was stopped. Please do not modify a pack's files."
    override val stateOfflineNoClient      = "Client files not found. Download them online first."
    override val stateOfflineNoManifest    = "No cached manifest for this server. Log in online at least once before launching offline."

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs     = "JVM arguments"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
    override val serverSettingsJvmBuildArgs = "Build args"
    override val serverSettingsResolution  = "Window size"
    override val serverSettingsWidth       = "Width"
    override val serverSettingsHeight      = "Height"
    override val serverSettingsFullscreen  = "Fullscreen"
    override val serverSettingsAutoConnect = "Auto-connect to server"

    // --- Server Settings: Icon Upload ---
    override val serverSettingsPickIcon    = "Select server icon"

    // =========================================================================
    // RAM Selector
    // =========================================================================
    override val ramCustomInputLabel = "Custom value:"
    override fun ramSystemHint(systemRam: String, recommended: String) =
        "System: $systemRam • Recommended max: $recommended"
    override fun ramAutoLabel(resolved: String) = "Auto · ~$resolved"

    // =========================================================================
    // Mod cards
    // =========================================================================
    override fun modConflictWarning(ids: String) = "Conflicts with: $ids"
    override fun modIncompatibleHint(ids: String) = "Incompatible with: $ids"

    // =========================================================================
    // Server grid
    // =========================================================================
    override val serversFavorites = "★ FAVORITES"

    // =========================================================================
    // Custom Background
    // =========================================================================
    override val backgroundTitle          = "Appearance"
    override val backgroundSubtitle       = "Launcher wallpaper, theme and palette"
    override val backgroundEnable         = "Enable"
    override val backgroundSectionImage   = "Image or video"
    override val backgroundPickFile       = "Choose a background image or video"
    override val backgroundPickButton     = "Choose file"
    override val backgroundCancelOptimize = "Stop preparing the video"
    override val backgroundSectionScale   = "Scaling"
    override val backgroundScaleCover     = "Cover"
    override val backgroundScaleContain   = "Contain"
    override val backgroundScaleStretch   = "Stretch"
    override val backgroundScaleOriginal  = "Original"
    override val backgroundScaleTile      = "Tile"
    override val backgroundSectionPosition = "Position"
    override val backgroundAlignX         = "Horizontal"
    override val backgroundAlignY         = "Vertical"
    override val backgroundSectionEffects = "Effects"
    override val backgroundBlur           = "Blur"
    override val backgroundDarken         = "Darken"
    override val backgroundOpacity        = "Opacity"
    override val backgroundSaturation     = "Saturation"
    override val backgroundParallax       = "Parallax"
    override val backgroundVignette       = "Vignette"
    override val backgroundAnimationSpeed = "Animation speed"
    override val backgroundSectionTint    = "Color tint"
    override val backgroundTintNone       = "None"
    override val backgroundTintNavy       = "Dark blue"
    override val backgroundTintViolet     = "Violet"
    override val backgroundTintEmerald    = "Emerald"
    override val backgroundTintBordeaux   = "Bordeaux"
    override val backgroundTintSteel      = "Steel"
    override val backgroundTintIntensity  = "Intensity"
    override val backgroundReset          = "Reset to defaults"
    override val backgroundPreview        = "Preview"
    override val backgroundPreviewServer  = "Example server"
    override val settingsBackground       = "Custom background"
    override val settingsBackgroundSub    = "Photo or GIF as launcher wallpaper"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "About"
    override fun aboutDescription(branding: String) = "Unofficial launcher for $branding"
    override val locale: Locale = Locale.ENGLISH
    override val byteUnits = listOf("B", "KB", "MB", "GB", "TB")
    override fun aboutBuildDate(date: String) = "Built: $date"
    override val aboutRenderer = "Renderer"
    override val aboutSectionCreator       = "Creator"
    override val aboutSectionTechnologies  = "Technologies"
    override val aboutSectionLicense       = "License"
    override val aboutLicenseText          = "GPLv3 — Free and open source software"
    override val aboutSectionUpdates       = "Updates"
    override val aboutCurrentVersion       = "Current version"
    override val aboutCheckUpdates         = "Check for updates"
    override val aboutChecking             = "Checking..."
    override fun aboutUpdateAvailable(version: String) = "Version $version available"
    override val aboutCriticalUpdate       = "Critical update"
    override val aboutSectionSystem        = "System"
    override val aboutOs                   = "OS"
    override val aboutSectionLinks         = "Links"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Report a bug"
    override val aboutLinkReleases         = "Releases"
    override val settingsSectionAbout      = "About"

    // Tech stack descriptions
    override val techKotlinDesc  = "Primary language"
    override val techComposeDesc = "UI framework"
    override val techKtorDesc    = "HTTP client"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Graphics renderer"
    override val techCoilDesc    = "Image loading"

    // --- Spawn Reset ---
    override val spawnResetButton  = "Return to spawn"
    override val spawnResetLoading = "Resetting..."
    override val spawnResetSuccess = "Done! Rejoin to apply"
    override val spawnResetError   = "Server error"

    // --- Tray ---
    override val trayStatusIdle    = "Idle"
    override val trayStatusRunning = "Game running"
    override val trayShow          = "Show launcher"
    override val trayHintTitle     = "Nexira is still running"
    override val trayHintBody      = "The window is hidden in the system tray. Click the tray icon to bring it back."
    override val trayHintShow      = "Show window"

    // --- Settings: Advanced (updates, launch, data directory) ---
    override val settingsSectionUpdates      = "Updates"
    override val settingsSectionLaunch       = "Launch"
    override val settingsPreReleases         = "Pre-release updates"
    override val settingsPreReleasesDesc     = "Receive beta builds before they are promoted to stable."
    override val settingsMandatoryUpdates       = "Mandatory updates"
    override val settingsMandatoryUpdatesDesc   = "Block startup until critical updates are installed when the upstream protocol breaks. Off by default: the floor can block your own startup, so honouring it is a deliberate choice."
    override val settingsAutoSyncAllPacks       = "Auto-sync SmartyCraft clients on launch"
    override val settingsAutoSyncAllPacksDesc   = "Re-sync every SmartyCraft client you have already installed when the launcher starts, in the background. With two-factor sign-in it never logs in — a login would revoke the session you unlocked with a code — so it syncs only against a manifest an earlier manual sign-in cached, and skips a server without one. The SmartyCraft server path is retired from 2.5.0 and its faults are not being fixed; a mirror pack is the supported route. Costs background bandwidth."
    override val settingsAutoUpdatePacks        = "Auto-update installed instances"
    override val settingsAutoUpdatePacksDesc    = "Keep installed pack instances on the latest build. Safe updates apply in the background; a Minecraft or loader change follows the policy below. Turn off to update them by hand."
    override val settingsAmberPolicy            = "When a build changes Minecraft or the loader"
    override val settingsAmberPolicyDesc        = "A pending build is compared against the installed one by Minecraft version, loader family and loader version. A newer loader version re-syncs on its own; a different Minecraft version or a different loader family can invalidate worlds, configs and mod state, so this decides what happens then. A restore point is taken before anything is applied, and keeping the current build also stops the reminders. Mirror instances only — a SmartyCraft client is not graded this way."
    override val settingsAmberPolicyAsk         = "Ask me"
    override val settingsAmberPolicyApply       = "Apply automatically"
    override val settingsAmberPolicyHold        = "Keep current build"
    override val settingsJvmBuilder             = "Visual JVM args builder"
    override val settingsJvmBuilderDesc         = "Reveals a 'Build args' button in the per-server constructor. Pick a GC algorithm, tune heap regions, enable AppCDS or JFR profiling — without memorizing flags. Curated presets cover Aikar's recipe, GTNH-class heavy modded, ZGC for huge heaps, and more."
    override val settingsAdaptiveMemory         = "Adaptive memory"
    override val settingsAdaptiveMemoryDesc     = "Refines each instance's heap from real usage over a few sessions, on top of the automatic machine-based baseline. Pin a specific RAM value to opt an instance out; turn this off to keep the automatic baseline without learning."
    override val settingsMimicVersion           = "Mimic launcher version override"
    override val settingsMimicVersionDesc       = "Pin the version string sent to upstream in the handshake and User-Agent. Leave blank to use the shipped default — set this only when upstream has bumped its version pin faster than Nexira's release cycle. Takes effect on the next protocol call after save; no restart needed."
    override fun settingsMimicVersionPlaceholder(default: String) = "Default: $default"
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Syncing $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB MB"
    override val widgetProgressTitle = "Background activity"
    override val widgetProgressIdle = "Nothing is downloading right now."
    override fun widgetTabDefaultLabel(index: Int) = "Tab $index"

    // April Fools
    override fun aprilCloseTitle(escapes: Int) = when {
        escapes == 0 -> "Hold on a second..."
        escapes < 3  -> "Are you sure?"
        escapes < 6  -> "Please... we had such a good time"
        escapes < 8  -> "This is getting embarrassing for both of us"
        else         -> "Fine. I give up."
    }

    override fun aprilCloseBody(escapes: Int) = when {
        escapes == 0 -> "Launcher worked really hard today. Will you really abandon it?"
        escapes < 3  -> "Everything you need is right here. The button is just... nervous."
        escapes < 6  -> "Escape attempts: $escapes. The button can't run forever, you know."
        escapes < 8  -> "You're very determined. The button is getting tired. Almost there..."
        else         -> "You win. You're an incredibly persistent human being."
    }

    override val aprilCloseStay      = "Stay"
    override val aprilCloseClose     = "Close"
    override val aprilCloseSurrender = "Close (finally)"
    override val aprilCloseHideTray  = "Hide to tray"
    override fun aprilCloseEscapeCount(current: Int, max: Int) =
        "The close button has fled $current / $max times"

    // --- 2FA (TOTP) — #159 ---
    override val auth2faTitle           = "Two-factor authentication"
    override val auth2faPrompt          = "Enter the 6-digit code from your authenticator app to finish signing in."
    override val auth2faPlaceholder     = "000000"
    override val auth2faSubmit          = "Verify"
    override val auth2faCancel          = "Cancel"
    override val auth2faInvalid         = "Wrong code. Try again."
    override val auth2faExpired         = "The 2FA session expired. Please sign in again."

    override val auth2faUnsupportedTitle   = "Unfortunately, 2FA doesn't work here"
    override val auth2faUnsupportedBody    = "We're sorry — we can't really support 2FA here. Our protocols differ enough from what Smartycraft uses that even though the 2FA login itself works, the game just throws errors after that. Please disable 2FA on your account on the website."
    override val auth2faUnsupportedDismiss = "Got it"

    // --- SSL Warning ---
    override val sslWarningTitle        = "Server certificate expired"
    override val sslWarningBody         = "The server's SSL certificate has expired. Your connection may be insecure — server identity cannot be verified. Proceed at your own risk?"
    override val sslWarningConnectAnyway = "Connect anyway"
    override val sslWarningCancel       = "Cancel"
    override val sslWarningTrustPrompt  = "Trust this host for:"
    override val sslWarningTrustHour    = "1 hour"
    override val sslWarningTrust30Days  = "30 days"
    override val sslWarningTrustAlways  = "Always"

    override val settingsSectionNetwork = "Network"
    override val sslBypassListTitle     = "Active SSL bypasses"
    override val sslBypassNoEntries     = "No active bypasses"
    override val sslBypassRevoke        = "Revoke"
    override fun sslBypassExpiresAt(formatted: String) = "Expires: $formatted"


    override val settingsSectionSmarty           = "Smarty servers"
    override val settingsOpenSmrtHelperTitle      = "Use the alternative smrt network helper"
    override val settingsOpenSmrtHelperDesc       = "Replace the upstream Smarty mod with our open-source helper on Smarty servers. Same network features, none of the surveillance. If no replacement exists for the game version, the launch is blocked rather than running the original mod."
    override val settingsStrictModCheckTitle      = "Exact mod verification"
    override val settingsStrictModCheckDesc       = "After syncing, delete everything in the mods folder the server did not ask for. Keeps the install clean, but also removes any mods you added by hand."
    override val settingsNetworkAgentTitle        = "Use the network-support agent"
    override val settingsNetworkAgentDesc         = "Point the game's login at SmartyCraft when it starts: the in-game join and the skin checks. The join then authenticates against SmartyCraft and skins still load, without swapping in SmartyCraft's patched login library. Needed to join SmartyCraft servers."
    override val settingsSmartyAuthLibTitle       = "Use SmartyCraft's login library"
    override val settingsSmartyAuthLibDesc        = "The older approach: take SmartyCraft's patched login library from its client and put it on the pack instead of the original. Replaced by the network agent above and kept as a fallback. If the file cannot be fetched, the launch is blocked. Off by default."

    override val settingsSectionDataDir       = "Data directory"
    override val settingsDataDirCurrent       = "Current path:"
    override val settingsDataDirMove          = "Move..."
    override val settingsDataDirPickerTitle   = "Choose a new location for Nexira data"
    override val settingsDataDirConfirmTitle  = "Move data directory?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Nexira will relocate its data:\nfrom: $source\nto:   $target\n\nThe move applies on the next launcher start."
    override val settingsDataDirRestartRequired = "Restart required — Nexira will apply the move on next start"
    override val settingsDataDirQuitNow         = "Quit now"
    override val settingsDataDirErrorSamePath   = "That's already the current directory — pick a different folder"
    override val settingsDataDirErrorNotEmpty   = "Target folder is not empty — pick an empty folder or delete its contents"
    override fun settingsDataDirErrorPickerFailed(reason: String) =
        "Couldn't open the folder picker: $reason"

    // ── JVM Args Builder ────────────────────────────────────────────────
    override val jvmTitle    = "JVM Args Builder"
    override val jvmSubtitle = "Pick a preset or compose flags by hand. The result lands in jvmArgs."
    override val jvmPresetsHeader = "Presets"
    override val jvmTabGc      = "GC"
    override val jvmTabTuning  = "G1 / Z / Shenandoah"
    override val jvmTabCds     = "AppCDS"
    override val jvmTabJit     = "JIT"
    override val jvmTabPerf    = "Performance"
    override val jvmTabJfr     = "JFR"
    override val jvmTabCustom  = "Custom"
    override val jvmCancel     = "Cancel"
    override val jvmApply      = "Apply to jvmArgs"
    override fun jvmPreviewFlagsCount(n: Int) = "Preview ($n ${twoFormPlural(n, "flag", "flags")})"

    override val jvmGcHeader            = "Garbage Collector"
    override val jvmGcG1Hint            = "Recommended for modded MC, 4-32 GB heap."
    override val jvmGcZHint             = "Sub-millisecond pauses. Java 17+, 16+ GB heap. Generational on Java 21+."
    override val jvmGcShenandoahHint    = "Concurrent low-pause from OpenJDK / Liberica. Java 17+."
    override val jvmGcParallelHint      = "Throughput-first. Long stop-the-world pauses. Almost never the right pick."
    override val jvmGcSerialHint        = "Single-threaded. Tiny heaps only (< 1 GB)."

    override val jvmG1Header                  = "G1GC tuning"
    override val jvmG1MaxPauseMillisHint      = "Target max pause time. Lower = more frequent collections."
    override val jvmG1RegionSizeHint          = "Region size in MB. Larger = fewer regions, less metadata."
    override val jvmG1NewSizePercentHint      = "Min young generation as % of heap. Aikar: 30."
    override val jvmG1MaxNewSizePercentHint   = "Max young generation as % of heap. Aikar: 40."
    override val jvmG1IhopHint                = "When mixed GC starts. Aikar: 15 (eager). Stock: 45."
    override val jvmG1ParallelRefProcHint     = "Process references in parallel. Pure win on multi-core."
    override val jvmG1PerfDisableSharedMemHint = "Skip /tmp/hsperfdata. Stops VisualVM but improves disk hygiene."

    override val jvmZHeader            = "ZGC tuning"
    override val jvmZGenerationalHint  = "Java 21+ only. Splits heap into young / old. Significantly better than non-generational."

    override val jvmShenandoahHeader        = "Shenandoah heuristic"
    override val jvmShenandoahAdaptiveHint  = "Default. Balances pause vs throughput."
    override val jvmShenandoahStaticHint    = "Trigger collection at fixed thresholds."
    override val jvmShenandoahCompactHint   = "Aggressive compaction. Better at memory reclaim."
    override val jvmShenandoahAggressiveHint = "Continuous collection. High throughput cost."

    override fun jvmTuningNotApplicable(gcName: String) =
        "No tuning available for $gcName. Switch to G1, Z, or Shenandoah on the GC tab."

    override val jvmCdsHeader            = "Application Class Data Sharing"
    override val jvmCdsIntro             = "Cache the loaded class metadata across launches. For 200+ mod packs, saves 1-3 seconds on every cold start after the first."
    override val jvmCdsModeDisabledLabel = "Disabled"
    override val jvmCdsModeDisabledHint  = "No CDS. Default."
    override val jvmCdsModeAutoLabel     = "Auto-archive (Java 19+)"
    override val jvmCdsModeAutoHint      = "JVM auto-manages the archive at exit. No path needed."
    override val jvmCdsModeArchiveLabel  = "Archive at exit"
    override val jvmCdsModeArchiveHint   = "Write archive to your specified path on shutdown."
    override val jvmCdsModeUseLabel      = "Use existing archive"
    override val jvmCdsModeUseHint       = "Read pre-built archive from your specified path."
    override val jvmCdsArchivePathLabel  = "Archive path"

    override val jvmJitHeader        = "JIT compiler"
    override val jvmJitTieredHint    = "On = warm-up via interpreter then C1 then C2 (default). Off = C2 only, slower start."
    override val jvmJitCodeCacheHint = "Size of JIT-compiled code cache. JVM default is 240. Modded MC may benefit from 512+."

    override val jvmPerfHeader                  = "Performance & OS-level flags"
    override val jvmPerfAlwaysPreTouchHint      = "Touch every heap page at startup. Slower start, more consistent runtime."
    override val jvmPerfDisableExplicitGcHint   = "Make System.gc() a no-op. Some legacy mods abuse it. Almost always a win."
    override val jvmPerfUseLargePagesHint       = "Requires hugepages pre-allocated via sysctl. ~2-5% perf gain when set up."
    override val jvmPerfTransparentHugePagesHint = "Easier than UseLargePages. Adds latency spikes during defrag. Trade-off."
    override val jvmPerfNumaHint                = "NUMA-aware allocation. Only useful on multi-socket systems."
    override val jvmPerfHeapDumpHint            = "Write a heap dump on OOM. Crucial for diagnostics."
    override val jvmPerfExitOnOomHint           = "Exit on OOM instead of trying to limp along. Prevents zombie game state."

    override val jvmJfrHeader               = "Java Flight Recorder"
    override val jvmJfrIntro                = "Records JVM internals (allocations, GC, threads, locks). Open the resulting .jfr in JDK Mission Control or IntelliJ for analysis."
    override val jvmJfrEnableLabel          = "Enable JFR recording"
    override val jvmJfrEnableHint           = "Default settings = ~1% overhead. Profile settings = ~5%, captures method-level."
    override val jvmJfrDurationLabel        = "Duration (minutes)"
    override val jvmJfrSettingsHeader       = "Settings preset"
    override val jvmJfrSettingsDefaultHint  = "Low overhead, suitable for normal play."
    override val jvmJfrSettingsProfileHint  = "Method-level profiling. ~5% overhead."
    override val jvmJfrOutputPathLabel      = "Output .jfr path (optional)"

    override val jvmCustomHeader = "Custom passthrough"
    override val jvmCustomIntro  = "Extra flags appended verbatim. Use for one-off experiments or vendor-specific knobs not surfaced in the UI yet. Space-separated."
    override val jvmCustomLabel  = "Extra args"

    // --- Data dir migration UI ---
    override val migrationWelcome      = "Welcome to Nexira"
    override val migrationDescription  = "Nexira is now Nexira. Your existing data needs to be copied to the new location before the launcher can start. Your old folder is left untouched as a backup; you can delete it manually once everything works."
    override val migrationFromHeader   = "From"
    override val migrationToHeader     = "To"
    override fun migrationSize(megabytes: Int, files: Int) =
        "$megabytes MB across $files ${twoFormPlural(files, "file", "files")}"
    override val migrationStart        = "Migrate now"
    override val migrationInProgress   = "Migrating to Nexira"
    override fun migrationCurrentFile(file: String) = "Copying $file"
    override fun migrationProgressBytes(doneMb: Int, totalMb: Int) = "$doneMb MB of $totalMb MB"
    override val migrationCompletedTitle = "Migration complete"
    override val migrationCompletedBody  = "Restart Nexira to begin using your migrated data."
    override val migrationFailedTitle    = "Migration failed"
    override fun migrationFailedBody(error: String) = "Some files could not be copied: $error"
    override val migrationRetry = "Retry"
    override val migrationQuit  = "Quit Nexira"

    override val placeholderNotImplemented = "Not yet implemented..."
    override val placeholderHint           = "This screen is reserved while Atelier work lands."

    override val navLibrary = "Library"
    override val navBrowse  = "Browse"

    override val settingsHomeViewTitle   = "Home view"
    override val settingsHomeViewSub     = "The modern home is the default. The classic Dashboard and the Library-first surface stay one switch away."
    override val settingsHomeViewClassic = "Classic"
    override val settingsHomeViewLibrary = "Library (alpha)"
    override val settingsHomeViewNew     = "Modern"

    override val settingsUiStyleTitle    = "UI style"
    override val settingsUiStyleSub      = "Switch the form / surface / motion approach independently from the color palette. Celestia is the current rounded-glass look; Brut is hard-edged and flat."
    override val settingsUiStyleCelestia = "Celestia"
    override val settingsUiStyleBrut     = "Brut"

    // --- Left-rail selection style ---
    override val navSelectionTitle        = "Selected item style"
    override val navSelectionSub          = "How the active entry in the left rail is highlighted"
    override val navStylePill             = "Pill"
    override val navStyleSquare           = "Square"
    override val navStyleCircle           = "Circle"
    override val navStyleBar              = "Bar"
    override val navStyleDot              = "Dot"
    override val navStyleNone             = "None"
    override val navSelectionOutlineIcons = "Outline unselected icons"
    override val navSelectionAccent       = "Highlight color"
    override val navHoverHighlight         = "Highlight on hover"

    override val settingsCategoryAppearance   = "Appearance"
    override val settingsCategoryNetwork      = "Network"
    override val settingsCategorySmarty       = "Smarty"
    override val settingsCategoryAdvanced     = "Advanced"
    override val settingsCategoryDiagnostics  = "Diagnostics"
    override val settingsCategoryConsole      = "Console"
    override val consoleSecDisplay            = "Display"
    override val consoleSecColors             = "Severity colours"
    override val consoleSecFontSize           = "Font size"
    override val consoleSecWrap               = "Wrap lines"
    override val consoleSecGutter             = "Severity strip"
    override val consoleSecTimestamps         = "Timestamps"
    override val consoleSecBuffer             = "Line buffer"
    override val consoleSecColorInfo          = "Info"
    override val consoleSecColorWarn          = "Warn"
    override val consoleSecColorError         = "Error"
    override val consoleSecColorAuto          = "Auto"
    override val consoleSecApplyNote          = "Changes apply the next time the console is opened."
    override val consoleSecHighlightRules     = "Highlight rules"
    override val consoleSecFilterRules        = "Filter / mute"
    override val consoleSecAddRule            = "Add rule"
    override val consoleSecRulePattern        = "Pattern"
    override val consoleSecRegex              = "regex"
    override val consoleSecBold               = "Bold"
    override val consoleSecRulesEmpty         = "No rules yet."
    override val consoleSecArt                 = "Empty-console art"
    override val consoleSecArtAdd              = "Add art"
    override val consoleSecArtPaste            = "Paste ASCII or Braille art"
    override val consoleSecArtEmpty            = "No custom art yet."

    override val profileCategoryAccount = "Account"
    override val profileCategorySignIn      = "Sign in"
    override val profileCategorySecurity    = "Security"
    override val profileForgetSavedSignIn   = "Forget saved sign-in"
    override val profileSecurityHint        = "Your sign-in is saved on this device for auto-login."
    override val accountsTitle               = "Accounts"
    override val accountRemove               = "Remove"
    override val accountFaceLabel            = "Show as"
    override val accountFaceAuto             = "Auto"
    override val profileSignOutSmartycraft   = "Sign out of SmartyCraft"
    override val profileSignOutMicrosoft     = "Sign out of Microsoft"
    override val wardrobeTitle               = "Wardrobe"
    override val wardrobeSignedOut           = "Sign in to manage your skins and capes."
    override val wardrobeUpload               = "Upload"
    override val wardrobeApplySmartycraft     = "Apply (SmartyCraft)"
    override val wardrobeEmpty                = "Your library is empty. Upload a skin PNG to begin."
    override val wardrobeSaved               = "Saved"
    override val wardrobeCapes               = "Capes"
    override val wardrobeApplyCape           = "Set clan cape"
    override val wardrobeCapeClanHint        = "Capes are clan-wide -- only the clan leader can set one."
    override val wardrobeDefaults            = "Default skins"
    override val wardrobePoseStand           = "Standing"
    override val wardrobePoseWave            = "Wave"
    override val wardrobePoseSit             = "Sitting"
    override val wardrobePoseFaceCover       = "Hide face"
    override val wardrobePoseWalk            = "Walking"

    override val backgroundLoopMode      = "Loop"
    override val backgroundLoopUseCodec  = "Use codec"
    override val backgroundLoopForever   = "Forever"
    override val backgroundLoopOnce      = "Play once"

    override val customizationAccentClear     = "Clear override"
    override val customizationSectionVisual   = "Visual"
    override val customizationSectionColors   = "Color overrides"
    override val customizationHexInvalid      = "Invalid hex"
    override val themePickerAccentOverride    = "Accent override (live)"

    override val browseTitle             = "Browse"
    override val browseSearchPlaceholder = "Search packs"
    override val browseImport            = "Import file"
    override val libraryAddAction        = "Add pack"
    override val libraryNewLocalPack     = "New local pack"
    override val libraryImportPack       = "Import pack"
    override val createPackName          = "Name"
    override val createPackMc            = "Minecraft version"
    override val createPackLoader        = "Loader"
    override val createPackLoaderVersion = "Loader version (optional)"
    override val createPackConfirm       = "Create"
    override val createPackCancel        = "Cancel"
    override val createPackShowSnapshots = "Show snapshots"
    override val createPackHideSnapshots = "Hide snapshots"
    override val browseEmptyTitle        = "Catalog is empty"
    override val browseEmptyMessage      = "The mirror is reachable but has not published any packs yet. Check back later."
    override val browseErrorTitle        = "Mirror unreachable"
    override val browseErrorMessage      = "Could not reach the mirror. Check your connection and retry."
    override val browseRetry             = "Retry"
    override fun modrinthCategory(id: String) = when (id) {
        "adventure"    -> "Adventure"
        "challenging"  -> "Challenging"
        "combat"       -> "Combat"
        "kitchen-sink" -> "Kitchen Sink"
        "lightweight"  -> "Lightweight"
        "magic"        -> "Magic"
        "multiplayer"  -> "Multiplayer"
        "optimization" -> "Optimization"
        "quests"       -> "Quests"
        "technology"   -> "Technology"
        else           -> humanizeCategory(id)
    }

    override val browseDetailTabDescription = "Description"
    override val browseDetailTabGallery     = "Gallery"
    override val browseDetailErrorTitle    = "Could not load pack"
    override val browseDetailErrorMessage  = "Could not fetch the manifest. Check your connection and retry."
    override val browseDetailInstallButton = "Install"
    override val contentInstallRetry       = "Retry"
    override val contentInstallFailed      = "The download did not finish"
    override fun browseDetailAbout(mods: Int, assets: Int) =
        "This pack ships $mods ${twoFormPlural(mods, "mod", "mods")} and $assets ${twoFormPlural(assets, "asset", "assets")}."

    override fun browseDetailInstallProgress(filename: String, current: Int, total: Int) =
        "$filename  ($current / $total)"
    override val browseDetailInstallFailedGeneric = "Install failed for an unknown reason."

    override val fileBrowserNoRoot          = "This instance has no files on disk yet."
    override val fileBrowserPickAFile       = "Pick a file on the left to preview it."
    override val fileBrowserBinaryHint      = "Binary file -- preview not available."
    override val fileBrowserOpenExternally  = "Open externally"
    override fun fileBrowserTextTruncated(maxKb: Long) =
        "Preview truncated to the first $maxKb KB. Open externally to see the full file."
    override val fileBrowserEmptyFolder      = "(empty)"

    override val contentTabUnsupportedOrigin    = "Content view is only available for mirror-published packs today. Other sources will get parity in a follow-up."
    override val contentAddFiles                = "Add files"
    override val contentFindProjects            = "Find projects"
    override val contentSearchPlaceholder       = "Search content..."
    override val contentEmpty                   = "Nothing found"
    override val contentFilterAll               = "All"
    override val contentFilterMods              = "Mods"
    override val contentFilterResourcePacks     = "Resource packs"
    override val contentFilterShaderPacks       = "Shaders"
    override val contentFiltersTitle            = "Filters"
    override val contentFiltersReset            = "Reset"
    override fun contentFiltersShown(shown: Int, total: Int) = "Showing $shown of $total"
    override val contentFilterGroupCurated      = "Pack content"
    override val contentFilterGroupStatus       = "State"
    override val contentFilterGroupOwner        = "Added by"
    override val contentFilterAny               = "Any"
    override val contentFilterEnabled           = "On"
    override val contentFilterDisabled          = "Off"
    override val contentFilterOwnerPack         = "The pack"
    override val contentFilterOwnerUser         = "You"
    override val contentFilterOptionalOnly      = "Optional only"
    override val contentFilterOptionalOnlyHint  = "What the pack leaves up to you"
    override val contentDeleteTitle             = "Delete file?"
    override val contentDeleteBody              = "The file is removed from disk for good."
    override fun contentBulkDeleteBody(count: Int) = "$count files are removed from disk for good."
    override val selectionEnable                = "Enable"
    override val selectionDisable               = "Disable"
    override val selectionDelete                = "Delete"
    override fun selectionCount(count: Int)     = "$count selected"
    override val selectionClear                 = "Clear"
    override fun selectionBlockedByPack(count: Int) = "$count of these belong to the pack. Detach the instance to manage them."
    override val contentActionDetails           = "Details"
    override val contentActionOpenPage          = "Open page"
    override val contentDetailAuthors           = "Authors"
    override val contentDetailSize              = "Size"
    override val contentTabFetchErrorTitle      = "Could not load pack content"
    override val contentTabFetchErrorGeneric    = "The mirror manifest failed to load."
    override val contentTabRetry                = "Retry"
    override val contentTabRoleSection          = "Role slots"
    override fun contentTabOptionalSection(count: Int) = "Optional mods ($count)"
    override fun contentTabIncompatibleWith(name: String) = "Incompatible with $name"
    override fun contentTabModsSection(count: Int) = "Mods ($count)"
    override fun contentTabAssetsSection(count: Int) = "Assets ($count)"
    override val contentTabResolverIssuesTitle  = "Manifest issues detected"
    override fun contentTabResolverMissing(count: Int) = twoFormPlural(
        count,
        "$count dependency references a mod that is not in this pack.",
        "$count dependencies reference mods that are not in this pack.",
    )
    override fun contentTabResolverCycles(count: Int) = twoFormPlural(
        count,
        "$count dependency cycle found — pack author should re-check the requires graph.",
        "$count dependency cycles found — pack author should re-check the requires graph.",
    )
    override val contentTabRoleRecipeViewer     = "Recipe viewer"
    override val contentTabRoleMinimap          = "Minimap"
    override val contentTabRoleBlockInfo        = "Block info"
    override val contentTabRolePerformance      = "Performance"
    override val contentTabRoleInventorySearch  = "Inventory search"
    override fun contentTabRoleAltCount(count: Int) =
        if (count == 0) "single option" else "$count ${twoFormPlural(count, "alternative", "alternatives")}"
    override val contentTabRoleAlternativesHeader = "Alternatives in this pack"
    override val contentTabModNoDescription     = "No description in the manifest yet."
    override fun contentTabModLicensePrefix(license: String) = "License: $license"
    override val contentTabModUrlLabel          = "Mod page"
    override fun contentTabModSizeLabel(kb: Long) = "$kb KB"
    override fun contentTabModDependencies(count: Int) = "Dependencies ($count)"
    override fun contentTabModMissingCount(count: Int) = "$count missing"
    override val contentTabDepOptional          = "optional"
    override val contentTabDepMissing           = "missing"
    override val contentTabModOptional          = "optional"
    override fun contentTabLibrariesSection(count: Int)     = "Libraries ($count)"
    override fun contentTabResourcePacksSection(count: Int) = "Resource packs ($count)"
    override fun contentTabShaderPacksSection(count: Int)   = "Shader packs ($count)"
    override fun contentTabConfigsSection(count: Int)       = "Configs ($count)"
    override fun contentTabOtherAssetsSection(count: Int)   = "Other files ($count)"
    override fun contentTabAssetSizeLabel(kb: Long) = "$kb KB"
    override val contentTabAssetOptional        = "optional"
    override val contentTabAssetNoDescription   = "No description in the manifest yet."

    override fun worldsTabLocalSection(count: Int) = "Local worlds ($count)"
    override val worldsTabLocalEmpty            = "No saved worlds yet. Start a new singleplayer world from inside the game and it'll show up here."
    override fun worldsTabServersSection(count: Int) = "Servers from history ($count)"
    override val worldsTabServersEmpty          = "No servers in this instance's multiplayer history yet."
    override val worldsTabErrorTitle            = "Couldn't read worlds"
    override val worldsTabErrorMessage          = "Failed to read this instance's saves or server list. The files may be corrupt or unreadable."
    override fun worldsTabLastPlayed(rel: String) = "Last played: $rel"
    override val worldsTabServerHiddenLabel     = "hidden from the in-game list"
    override val worldsTabGameSurvival          = "Survival"
    override val worldsTabGameCreative          = "Creative"
    override val worldsTabGameAdventure         = "Adventure"
    override val worldsTabGameSpectator         = "Spectator"
    override val worldsTabGameUnknown           = "Unknown mode"
    override val worldsTabDimOverworld          = "Overworld"
    override val worldsTabDimNether             = "Nether"
    override val worldsTabDimEnd                = "End"
    override val worldsTabDimOther              = "Other"

    override val packDetailTabContent           = "Content"
    override val packDetailTabFiles             = "Files"
    override val packDetailTabWorlds            = "Worlds"
    override val packDetailTabLogs              = "Logs"
    override val packDetailTabSettings          = "Settings"
    override val packVersionSection             = "Version and updates"
    override val packVersionInstalled           = "Installed build"
    override val packVersionCheck               = "Check"
    override val packVersionUpToDate            = "On the latest build"
    override fun packVersionAvailable(version: String) = "Build $version available"
    override val packVersionSafe                = "Safe update"
    override val packVersionNeedsCare           = "Changes Minecraft or the loader — a snapshot is taken first"
    override val packVersionUpdateNow           = "Update now"
    override val packVersionFollowLatest        = "Follow latest"
    override val packVersionFollowLatestDesc    = "Auto-update this pack to the newest build."
    override fun packVersionLatestBuilt(version: String, publishedAt: String) = "Latest build: $version, published $publishedAt"
    override val packVersionSwitch              = "Switch"
    override val packVersionCurrentTag          = "Current"
    override val packVersionUpdateBadge         = "Update"
    override val packVersionRollbackBadge       = "Rollback"
    override fun packVersionRolledBack(version: String) = "The mirror rolled the pack back to $version"
    override val packVersionSwitchNow           = "Switch"
    override val packVersionCheckFailed         = "Couldn't check for updates"

    override val versionPickerInstallTitle      = "Install pack"
    override val versionPickerChangeTitle       = "Change pack version"
    override val versionPickerSearch            = "Find a version"
    override fun versionPickerCount(n: Int)     = if (n == 1) "1 version" else "$n versions"
    override val versionPickerEmpty             = "No version selected"
    override val versionPickerNoChangelog       = "This version has no changelog"
    override val versionPickerWarning           = "Changing the version rewrites the pack's files. A restore point is taken before it applies."
    override fun versionPickerInstall(version: String)  = "Install $version"
    override fun versionPickerUpgrade(version: String)  = "Update to $version"
    override fun versionPickerRollback(version: String) = "Roll back to $version"
    override fun versionPickerSwitch(version: String)   = "Switch to $version"

    override val packVersionsTitle              = "Pack versions"
    override val packVersionsAllVersions        = "All versions"
    override val packVersionsLatestTag          = "Latest"
    override fun packVersionsRebuilds(n: Int)   = "+$n ${twoFormPlural(n, "rebuild", "rebuilds")} with no changes"
    override val packVersionsChannelRelease     = "Release"
    override val packVersionsChannelBeta        = "Beta"
    override val packVersionsChannelAlpha       = "Alpha"
    override fun packVersionsCounts(mods: Int, assets: Int) =
        "$mods ${twoFormPlural(mods, "mod", "mods")}, $assets ${twoFormPlural(assets, "asset", "assets")}"
    override val packVersionsDiffVsPrevious     = "Vs previous build"
    override val packVersionsDiffVsInstalled    = "Vs installed"
    override val packVersionsIdentical          = "No file changes: a relabeled rebuild"
    override val packVersionsFirstBuild         = "The pack's first build, nothing to compare against"
    override val packVersionsNoDiffSource       = "This source does not list what a build contains until it is installed"
    override fun packVersionsAdded(n: Int)      = "Added ($n)"
    override fun packVersionsUpdated(n: Int)    = "Updated ($n)"
    override fun packVersionsRemoved(n: Int)    = "Removed ($n)"
    override val packVersionsSectionMods        = "Mods"
    override val packVersionsSectionAssets      = "Pack files"
    override val packVersionsSectionPack        = "Properties"
    override val packVersionsNotes              = "Release notes"
    override val packVersionsSwitchTo           = "Switch to this build"
    override val packVersionsConfirmTitle       = "Switch version?"
    override fun packVersionsConfirmBody(from: String, to: String) =
        "The instance moves from $from to $to. A restore point is taken first."
    override fun packVersionsPlanCounts(add: Int, update: Int, remove: Int) = "Changes: +$add, ~$update, -$remove"
    override fun packVersionsConflicts(n: Int) =
        "$n ${twoFormPlural(n, "conflict", "conflicts")} with your edits: the pack's files land beside them as .new"
    override fun packVersionsApplying(current: Int, total: Int, name: String) = "Applying $current/$total: $name"
    override fun packVersionsApplied(version: String) = "Done: now on build $version"
    override fun packVersionsFailed(reason: String) = "Failed: $reason"
    override val packVersionsRetry              = "Retry"
    override val packVersionsLoadError          = "Mirror unreachable, the version list did not load"

    override val packSettingsTitle              = "Pack settings"
    override val packSettingsClose              = "Close"
    override val packSettingsCategoryGeneral    = "General"
    override val packSettingsCategoryRuntime    = "Launch"
    override val packSettingsCategoryVersion    = "Version"
    override val packSettingsCategoryContent    = "Content"
    override val packSettingsCategoryData       = "Data"
    override val packSettingsIdentity           = "Identity"
    override val packSettingsName               = "Name"
    override val packSettingsNamePlaceholder    = "Pack name"
    override val packSettingsNotes              = "Notes"
    override val packSettingsNotesPlaceholder   = "Notes to yourself"
    override val packSettingsSource             = "Source"
    override fun packSettingsForkedFrom(name: String) = "Forked from $name"
    override val packSettingsPackId             = "Pack ID"
    override val packSettingsMemory             = "Memory"
    override val packSettingsEnvironment        = "Environment"
    override val packSettingsJava               = "Java"
    override fun packSettingsJavaManaged(major: Int) = "Managed -- Java $major"
    override val packSettingsJavaCustom         = "Custom Java path"
    override val packSettingsJavaPathPlaceholder = "/path/to/bin/java"
    override val packSettingsJavaReset          = "Use managed"
    override val packSettingsJvmArgs            = "JVM arguments"
    override val packSettingsJvmArgsDefault     = "Default"
    override val packSettingsJvmArgsEdit        = "Edit"
    override val packSettingsWindow             = "Game window"
    override val packSettingsWindowOverride     = "Custom window size"
    override val packSettingsWindowOverrideDesc = "Otherwise the client keeps its own remembered size"
    override val packSettingsWidth              = "Width"
    override val packSettingsHeight             = "Height"
    override val packSettingsFullscreen         = "Fullscreen"
    override val packSettingsOptional           = "Optional content"
    override val packSettingsOptionalNone       = "This pack has no options"
    override val packContentPresenceClient      = "Client only"
    override val packContentPresenceServer      = "Server only"
    override val packContentPresenceBoth        = "Client and server"
    override val packContentPresenceCoremod     = "Coremod"
    override val packSettingsDependencies       = "Dependencies"
    override val packSettingsDependenciesNone   = "Nothing missing"
    override fun packSettingsMissing(name: String) = "Missing: $name"
    override val packSettingsContentUnavailable = "Content list is unavailable without a manifest"
    override val packSettingsContentLoading     = "Loading"
    override val packSettingsStorage            = "Location"
    override val packSettingsFolder             = "Pack folder"
    override val packSettingsOpenFolder         = "Open"
    override val packSettingsSizeComputing      = "computing size"
    override val packSettingsDetach             = "Detach to local"
    override val packSettingsDetachDesc         = "Become your own copy; provenance is kept"
    override val packSettingsDetachAction       = "Detach"
    override val packSettingsRepair             = "Verify and repair files"
    override val packSettingsRepairDesc         = "Check every file and restore only what is damaged"
    override val packSettingsRepairAction       = "Repair"

    override val packBusyRunningTitle           = "This pack is running"
    override val packBusyRunningBody            = "Changing its files now rewrites mods and configs the game already has open. Expect the session to end badly -- a crash, or a world saved half-way. Close the game first if you can."
    override val packBusyRunningConfirm         = "Do it anyway"
    override fun packSettingsRepairDone(checked: Int, repaired: Int) =
        if (repaired == 0) "Checked $checked files, all intact" else "Checked $checked files, restored $repaired"
    override fun packSettingsRepairProgress(current: Int, total: Int, name: String) = "Checking $current/$total: $name"
    override val packSettingsDangerZone         = "Danger zone"
    override val packSettingsDelete             = "Delete pack"
    override val packSettingsDeleteDesc         = "The instance files are erased for good"
    override val packVersionSnapshots           = "Restore points"
    override val packVersionRestore             = "Restore"
    override val packVersionSnapshotsHint       = "A snapshot keeps your own edits; one is taken before a structural update"
    override val consoleSessionLive             = "General"
    override fun consoleSessionPickerLabel(current: String) = "Log: $current"

    override val packDetailReadyTitle           = "Ready to play"
    override fun packDetailInstanceDirHint(dirName: String) = "Instance folder: instances/$dirName"
    override val packDetailPlay                 = "Play"
    override val packDetailPlayLoginRequired    = "Sign in to play"
    override val packPlayWait                   = "Please wait"
    override val packPlayExit                   = "Exit"
    override val packDetailNotFoundTitle        = "Instance not found"
    override val packDetailNotFoundHint         = "It may have been removed from another window."
    override val packDetailNotFoundBack         = "Back to Library"

    // --- Notification subsystem ---
    override val notificationExpandHistory   = "Expand notification history"
    override val notificationCollapseHistory = "Collapse notification history"
    override val notificationDismiss         = "Dismiss notification"
    override val notifHistoryEmpty           = "No messages yet"
    override val notifHistoryClear           = "Clear"
    override val notifDoNotDisturb           = "Do not disturb"
    override fun notifGroupCount(count: Int) = "×$count"
    override fun notifCountTitle(count: Int) = if (count == 1) "$count message" else "$count messages"
    override fun notificationShowMore(count: Int)               = "+$count more"
    override fun notificationAbsoluteTime(instant: java.time.Instant): String =
        notificationTimeFormatter(java.util.Locale.ENGLISH).format(instant)

    override fun notifPackPreparing(packName: String)   = "Preparing $packName"
    override fun notifPackStage(stage: String)          = "Stage: $stage"
    override fun notifPackSyncing(packName: String)     = "Syncing $packName"
    override fun notifPackSyncBody(current: Int, total: Int, pctLabel: String) =
        "$current/$total files, $pctLabel"
    override val notifPackSyncIndeterminate             = "downloading..."
    override fun notifPackSyncPercent(pct: Int)         = "$pct%"
    override fun notifPackRunning(packName: String)     = "$packName is running"
    override fun notifPackFailed(packName: String)      = "$packName failed to launch"
    override fun notifPackSessionEnded(packName: String) = "$packName session ended"
    override fun notifInstallSyncing(packName: String)  = "Installing $packName"
    override fun notifInstallDone(packName: String)     = "$packName installed"
    override fun notifPackUpdatePending(packName: String, version: String) = "$packName: build $version is available"
    override fun notifPackUpdated(packName: String, version: String) = "$packName updated to $version"
    override fun notifPackUpdateFailed(packName: String) = "$packName: update failed"
    override val notifActionOpenVersions                = "Open versions"
    override fun notifInstallFailed(packName: String)   = "$packName failed to install"
    override fun notifInstallCancelled(packName: String) = "$packName install cancelled"
    override val editorSurfOverlay                      = "Floating layer"
    override val editorSurfShortOverlay                 = "Floating"

    override val activityPillExpand                     = "Show all"
    override fun activityPillMore(count: Int)           = "+$count"

    override val activityPillDismiss                    = "Dismiss"
    override val activityPillCancel                     = "Cancel"
    override val activityPillPause                      = "Pause"
    override fun activityPillMeasure(done: Long, total: Long) = "$done of $total"

    override val notifActionCancel                      = "Cancel"
    override val notifActionShowConsole                 = "Show console"
    override val notifActionStop                        = "Stop"
    override val notifActionPlayOffline                 = "Play offline"
    override fun notifReasonExitCode(code: Int)         = "Game exited with code $code"
    override val notifReasonInternal                    = "Internal error"
    override fun notifReasonInternalDetail(detail: String) = detail
    override val notifReasonAuthFail                    = "Authentication failed"
    override fun notifReasonAuthFailDetail(detail: String) = detail
    override val notifReasonOfflineNoClient             = "Pack files missing on disk"
    override val notifReasonOfflineNoManifest           = "No cached manifest; go online once to sync"
    override val notifReasonTwoFactorExpired            = "Sign in again to refresh credentials"
    override val notifSessionStaleTitle                 = "Session was not refreshed"
    override val notifSessionStaleRejected              = "The auth server refused the sign-in. The game starts on the old session, but joining the server will most likely fail: sign in again."
    override val notifSessionStaleUnreachable           = "The auth server could not be reached. The game starts on the old session; if the server turns you away, try again later."
    override val notifSessionStaleUnknown               = "The session could not be refreshed. The game starts on the old session."
    override val notifSessionStaleNoPassword            = "No saved password, so the session is never refreshed. Once it expires the server stops letting you in: sign in again."
    override fun notifForeignContentRemovedTitle(count: Int) = "Removed $count file(s) not in the pack"
    override val notifInstanceUnverifiedTitle           = "Pack contents not verified"
    override val notifInstanceUnverifiedBody            = "Nothing on disk says what this pack consists of, so the game starts without a sign-in and cannot join a server. Sync the pack (Settings -> Verify and repair) and launch again."
    override fun notifReasonMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY -> "Sign in to SmartyCraft to play this pack"
        else                                          -> "Sign in with '$providerKey' to play this pack"
    }

    override val notifTimeNow                           = "Now"
    override fun notifTimeSeconds(seconds: Long)        = "${seconds}s"
    override fun notifTimeMinutes(minutes: Long)        = "${minutes}m"
    override fun notifTimeHours(hours: Long)            = "${hours}h"
    override fun notifTimeDays(days: Long)              = "${days}d"

    // --- Home (new) + launch tiles ---
    override val homeRecentTitle    = "Your packs"
    override val homeNoPacksTitle   = "No packs yet"
    override val homeNoPacksBody    = "Install something from Browse and your packs will show up here."
    override val browseOpen         = "Open Browse"
    override val homeQuickContinue  = "Continue"
    override val homeQuickStart     = "Launch"
    override val homeQuickButton    = "Play"
    override fun homeHeroPlaytime(hours: Long) = "$hours h played"
    override val launchTileReady    = "Launch"
    override val launchTileBlocked  = "Can't play yet"

    // --- Library widgets ---
    override val libraryEmptyTitle     = "Empty for now"
    override val libraryEmptyBody      = "Install a pack from Browse and it'll show up here."
    override val libraryHeaderTitle    = "Library"
    override val libraryHeaderSubtitle = "Installed packs"

    // --- Customization widget labels ---

    // --- Layout editor: common actions ---
    override val editorClose   = "Close"
    override val editorEnterLayout          = "Edit layout"
    override val editorCancel  = "Cancel"
    override val editorDelete  = "Delete"
    override val editorReset   = "Reset"
    override val editorUnsupportedWidget = "Unsupported widget"
    override val editorResetAll = "Reset everything"
    override val editorToFront = "Bring to front"
    override val editorToBack = "Send to back"
    override val widgetLabels: Map<String, String> = mapOf(
        "widget.about.credits" to "Credits and tech",
        "widget.about.credits.title" to "Heading (credits)",
        "widget.about.links.card" to "Links",
        "widget.about.links.card.title" to "Heading",
        "widget.about.logo" to "Logo and version",
        "widget.about.logo.title" to "Heading",
        "widget.about.logo.showVersion" to "Show version",
        "widget.about.logo.showBuildDate" to "Show build date",
        "widget.about.logo.showTagline" to "Show tagline",
        "widget.about.system.card" to "System",
        "widget.about.system.card.title" to "Heading",
        "widget.about.update.panel" to "Updates",
        "widget.about.update.panel.title" to "Heading",
        "widget.appshell.region.center" to "Main content",
        "widget.appshell.region.collapsed" to "Collapsed",
        "widget.appshell.region.swipeToCollapse" to "Swipe to collapse",
        "widget.appshell.region.frostTier" to "Frost",
        "widget.appshell.region.glassAlphaPct" to "Glass, %",
        "widget.appshell.region.left" to "Left rail",
        "widget.appshell.region.top" to "Title bar",
        "widget.appshell.region.body" to "Main area",
        "widget.appshell.topbar.breadcrumb" to "Breadcrumb",
        "widget.appshell.topbar.heightDp" to "Height",
        "widget.appshell.topbar.cornerStyle" to "Corner style",
        "widget.appshell.topbar.groupStyle" to "Grouping",
        "widget.appshell.topbar.frostTier" to "Frost",
        "widget.appshell.topbar.controls" to "Window controls",
        "widget.appshell.region.right" to "Right panel",
        "widget.appshell.region.showDivider" to "Divider",
        "widget.appshell.region.widthDp" to "Width (0 = flexible)",
        "widget.appshell.rightrail.compactnews" to "News feed",
        "widget.appshell.rightrail.compactnews.maxItems" to "Max items (0 = all)",
        "widget.appshell.rightrail.compactnews.showTitle" to "Show title",
        "widget.appshell.rightrail.compactnews.imageSource" to "Image source",
        "widget.bg.enable.toggle" to "Background on/off",
        "widget.bg.fx.animspeed" to "Animation speed",
        "widget.bg.fx.blur" to "Blur",
        "widget.bg.fx.darken" to "Darken",
        "widget.bg.fx.opacity" to "Opacity",
        "widget.bg.fx.parallax" to "Parallax",
        "widget.bg.fx.saturation" to "Saturation",
        "widget.bg.fx.vignette" to "Vignette",
        "widget.bg.image.picker" to "Background image",
        "widget.bg.loop.mode" to "Playback loop",
        "widget.bg.position.x" to "Position X",
        "widget.bg.position.y" to "Position Y",
        "widget.bg.preview" to "Preview",
        "widget.bg.reset" to "Reset background",
        "widget.bg.scale.mode" to "Scaling",
        "widget.bg.tint" to "Tint",
        "widget.container.group" to "Group",
        "widget.checklist" to "Checklist",
        "widget.checklist.add" to "Add item...",
        "widget.checklist.empty" to "No items yet",
        "widget.checklist.hideCompleted" to "Hide completed",
        "widget.checklist.title" to "Title",
        "widget.container.tabs" to "Tabs",
        "widget.container.tabs.label1" to "Tab 1",
        "widget.container.tabs.label2" to "Tab 2",
        "widget.container.tabs.label3" to "Tab 3",
        "widget.container.tabs.tabCount" to "Tabs",
        "widget.home.classic.content" to "Classic dashboard",
        "widget.home.new.clock" to "Clock",
        "widget.home.new.clock.accent" to "Accent color",
        "widget.home.new.clock.faceSize" to "Dial size",
        "widget.home.new.clock.format24h" to "24-hour format",
        "widget.home.new.clock.mode" to "Mode",
        "widget.home.new.clock.showSeconds" to "Seconds",
        "widget.home.new.clock.title" to "Heading",
        "widget.home.new.hero" to "Pack hero card",
        "widget.home.new.hero.height" to "Height",
        "widget.home.new.hero.showMeta" to "Metadata",
        "widget.home.new.launchbutton" to "Launch button",
        "widget.home.new.launchbutton.label" to "Label",
        "widget.home.new.music" to "Music player",
        "widget.home.new.music.title" to "Heading",
        "widget.home.new.playback.mini" to "Mini player",
        "widget.home.new.progress" to "Background activity",
        "widget.home.new.progress.idleText" to "Idle text",
        "widget.home.new.progress.title" to "Heading",
        "widget.home.new.quicklaunch" to "Quick launch",
        "widget.home.new.quicklaunch.buttonLabel" to "Button label",
        "widget.home.new.recent" to "Pack tiles",
        "widget.home.new.recent.maxTiles" to "Tile count",
        "widget.home.new.recent.title" to "Heading",
        "widget.home.new.spacer" to "Spacer",
        "widget.home.new.spacer.height" to "Height",
        "widget.home.new.video" to "Video player",
        "widget.home.new.video.url" to "Video URL",
        "widget.home.new.welcome" to "Welcome banner",
        "widget.home.new.welcome.customGreeting" to "Custom greeting text",
        "widget.home.new.welcome.showSubtitle" to "Show subtitle",
        "widget.library.body" to "Library body",
        "widget.library.body.emptyText" to "Empty-state text",
        "widget.library.body.emptyTitle" to "Empty-state title",
        "widget.library.header" to "Library header",
        "widget.library.header.subtitle" to "Subtitle",
        "widget.library.header.title" to "Heading",
        "widget.library.header.show" to "Show header",
        "widget.nav.entry" to "Nav item",
        "widget.notes.scratch" to "Notes",
        "widget.notes.scratch.placeholder" to "Write something...",
        "widget.notes.scratch.title" to "Title",
        "widget.notifications.history" to "Message history",
        "widget.activity.pill" to "Activity",
        "widget.activity.pill.progress" to "Measure",
        "widget.activity.pill.anchor" to "Anchor",
        "widget.activity.pill.heightDp" to "Height",
        "widget.activity.pill.showActions" to "Show controls",
        "widget.notifications.history.expandUp" to "Expand upward",
        "widget.notifications.history.clock12h" to "12-hour clock (am/pm)",
        "widget.notifications.history.verticalTime" to "Stacked time",
        "widget.profile.account.section" to "SmartyCraft",
        "widget.profile.signin" to "Microsoft",
        "widget.profile.nav" to "Profile navigation",
        "widget.profile.skin.section" to "Skin",
        "widget.profile.skin.section.previewHeight" to "Preview height",
        "widget.server.details.banner" to "Server banner",
        "widget.server.details.banner.cornerRadius" to "Corner rounding",
        "widget.server.details.description" to "Server description",
        "widget.server.details.tagbar" to "Server tags",
        "widget.server.details.title" to "Server title",
        "widget.theme.picker.grid" to "Theme grid",
        "widget.theme.picker.preview" to "Theme preview",
    )
    override val recoverySafeModeTitle = "Can't recover the interface"
    override val recoverySafeModeBody  = "The interface crashed several times in a row. A crash report was saved to disk. Restart the launcher."
    override val recoverySafeModeQuit  = "Quit"

    override val recoveryTitle              = "Recovery mode"
    override val recoveryBody               = "Disable a module or reset a corrupted state, then continue. Changes apply when the launcher restarts."
    override val recoveryModulesHeading     = "Disable modules"
    override val recoveryModuleTray         = "System tray"
    override val recoveryModuleNotify       = "Notifications"
    override val recoveryModuleSkinema      = "Media backgrounds"
    override val recoveryModuleKeyring      = "System keyring"
    override val recoveryResetsHeading      = "Reset"
    override val recoveryResetLayout        = "Layout"
    override val recoveryResetCustomization = "Customization"
    override val recoveryResetSettings      = "Settings"
    override val recoveryContinue           = "Continue to normal boot"
    override val recoveryRelaunchFailed     = "Couldn't restart automatically. Reopen the launcher."
    override val recoveryRestartInApp       = "Restart in recovery mode"
    override val thresholdStageFiles     = "checking files"
    override val thresholdStageNetwork   = "network state"
    override val thresholdStageMigration = "migration check"
    override val thresholdStageModules   = "starting modules"
    override val thresholdErrorTitle     = "boot failed"
    override val thresholdOpenLogs       = "open logs folder"
    override val thresholdQuit           = "quit"
    override val recoveryReloadedNotice = "Interface reloaded after an error"
    override val editorSave    = "Save"
    override val editorApply   = "Apply"
    override val editorExport  = "Export"
    override val editorWidgets = "Widgets"

    // --- Layout editor: slot orientation ---
    override val editorSlotStack  = "Stack"
    override val editorSlotRow    = "Row"
    override val editorSlotGrid   = "Grid"
    override val editorSlotCanvas = "Canvas"
    override val editorSlotCubeGrid = "Cube grid"
    override val editorSlotLayoutMenuTitle     = "Layout"
    override val editorSlotGridColumns         = "Columns"
    override val editorSlotGridColumnsDecrease = "Fewer columns"
    override val editorSlotGridColumnsIncrease = "More columns"
    override val editorSlotLayoutHandle        = "Slot layout"

    // --- Layout editor: prop panel ---
    override val editorResetToDefault = "Reset to default"
    override val editorBackingTitle   = "Backing"
    override val editorSurfaceSettings = "Settings"
    override val editorBackingGlass   = "Glass opacity"
    override val editorBackingCorner  = "Corner"
    override val editorBackingPadding = "Padding (all sides)"
    override val editorBackingPaddingTop    = "Padding top"
    override val editorBackingPaddingEnd    = "Padding right"
    override val editorBackingPaddingBottom = "Padding bottom"
    override val editorBackingPaddingStart  = "Padding left"
    override val editorBackingNoGlassHint   = "No glass means no visible backing. Corner and padding still apply to the widget."

    // --- Layout editor: presets ---
    override val editorPresetsTitle          = "Presets"
    override val editorPresetsIntro          = "A snapshot of layout, theme and style. Save now, load anytime."
    override val editorPresetNamePlaceholder = "Preset name..."
    override fun editorPresetsSaved(count: Int) = "Saved ($count)"
    override val editorPresetsEmpty          = "Empty. Save the current layout as your first preset."

    // --- Layout editor: palette ---
    override val editorPaletteHide  = "Hide palette"
    override val editorPaletteHint  = "Drag into a slot"
    override val editorPaletteEmpty = "Widget registry is empty (build issue)."
    override val editorPaletteSearch = "Search widgets…"
    override val editorPaletteNoMatch = "No matches"

    // --- Layout editor: empty slot + chrome ---
    override val editorDragWidgetHere   = "Drag a widget here"
    override val editorDragReorder      = "Drag to reorder"
    override val editorConfigure        = "Configure"
    override val editorForceRemove      = "Force-remove"
    override val editorForceRemoveTitle = "Force-remove widget?"
    override fun editorForceRemoveBody(name: String) =
        "\"$name\" is marked as non-removable. Such widgets usually stay put so you are not left without navigation. If you are sure it is not needed here, you can remove it now. If anything goes wrong, reset the surface to default from the menu next to the surface chip."

    // --- Layout editor: host (reset / pill / fab) ---
    override val editorResetSurfaceTitle = "Reset surface to default?"
    override fun editorResetSurfaceBody(name: String) =
        "\"$name\" will return to the widget arrangement from the built-in default layout. All local changes on this surface (added widgets, reorders, deletions) will be lost. Other surfaces are left untouched."
    override val editorPreview           = "Preview"
    override val editorPreviewHidden     = "Hidden"
    override val editorPaletteToggleHide = "Hide"
    override val editorEscHint           = "Esc to exit"
    override val editorFabEdit           = "Edit layout"
    override val editorFabDone           = "Done editing"

    // --- Layout editor: surface short names ---
    override val editorSurfShortHome      = "Home"
    override val editorSurfShortLibrary   = "Library"
    override val editorSurfShortLeftRail  = "Left rail"
    override val editorSurfShortRightRail = "Right rail"
    override val editorSurfShortAbout     = "About"
    override val editorSurfShortBg        = "Background"
    override val editorSurfShortProfile   = "Profile"
    override val editorSurfShortServer    = "Server"
    override val editorSurfShortTheme     = "Themes"
    override val editorSurfShortShell     = "Shell"
    override val editorSurfShortTopBar    = "Top"
    override val editorSurfShortBody      = "Main"

    // --- Layout editor: surface long names ---
    override val editorSurfHomeClassic = "Home (classic)"
    override val editorSurfHomeNew     = "Home (new)"
    override val editorSurfLibrary     = "Library"
    override val editorSurfLeftRail    = "Side panel"
    override val editorSurfRightRail   = "Right panel"
    override val editorSurfAbout       = "About"
    override val editorSurfBg          = "Background settings"
    override val editorSurfProfile     = "Profile"
    override val editorSurfServer      = "Server details"
    override val editorSurfTheme       = "Theme picker"
    override val editorSurfShell        = "App shell"
    override val editorSurfTopBar       = "Top bar"
    override val editorSurfBody         = "Main area"

    // --- Music player widgets ---
    override val musicPlayerTitle      = "Music player"
    override val audioPlay             = "Play"
    override val audioPause            = "Pause"
    override val audioStop             = "Stop"
    override val audioOpenFile         = "Open file"
    override val audioPickTrack        = "Pick a track"
    override val audioVolume           = "Volume"
    override val audioNoFile           = "No file"
    override val audioStatusReady      = "Ready"
    override val audioStatusPlaying    = "Playing"
    override val audioStatusPaused     = "Paused"
    override val audioFormatHint       = "MP3, FLAC, OGG, WAV and more."
    override val audioNoPlayerHere     = "No player on this layout"
    override val audioAddMusicPlayer   = "Add a Music player"
    override val audioErrorUnsupported = "Unsupported or unreadable file."
    override val audioErrorOpenFailed  = "Could not open the file"
    override val audioErrorDeviceBusy  = "Audio device is busy"
    override val audioErrorPlaybackFailed = "Playback failed"

    // --- Video player ---
    override val videoFullscreen     = "Fullscreen"
    override val videoExitFullscreen = "Exit fullscreen"
    override val videoMute           = "Mute"
    override val videoUnmute         = "Unmute"
    override val videoReplay         = "Replay"
    override val videoError          = "Could not play this video"
    override val videoLoading        = "Loading video…"
    override val videoOpenInBrowser  = "Open in browser"
    override val videoSkipBack        = "Back 10 seconds"
    override val videoSkipForward     = "Forward 10 seconds"
    override val videoWidgetEmpty     = "Set a video URL in widget settings"
    override val readOnlyDataTitle    = "Changes made now will not be kept"
    override fun readOnlyDataBody(stores: String) =
        "Written by a newer build of the launcher: $stores. Open read-only — this session cannot write it back, " +
            "so anything you change is lost when the launcher closes. Update to edit it again."
    override val readOnlyDataLibrary  = "the pack library"
    override val readOnlyDataLayout   = "the layout"
    override val videoFetchingTool    = "Fetching the downloader"
    override val videoResolvingPage   = "Reading the page"
    override val videoDownloading     = "Downloading"
    override val videoCancelDownload  = "Stop the download"
    override val videoCancelled       = "Download stopped"
    override val videoRetry           = "Try again"

    // --- Library pack card ---
    override val packCardPlay          = "Play"
    override val packCardSettings      = "Settings"
    override val packCardMore          = "More"
    override val packCardDeleteTitle   = "Delete instance?"
    override val packCardDeleteBody    = "The instance and all its files (worlds, settings, mods) are removed for good. This can't be undone."
    override val packCardNeverPlayed   = "Never played"
    override val packCardPlayedJustNow = "just now"
    override fun packCardPlayedMinutesAgo(n: Long) = "$n min ago"
    override fun packCardPlayedHoursAgo(n: Long)   = "$n h ago"
    override fun packCardPlayedDaysAgo(n: Long)    = "$n d ago"
    override val packCardPlayedLongAgo = "long ago"

    // --- Session chip + about logo a11y ---
    override val sessionsActiveTitle = "Active sessions"
    override val aboutLogoDesc       = "App logo"
}

private val notificationTimeFormatterCache = ConcurrentHashMap<Locale, DateTimeFormatter>()
private fun notificationTimeFormatter(locale: Locale): DateTimeFormatter =
    notificationTimeFormatterCache.computeIfAbsent(locale) {
        java.time.format.DateTimeFormatter
            .ofPattern("d MMM yyyy, HH:mm:ss", it)
            .withZone(java.time.ZoneId.systemDefault())
    }
