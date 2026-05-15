package hivens.ui.i18n

object EnglishStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"

    // Login
    override val loginTitle        = "Aura Launcher"
    override val loginUsername     = "Username"
    override val loginPassword     = "Password"
    override val loginRemember     = "Remember password"
    override val loginButton       = "LOG IN"
    override val loginErrorEmpty   = "Enter your username and password"
    override val loginErrorGeneric = "Login error"
    override val loginRegister     = "Create an account"

    // Navigation
    override val navLogout   = "Log out"
    override val navBack     = "Back"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WELCOME BACK, $name"
    override val dashboardServers              = "AVAILABLE SERVERS"
    override val dashboardServersEmpty         = "No servers found"
    override val dashboardLoginRequiredTitle   = "Sign in to see servers"
    override val dashboardLoginRequiredHint    = "Use the panel on the right to log in. The server list lives behind authentication on SMARTYcraft."

    // Launch Control
    override val launchReady       = "Ready to play"
    override val launchButton      = "PLAY"
    override val launchAbort       = "CANCEL"
    override val launchRunning     = "Game running"
    override val launchResetError  = "CLEAR ERROR"
    override val launchDownloading = "Downloading:"

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
    override fun authSuccess(uuid: String) = "Login successful. UUID: $uuid"

    // Profile
    override val profileTitle              = "PROFILE"
    override val profileStatusLabel        = "Status"
    override val profileStatusOnline       = "Authenticated"
    override val profileStatusOffline      = "Offline"
    override val profileBalance            = "Balance"
    override val profileTopUp              = "Top up balance"
    override val profileUploadSkin         = "Upload skin"
    override val profileUploadSkinLoading  = "Uploading..."
    override val profileSkinFront          = "Front"
    override val profileSkinBack           = "Back"
    override val profileSkinLoading        = "Loading skin..."
    override val profileRefresh            = "Refresh"
    override val profileUploadSuccess      = "Skin uploaded successfully"
    override fun profileUploadError(msg: String) = "Upload error: $msg"

    // Settings
    override val settingsTitle              = "GLOBAL SETTINGS"
    override val settingsSectionUI          = "Interface"
    override val settingsSectionBehavior    = "Behavior"
    override val settingsThemePicker        = "Choose theme"
    override val settingsThemePickerSub     = "Customize the color scheme"
    override val settingsDarkTheme          = "Dark theme"
    override val settingsCloseAfterLaunch   = "Hide launcher to tray after server starts"
    override val settingsSaved              = "Settings saved"
    override val settingsLanguage           = "Language"

    // Theme Picker
    override val themePickerTitle           = "CHOOSE THEME"
    override val themePickerApply           = "APPLY"
    override val themePickerPreview         = "PREVIEW"
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
    override val newsTitle   = "PROJECT NEWS"
    override val newsEmpty   = "No news yet..."

    // Server Detail
    override val serverDetailTitle         = "SERVER INFORMATION"
    override val serverDetailNoImage       = "No image"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Information missing"
    override fun serverDetailMissingPath(path: String, file: String) = "Create $file in:"

    // Server Settings
    override val serverSettingsSubtitle        = "Launch settings"
    override val serverSettingsSectionSystem   = "SYSTEM"
    override val serverSettingsSectionMods     = "MODIFICATIONS"
    override val serverSettingsRam             = "RAM"
    override fun serverSettingsRamValue(mb: Int) = "RAM: $mb MB"
    override val serverSettingsJava            = "Java version"
    override fun serverSettingsJavaAuto(version: String) = "Automatic ($version)"
    override val serverSettingsJavaHint        = "Leave empty to use bundled Java"
    override val serverSettingsOpenFolder      = "Open folder"
    override val serverSettingsReset           = "Reset client"
    override val serverSettingsNoMods          = "No optional mods"
    override val serverSettingsPickJava        = "Select Java"

    // Update
    override val updateTitle           = "Update available"
    override val updateTitleCritical   = "CRITICAL UPDATE"
    override val updateTitleMandatory  = "MANDATORY UPDATE"
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
    override val updateDownloadNow     = "DOWNLOAD NOW"
    override val updateDownloading     = "Downloading..."
    override val updateInstall         = "Install and restart"
    override val updateRetry           = "Retry"
    override val updateErrorTitle      = "Download error"
    override val updateErrorUnknown    = "Unknown error"
    override val updateScheduleFailed  = "Failed to schedule update"
    override fun updateVersion(version: String) = "Version $version"
    override val updateDetails         = "Details"

    // Console
    override val consoleTitle = "Debug Console"
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Game Output ($filtered/$total)"
    override val consoleCopyAll = "Copy All"
    override val consoleClear   = "Clear"
    override val consoleWrap    = "Wrap"
    override val consoleSaveToFile = "Save to file"
    override val consoleSearchPlaceholder = "Search…"
    override val consoleJumpToBottom = "↓ Jump to bottom"

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

    // File Manager
    override fun fileDownloading(n: Int) = "Downloading updates ($n files)..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Offline mode"
    override val settingsOfflineModeDesc   = "Launch without authentication. Files won't be synced."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Offline mode — authentication skipped"
    override val stateOfflineSkipSync      = "Offline mode — file sync skipped, using local files"
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
    override val backgroundTitle          = "CUSTOM BACKGROUND"
    override val backgroundSubtitle       = "Customize the launcher wallpaper"
    override val backgroundEnable         = "Enable"
    override val backgroundSectionImage   = "IMAGE"
    override val backgroundPickFile       = "Choose a background image"
    override val backgroundPickButton     = "Choose file"
    override val backgroundSectionScale   = "SCALING"
    override val backgroundScaleCover     = "Cover"
    override val backgroundScaleContain   = "Contain"
    override val backgroundScaleStretch   = "Stretch"
    override val backgroundScaleOriginal  = "Original"
    override val backgroundScaleTile      = "Tile"
    override val backgroundSectionPosition = "POSITION"
    override val backgroundAlignX         = "Horizontal"
    override val backgroundAlignY         = "Vertical"
    override val backgroundSectionEffects = "EFFECTS"
    override val backgroundBlur           = "Blur"
    override val backgroundDarken         = "Darken"
    override val backgroundOpacity        = "Opacity"
    override val backgroundSaturation     = "Saturation"
    override val backgroundParallax       = "Parallax"
    override val backgroundVignette       = "Vignette"
    override val backgroundSectionTint    = "COLOR TINT"
    override val backgroundTintNone       = "None"
    override val backgroundTintNavy       = "Dark blue"
    override val backgroundTintViolet     = "Violet"
    override val backgroundTintEmerald    = "Emerald"
    override val backgroundTintBordeaux   = "Bordeaux"
    override val backgroundTintSteel      = "Steel"
    override val backgroundTintIntensity  = "Intensity"
    override val backgroundReset          = "Reset to defaults"
    override val backgroundPreview        = "PREVIEW"
    override val backgroundPreviewServer  = "Example server"
    override val settingsBackground       = "Custom background"
    override val settingsBackgroundSub    = "Photo or GIF as launcher wallpaper"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "ABOUT"
    override fun aboutDescription(branding: String) = "Unofficial launcher for $branding"
    override fun aboutBuildDate(date: String) = "Built: $date"
    override val aboutSectionCreator       = "CREATOR"
    override val aboutSectionTechnologies  = "TECHNOLOGIES"
    override val aboutSectionLicense       = "LICENSE"
    override val aboutLicenseText          = "GPLv3 — Free and open source software"
    override val aboutSectionUpdates       = "UPDATES"
    override val aboutCurrentVersion       = "Current version"
    override val aboutCheckUpdates         = "Check for updates"
    override val aboutChecking             = "Checking..."
    override val aboutUpToDate             = "You're up to date!"
    override val aboutCheckAgain           = "Check again"
    override fun aboutUpdateAvailable(version: String) = "Version $version available"
    override val aboutCriticalUpdate       = "Critical update"
    override val aboutSectionSystem        = "SYSTEM"
    override val aboutOs                   = "OS"
    override val aboutSectionLinks         = "LINKS"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Report a bug"
    override val aboutLinkReleases         = "Releases"
    override val settingsSectionAbout      = "ABOUT"

    // Tech stack descriptions
    override val techKotlinDesc  = "Primary language"
    override val techComposeDesc = "UI framework"
    override val techKtorDesc    = "HTTP client"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Skin rendering"
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
    override val trayServers       = "Servers"
    override val trayNoServers     = "No servers loaded"

    // --- Settings: Experimental features ---
    override val settingsSectionExperimental    = "Experimental features"
    override val settingsExperimentalMaster     = "Experimental features"
    override val settingsExperimentalMasterDesc = "Master toggle. Disabling this forces both knobs below to off, regardless of their stored values."
    override val settingsMandatoryUpdates       = "Mandatory updates"
    override val settingsMandatoryUpdatesDesc   = "Block startup until critical updates are installed when the upstream protocol breaks. Currently ON by default."
    override val settingsPrereleaseChannel      = "Pre-release update channel"
    override val settingsPrereleaseChannelDesc  = "Receive RC and beta builds. Lets you get protocol fixes before the next stable release. Currently ON by default."
    override val settingsAutoSyncAllPacks       = "Auto-sync installed packs on launch"
    override val settingsAutoSyncAllPacksDesc   = "Quietly refresh every server pack you've already installed when the launcher starts. Costs background bandwidth — useful if you switch between many servers and want fresh state without clicking each one."
    override val settingsJvmBuilder             = "Visual JVM args builder"
    override val settingsJvmBuilderDesc         = "Reveals a 'Build args' button in the per-server constructor. Pick a GC algorithm, tune heap regions, enable AppCDS or JFR profiling — without memorizing flags. Curated presets cover Aikar's recipe, GTNH-class heavy modded, ZGC for huge heaps, and more."
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Syncing $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB MB"

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

    override val settingsForceProxyTitle = "Force proxy mode"
    override val settingsForceProxyDesc  = "Skip the direct connection attempt and route every request through the SmartyCraft SOCKS proxy. Enable if you're in a region or network where direct access fails."

    override val settingsSectionDataDir       = "Data directory"
    override val settingsDataDirCurrent       = "Current path:"
    override val settingsDataDirMove          = "Move..."
    override val settingsDataDirPickerTitle   = "Choose a new location for Aura data"
    override val settingsDataDirConfirmTitle  = "Move data directory?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Aura will relocate its data:\nfrom: $source\nto:   $target\n\nThe move applies on the next launcher start."
    override val settingsDataDirRestartRequired = "Restart required — Aura will apply the move on next start"
    override val settingsDataDirQuitNow         = "Quit now"
    override val settingsDataDirErrorSamePath   = "That's already the current directory — pick a different folder"
    override val settingsDataDirErrorNotEmpty   = "Target folder is not empty — pick an empty folder or delete its contents"

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
    override fun jvmPreviewFlagsCount(n: Int) = "Preview ($n flags)"

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
}
