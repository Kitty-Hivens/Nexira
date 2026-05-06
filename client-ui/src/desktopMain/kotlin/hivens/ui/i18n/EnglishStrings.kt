package hivens.ui.i18n

object EnglishStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Login
    override val loginTitle        = "Aura Launcher"
    override val loginUsername     = "Username"
    override val loginPassword     = "Password"
    override val loginRemember     = "Remember password"
    override val loginButton       = "LOG IN"
    override val loginSuccess      = "SUCCESS"
    override val loginLoading      = "LOADING"
    override val loginErrorEmpty   = "Enter your username and password"
    override val loginErrorGeneric = "Login error"
    override val loginRegister     = "Create an account"

    // Navigation
    override val navHome     = "Home"
    override val navProfile  = "Profile"
    override val navSettings = "Settings"
    override val navConsole  = "Console"
    override val navLogout   = "Log out"
    override val navBack     = "Back"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WELCOME BACK, $name"
    override val dashboardNews         = "News"
    override val dashboardServers      = "AVAILABLE SERVERS"
    override val dashboardServersEmpty = "No servers found"

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
    override val settingsSeasonEffect       = "Seasonal effect"
    override val settingsSeasonEffectSub    = "Background animation"
    override val settingsCloseAfterLaunch   = "Close launcher after game starts"
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
    override val newsLoading = "Loading news..."
    override val newsEmpty   = "No news yet..."
    override val newsNoImage = "NO IMG"

    // Server Detail
    override val serverDetailTitle         = "SERVER INFORMATION"
    override val serverDetailLoading       = ""
    override val serverDetailNoImage       = "No image"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Information missing"
    override val serverDetailMissingBody   = "Create the file in the folder:"
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
    override val updateCriticalBanner  = "This update contains critical security fixes."
    override val updateChangelog       = "What's new:"
    override val updateLater           = "Later"
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
    override fun consoleTitleCount(n: Int) = "Game Output ($n)"
    override val consoleCopyAll = "Copy All"
    override val consoleClear   = "Clear"

    // Tray
    override val trayShowHide = "Show / Hide"
    override val trayConsole  = "Open console"
    override val trayExit     = "Exit"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics = "Diagnostics"
    override val settingsOpenLogs           = "Open logs folder"
    override val settingsOpenCrashReports   = "Crash reports"

    // File Manager
    override val fileCheckIntegrity = "Checking file integrity..."
    override val fileNoUpdates      = "Files verified, no updates found."
    override fun fileDownloading(n: Int) = "Downloading updates ($n files)..."
    override val fileClientSetup    = "Setting up client..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Offline mode"
    override val settingsOfflineModeDesc   = "Launch without authentication. Files won't be synced."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Offline mode — authentication skipped"
    override val stateOfflineSkipSync      = "Offline mode — file sync skipped, using local files"
    override val stateOfflineNoClient      = "Client files not found. Download them online first."

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs     = "JVM arguments"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
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
    override val aboutJvmHeap             = "JVM Heap"
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
    override val trayStatusIdle    = "● Idle"
    override val trayStatusRunning = "▶ Game running"
    override val trayShow          = "Show launcher"
    override val trayServers       = "Servers"
    override val trayNoServers     = "No servers loaded"

    // --- Settings: Start in tray ---
    override val settingsStartInTray     = "Start in tray"
    override val settingsStartInTrayDesc = "Launch minimized; closing the window hides it to tray"

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

    // --- SSL Warning ---
    override val sslWarningTitle        = "Server certificate expired"
    override val sslWarningBody         = "The server's SSL certificate has expired. Your connection may be insecure — server identity cannot be verified. Proceed at your own risk?"
    override val sslWarningConnectAnyway = "Connect anyway"
    override val sslWarningCancel       = "Cancel"
}
