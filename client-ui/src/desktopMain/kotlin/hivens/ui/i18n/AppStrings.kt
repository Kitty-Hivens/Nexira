package hivens.ui.i18n

interface AppStrings {

    // --- App ---
    val appName: String

    // --- Login ---
    val loginTitle: String
    val loginUsername: String
    val loginPassword: String
    val loginRemember: String
    val loginButton: String
    val loginErrorEmpty: String
    val loginErrorGeneric: String
    val loginRegister: String

    // --- Navigation ---
    val navLogout: String
    val navBack: String

    // --- Dashboard ---
    fun dashboardWelcome(name: String): String
    val dashboardServers: String
    val dashboardServersEmpty: String
    /** Shown in the main content area while no session exists — replaces the
     *  ambiguous "spinning indicator forever" state for unauthenticated users. */
    val dashboardLoginRequiredTitle: String
    val dashboardLoginRequiredHint: String

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
    val newsEmpty: String

    // --- Server Detail ---
    val serverDetailTitle: String
    val serverDetailNoImage: String
    val serverDetailNoImageHint: String
    val serverDetailMissingTitle: String
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
    /** Header showing how many entries pass the active filter, e.g. "Game Output (12/847)". */
    fun consoleHeaderCount(filtered: Int, total: Int): String
    val consoleCopyAll: String
    val consoleClear: String
    val consoleWrap: String
    val consoleSaveToFile: String
    val consoleSearchPlaceholder: String
    val consoleJumpToBottom: String

    // --- Tray ---
    val trayConsole: String
    val trayExit: String

    // --- Settings: Diagnostics ---
    val settingsSectionDiagnostics: String
    val settingsOpenLogs: String
    val settingsOpenCrashReports: String
    /** Beacon: bundles crash reports + redacted logs + system info into one ZIP. */
    val settingsCreateDiagnosticBundle: String
    val settingsDiagnosticBundleHint: String
    /** Companion to [settingsCreateDiagnosticBundle] — opens a pre-filled GitHub Issue
     *  asking the user to drag-attach the just-created bundle. Off-machine action only
     *  triggers when user clicks Submit in their browser — never the launcher itself. */
    val settingsReportOnGithub: String

    // --- File Manager ---
    fun fileDownloading(n: Int): String

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
    /** Button label that opens the visual JVM args builder when the experimental toggle is on. */
    val serverSettingsJvmBuildArgs: String
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

    // --- Settings: Experimental features ---
    val settingsSectionExperimental: String
    val settingsExperimentalMaster: String
    val settingsExperimentalMasterDesc: String
    val settingsMandatoryUpdates: String
    val settingsMandatoryUpdatesDesc: String
    val settingsPrereleaseChannel: String
    val settingsPrereleaseChannelDesc: String
    val settingsAutoSyncAllPacks: String
    val settingsAutoSyncAllPacksDesc: String
    val settingsJvmBuilder: String
    val settingsJvmBuilderDesc: String

    /** Auto-sync progress strip — `Syncing <name> (3/7)` */
    fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int): String
    /** Auto-sync byte progress — `123 / 456 MB` */
    fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long): String

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

    // --- 2FA (TOTP) — #159 ---
    val auth2faTitle: String
    val auth2faPrompt: String
    val auth2faPlaceholder: String
    val auth2faSubmit: String
    val auth2faCancel: String
    val auth2faInvalid: String
    val auth2faExpired: String

    // --- SSL Warning ---
    val sslWarningTitle: String
    val sslWarningBody: String
    val sslWarningConnectAnyway: String
    val sslWarningCancel: String
    val sslWarningTrustPrompt: String
    val sslWarningTrustHour: String
    val sslWarningTrust30Days: String
    val sslWarningTrustAlways: String

    // --- SSL Bypass list (Settings → Network) ---
    val settingsSectionNetwork: String
    val sslBypassListTitle: String
    val sslBypassNoEntries: String
    val sslBypassRevoke: String
    /** Receives a pre-formatted local date/time string. */
    fun sslBypassExpiresAt(formatted: String): String

    // --- Force proxy mode (Settings → Network) — Conduit Phase 2 ---
    val settingsForceProxyTitle: String
    val settingsForceProxyDesc: String

    // --- Data directory (Settings → Data dir) ---
    val settingsSectionDataDir: String
    val settingsDataDirCurrent: String
    val settingsDataDirMove: String
    val settingsDataDirPickerTitle: String
    val settingsDataDirConfirmTitle: String
    /** Templated body: receives source and target paths. */
    fun settingsDataDirConfirmBody(source: String, target: String): String
    val settingsDataDirRestartRequired: String
    val settingsDataDirQuitNow: String
    val settingsDataDirErrorSamePath: String
    val settingsDataDirErrorNotEmpty: String

    // ═══════════════════════════════════════════════════════════════════════
    // JVM Args Builder dialog
    // ═══════════════════════════════════════════════════════════════════════
    // Technical -XX flag names and JVM-recognized identifiers (G1GC, ZGC,
    // MaxGCPauseMillis, AlwaysPreTouch, etc.) are kept hardcoded — they
    // are not labels but actual flag names a user has to recognize. Only
    // the descriptive helpers, section headers, and button copy are
    // localized.

    val jvmTitle: String
    val jvmSubtitle: String
    val jvmPresetsHeader: String
    val jvmTabGc: String
    val jvmTabTuning: String
    val jvmTabCds: String
    val jvmTabJit: String
    val jvmTabPerf: String
    val jvmTabJfr: String
    val jvmTabCustom: String
    val jvmCancel: String
    val jvmApply: String
    fun jvmPreviewFlagsCount(n: Int): String

    val jvmGcHeader: String
    val jvmGcG1Hint: String
    val jvmGcZHint: String
    val jvmGcShenandoahHint: String
    val jvmGcParallelHint: String
    val jvmGcSerialHint: String

    val jvmG1Header: String
    val jvmG1MaxPauseMillisHint: String
    val jvmG1RegionSizeHint: String
    val jvmG1NewSizePercentHint: String
    val jvmG1MaxNewSizePercentHint: String
    val jvmG1IhopHint: String
    val jvmG1ParallelRefProcHint: String
    val jvmG1PerfDisableSharedMemHint: String

    val jvmZHeader: String
    val jvmZGenerationalHint: String

    val jvmShenandoahHeader: String
    val jvmShenandoahAdaptiveHint: String
    val jvmShenandoahStaticHint: String
    val jvmShenandoahCompactHint: String
    val jvmShenandoahAggressiveHint: String

    fun jvmTuningNotApplicable(gcName: String): String

    val jvmCdsHeader: String
    val jvmCdsIntro: String
    val jvmCdsModeDisabledLabel: String
    val jvmCdsModeDisabledHint: String
    val jvmCdsModeAutoLabel: String
    val jvmCdsModeAutoHint: String
    val jvmCdsModeArchiveLabel: String
    val jvmCdsModeArchiveHint: String
    val jvmCdsModeUseLabel: String
    val jvmCdsModeUseHint: String
    val jvmCdsArchivePathLabel: String

    val jvmJitHeader: String
    val jvmJitTieredHint: String
    val jvmJitCodeCacheHint: String

    val jvmPerfHeader: String
    val jvmPerfAlwaysPreTouchHint: String
    val jvmPerfDisableExplicitGcHint: String
    val jvmPerfUseLargePagesHint: String
    val jvmPerfTransparentHugePagesHint: String
    val jvmPerfNumaHint: String
    val jvmPerfHeapDumpHint: String
    val jvmPerfExitOnOomHint: String

    val jvmJfrHeader: String
    val jvmJfrIntro: String
    val jvmJfrEnableLabel: String
    val jvmJfrEnableHint: String
    val jvmJfrDurationLabel: String
    val jvmJfrSettingsHeader: String
    val jvmJfrSettingsDefaultHint: String
    val jvmJfrSettingsProfileHint: String
    val jvmJfrOutputPathLabel: String

    val jvmCustomHeader: String
    val jvmCustomIntro: String
    val jvmCustomLabel: String
}
