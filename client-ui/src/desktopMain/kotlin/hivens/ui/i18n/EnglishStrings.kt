package hivens.ui.i18n

object EnglishStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Splash
    override val splashLoading = "Loading..."

    // Login
    override val loginTitle        = "Aura Client"
    override val loginUsername     = "Username"
    override val loginPassword     = "Password"
    override val loginRemember     = "Remember password"
    override val loginButton       = "LOG IN"
    override val loginSuccess      = "SUCCESS"
    override val loginLoading      = "LOADING"
    override val loginErrorEmpty   = "Enter your username and password"
    override val loginErrorGeneric = "Login error"

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

    // Seasons
    override val seasonAuto    = "Automatic"
    override val seasonNone    = "Disabled"
    override val seasonWinter  = "Winter (Snow)"
    override val seasonNewYear = "New Year"
    override val seasonSpring  = "Spring (Sakura)"
    override val seasonSummer  = "Summer (Fireflies)"
    override val seasonAutumn  = "Autumn (Leaves)"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics = "Diagnostics"
    override val settingsOpenLogs           = "Open logs folder"
    override val settingsOpenCrashReports   = "Crash reports"

    // File Manager
    override val fileCheckIntegrity = "Checking file integrity..."
    override val fileNoUpdates      = "Files verified, no updates found."
    override fun fileDownloading(n: Int) = "Downloading updates ($n files)..."
    override val fileClientSetup    = "Setting up client..."
}
