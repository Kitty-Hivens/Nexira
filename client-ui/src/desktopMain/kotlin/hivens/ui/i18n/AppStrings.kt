package hivens.ui.i18n

interface AppStrings {

    // --- App ---
    val appName: String
    val appVersion: String

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
    val loginRegister: String

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

    // --- Launcher States ---
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
    /** Shown in green after a successful skin upload. */
    val profileUploadSuccess: String
    /** Shown in red after a failed skin upload. */
    fun profileUploadError(msg: String): String

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
    val updateTitleMandatory: String
    val updateCriticalBanner: String
    val updateMandatoryBanner: String
    fun updateMandatoryBannerWithReason(reason: String): String
    val updateChangelog: String
    val updateHighlights: String
    val updateViewOnGitHub: String
    val updateLater: String
    val updateExit: String
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

    // --- Settings: Diagnostics ---
    val settingsSectionDiagnostics: String
    val settingsOpenLogs: String
    val settingsOpenCrashReports: String

    // --- File Manager ---
    val fileCheckIntegrity: String
    val fileNoUpdates: String
    fun fileDownloading(n: Int): String
    val fileClientSetup: String

    // --- Settings: Offline Mode ---
    val settingsOfflineMode: String
    val settingsOfflineModeDesc: String

    // --- Launcher States: Offline ---
    val stateOfflineSkipAuth: String
    val stateOfflineSkipSync: String
    val stateOfflineNoClient: String
    val stateOfflineNoManifest: String

    // --- Server Settings: Extended ---
    val serverSettingsJvmArgs: String
    val serverSettingsJvmArgsHint: String
    val serverSettingsResolution: String
    val serverSettingsWidth: String
    val serverSettingsHeight: String
    val serverSettingsFullscreen: String
    val serverSettingsAutoConnect: String

    // --- Server Settings: Icon Upload ---
    val serverSettingsPickIcon: String

    // =========================================================================
    // RAM Selector
    // =========================================================================
    val ramCustomInputLabel: String
    fun ramSystemHint(systemRam: String, recommended: String): String

    // =========================================================================
    // Mod cards
    // =========================================================================
    fun modConflictWarning(ids: String): String
    fun modIncompatibleHint(ids: String): String

    // =========================================================================
    // Server grid
    // =========================================================================
    val serversFavorites: String

    // =========================================================================
    // Custom Background
    // =========================================================================
    val backgroundTitle: String
    val backgroundSubtitle: String
    val backgroundEnable: String
    val backgroundSectionImage: String
    val backgroundPickFile: String
    val backgroundPickButton: String
    val backgroundSectionScale: String
    val backgroundScaleCover: String
    val backgroundScaleContain: String
    val backgroundScaleStretch: String
    val backgroundScaleOriginal: String
    val backgroundScaleTile: String
    val backgroundSectionPosition: String
    val backgroundAlignX: String
    val backgroundAlignY: String
    val backgroundSectionEffects: String
    val backgroundBlur: String
    val backgroundDarken: String
    val backgroundOpacity: String
    val backgroundSaturation: String
    val backgroundParallax: String
    val backgroundVignette: String
    val backgroundSectionTint: String
    val backgroundTintNone: String
    val backgroundTintNavy: String
    val backgroundTintViolet: String
    val backgroundTintEmerald: String
    val backgroundTintBordeaux: String
    val backgroundTintSteel: String
    val backgroundTintIntensity: String
    val backgroundReset: String
    val backgroundPreview: String
    val backgroundPreviewServer: String

    // --- Settings: Background shortcut ---
    val settingsBackground: String
    val settingsBackgroundSub: String

    // =========================================================================
    // About Screen
    // =========================================================================
    val aboutTitle: String
    fun aboutDescription(branding: String): String
    fun aboutBuildDate(date: String): String
    val aboutSectionCreator: String
    val aboutSectionTechnologies: String
    val aboutSectionLicense: String
    val aboutLicenseText: String
    val aboutSectionUpdates: String
    val aboutCurrentVersion: String
    val aboutCheckUpdates: String
    val aboutChecking: String
    val aboutUpToDate: String
    val aboutCheckAgain: String
    fun aboutUpdateAvailable(version: String): String
    val aboutCriticalUpdate: String
    val aboutSectionSystem: String
    val aboutOs: String
    val aboutJvmHeap: String
    val aboutSectionLinks: String
    val aboutLinkGithub: String
    val aboutLinkBugReport: String
    val aboutLinkReleases: String

    // --- Settings: About shortcut ---
    val settingsSectionAbout: String

    // --- Tech stack descriptions (for About screen) ---
    val techKotlinDesc: String
    val techComposeDesc: String
    val techKtorDesc: String
    val techKoinDesc: String
    val techSkiaDesc: String
    val techCoilDesc: String

    // --- Spawn Reset ---
    val spawnResetButton: String
    val spawnResetLoading: String
    val spawnResetSuccess: String
    val spawnResetError: String

    // --- Tray ---
    val trayStatusIdle: String
    val trayStatusRunning: String
    val trayShow: String
    val trayServers: String
    val trayNoServers: String

    // --- Settings: Start in tray ---
    val settingsStartInTray: String
    val settingsStartInTrayDesc: String

    // --- Settings: Experimental features ---
    val settingsSectionExperimental: String
    val settingsExperimentalMaster: String
    val settingsExperimentalMasterDesc: String
    val settingsMandatoryUpdates: String
    val settingsMandatoryUpdatesDesc: String
    val settingsPrereleaseChannel: String
    val settingsPrereleaseChannelDesc: String

    // ─── April Fools close dialog ─────────────────────────────────────────────────
    /** Dialog title — changes tone as the user fails more attempts */
    fun aprilCloseTitle(escapes: Int): String
    /** Dialog body text — changes with escalating despair */
    fun aprilCloseBody(escapes: Int): String
    val aprilCloseStay: String
    val aprilCloseClose: String
    val aprilCloseSurrender: String
    val aprilCloseHideTray: String
    fun aprilCloseEscapeCount(current: Int, max: Int): String

    // --- SSL Warning ---
    val sslWarningTitle: String
    val sslWarningBody: String
    val sslWarningConnectAnyway: String
    val sslWarningCancel: String
}
