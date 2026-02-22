package hivens.ui.i18n

// ============================================================================
// AppStrings — centralised string contract for all UI text
// Add new keys here, then implement in each language object below.
// ============================================================================

interface AppStrings {

    // --- App ---
    val appName: String
    val appVersion: String

    // --- Splash ---
    val splashLoading: String

    // --- Login ---
    val loginTitle: String
    val loginUsername: String
    val loginPassword: String
    val loginRemember: String
    val loginButton: String
    val loginSuccess: String
    val loginLoading: String
    val loginErrorEmpty: String
    val loginErrorGeneric: String

    // --- Navigation ---
    val navHome: String
    val navProfile: String
    val navSettings: String
    val navConsole: String
    val navLogout: String
    val navBack: String

    // --- Dashboard ---
    fun dashboardWelcome(name: String): String
    val dashboardNews: String
    val dashboardServers: String
    val dashboardServersEmpty: String

    // --- Launch Control ---
    val launchReady: String
    val launchButton: String
    val launchAbort: String
    val launchRunning: String
    val launchResetError: String
    val launchDownloading: String

    // --- Launcher States (non-composable use via I18n object) ---
    val stateInit: String
    val stateAuth: String
    val stateAuthFail: String
    val stateNoPassword: String
    val stateSync: String
    val stateJvm: String
    val stateLaunching: String
    fun stateExitCode(code: Int): String
    fun stateError(msg: String): String

    // --- Auth Success ---
    fun authSuccess(uuid: String): String

    // --- Profile Screen ---
    val profileTitle: String
    val profileStatusLabel: String
    val profileStatusOnline: String
    val profileStatusOffline: String
    val profileBalance: String
    val profileTopUp: String
    val profileUploadSkin: String
    val profileUploadSkinLoading: String
    val profileSkinFront: String
    val profileSkinBack: String
    val profileSkinLoading: String
    val profileRefresh: String

    // --- Settings Screen ---
    val settingsTitle: String
    val settingsSectionUI: String
    val settingsSectionBehavior: String
    val settingsThemePicker: String
    val settingsThemePickerSub: String
    val settingsDarkTheme: String
    val settingsSeasonEffect: String
    val settingsSeasonEffectSub: String
    val settingsCloseAfterLaunch: String
    val settingsSaved: String
    val settingsLanguage: String

    // --- Theme Picker ---
    val themePickerTitle: String
    val themePickerApply: String
    val themePickerPreview: String
    val themePickerSelected: String
    val themePickerColorPrimary: String
    val themePickerColorSecondary: String
    val themePickerColorBackground: String
    val themePickerColorSurface: String
    val themePickerColorAccent: String
    val themePickerColorSuccess: String
    val themePickerColorError: String
    val themePickerBtnSample: String
    val themePickerBtnOutlined: String

    // --- News ---
    val newsTitle: String
    val newsLoading: String
    val newsEmpty: String
    val newsNoImage: String

    // --- Server Detail ---
    val serverDetailTitle: String
    val serverDetailLoading: String
    val serverDetailNoImage: String
    val serverDetailNoImageHint: String
    val serverDetailMissingTitle: String
    val serverDetailMissingBody: String
    fun serverDetailMissingPath(path: String, file: String): String

    // --- Server Settings ---
    val serverSettingsSubtitle: String
    val serverSettingsSectionSystem: String
    val serverSettingsSectionMods: String
    val serverSettingsRam: String
    fun serverSettingsRamValue(mb: Int): String
    val serverSettingsJava: String
    fun serverSettingsJavaAuto(version: String): String
    val serverSettingsJavaHint: String
    val serverSettingsOpenFolder: String
    val serverSettingsReset: String
    val serverSettingsNoMods: String
    val serverSettingsPickJava: String

    // --- Update ---
    val updateTitle: String
    val updateTitleCritical: String
    val updateCriticalBanner: String
    val updateChangelog: String
    val updateLater: String
    val updateDownload: String
    val updateDownloadNow: String
    val updateDownloading: String
    val updateInstall: String
    val updateRetry: String
    val updateErrorTitle: String
    val updateErrorUnknown: String
    val updateScheduleFailed: String
    fun updateVersion(version: String): String
    val updateDetails: String

    // --- Console ---
    val consoleTitle: String
    fun consoleTitleCount(n: Int): String
    val consoleCopyAll: String
    val consoleClear: String

    // --- Tray ---
    val trayShowHide: String
    val trayConsole: String
    val trayExit: String

    // --- Season Themes ---
    val seasonAuto: String
    val seasonNone: String
    val seasonWinter: String
    val seasonNewYear: String
    val seasonSpring: String
    val seasonSummer: String
    val seasonAutumn: String

    // --- Settings: Diagnostics ---
    val settingsSectionDiagnostics: String
    val settingsOpenLogs: String
    val settingsOpenCrashReports: String

    // --- File Manager ---
    val fileCheckIntegrity: String
    val fileNoUpdates: String
    fun fileDownloading(n: Int): String
    val fileClientSetup: String
}
