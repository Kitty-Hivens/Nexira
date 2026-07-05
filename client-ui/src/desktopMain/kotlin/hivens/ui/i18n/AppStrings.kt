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
    val loginPlayOffline: String
    val loginMicrosoft: String
    val msaTitle: String
    val msaInstruction: String
    val msaCopyCode: String
    val msaOpenBrowser: String
    val msaWaiting: String

    // --- Navigation ---
    val navLogout: String
    val navBack: String
    val navForward: String
    /** App locale, for date/number formatting in widgets (not the system default). */
    val locale: java.util.Locale

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
    fun stateMissingAuthProvider(providerKey: String): String
    fun stateHelperUnavailable(mcVersion: String): String
    fun stateAuthlibUnavailable(mcVersion: String): String

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
    val settingsDarkThemeDesc: String
    val settingsThemeModeTitle: String
    val settingsThemeModeManual: String
    val settingsThemeModeSystem: String
    val settingsThemeModeWallpaper: String
    val settingsThemeModeSystemUnavailable: String
    val settingsCloseAfterLaunch: String
    val settingsCloseAfterLaunchDesc: String
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
    val newsFilterPlaceholder: String
    val newsFilterClear: String

    // --- Right rail ---
    val railCollapse: String
    val railExpand: String

    // --- Window chrome (custom title bar caption buttons) ---
    val windowMinimize: String
    val windowMaximize: String
    val windowRestore: String
    val windowClose: String

    // --- Top-bar breadcrumb ---
    val crumbHome: String
    val crumbLoading: String

    // --- Pagination ---
    val paginationPrev: String
    val paginationNext: String

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

    // --- Destructive-action confirm dialogs ---
    val serverSettingsResetConfirmTitle: String
    val serverSettingsResetConfirmBody: String
    val backgroundResetConfirmTitle: String
    val backgroundResetConfirmBody: String
    val logoutConfirmTitle: String
    val logoutConfirmBody: String

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

    // --- Update manager ---
    val updateManagerTitle: String
    val updateManagerChannel: String
    val updateChannelRelease: String
    val updateChannelBeta: String
    val updateChannelAlpha: String
    val updateChannelDev: String
    val updateChannelGit: String
    val updateManagerCurrentTag: String
    val updateManagerInstall: String
    val updateManagerRollback: String
    val updateManagerBuild: String
    val updateManagerInstallDesktop: String
    val updateManagerDesktopDone: String
    val updateManagerEmpty: String
    val updateManagerNeedsExperimental: String
    val updateManagerBuilding: String
    val updateManagerOpenHint: String
    fun updateManagerNeedsTools(tools: String): String

    // --- Console ---
    val consoleTitle: String
    /** Shown over the empty console (no log lines yet). */
    val consoleEmptyHint: String
    /** Header showing how many entries pass the active filter, e.g. "Game Output (12/847)". */
    fun consoleHeaderCount(filtered: Int, total: Int): String
    val consoleCopyAll: String
    val consoleClear: String
    val consoleWrap: String
    val consoleSaveToFile: String
    val consoleSearchPlaceholder: String
    val consoleCopied: String
    val consoleCommandPlaceholder: String
    val consoleMenuCopyLine: String
    val consoleMenuCopySelection: String
    val consoleSelectAll: String
    val consoleSettingsLabel: String
    val consoleShowGutter: String
    val consoleHideGutter: String
    val consoleShowTimestamps: String
    val consoleHideTimestamps: String
    val consoleStatusFollow: String
    val consoleStatusPaused: String
    fun consoleStatusLines(filtered: Int, total: Int): String
    fun consoleStatusLinesWithHistory(filtered: Int, total: Int, history: Int): String
    fun consoleStatusFiltered(warn: Int, error: Int): String
    fun consoleStatusMatch(current: Int, total: Int): String

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
    /** Auto-mode chip label; [resolved] is the formatted heap Auto currently resolves to. */
    fun ramAutoLabel(resolved: String): String

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
    val backgroundAnimationSpeed: String
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
    /** System-card row label for the graphics renderer (windowing + Skiko API). */
    val aboutRenderer: String
    val aboutSectionCreator: String
    val aboutSectionTechnologies: String
    val aboutSectionLicense: String
    val aboutLicenseText: String
    val aboutSectionUpdates: String
    val aboutCurrentVersion: String
    val aboutCheckUpdates: String
    val aboutChecking: String
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
    /** Title of the one-time OS notification posted the first time the window hides to the tray. */
    val trayHintTitle: String
    /** Body of that hint -- explains the launcher is still running in the tray, not closed. */
    val trayHintBody: String
    /** Label on the hint's action button that restores the window. */
    val trayHintShow: String

    // --- Settings: Experimental features ---
    val settingsSectionExperimental: String
    val settingsExperimentalMaster: String
    val settingsExperimentalMasterDesc: String
    val settingsMandatoryUpdates: String
    val settingsMandatoryUpdatesDesc: String
    val settingsAutoSyncAllPacks: String
    val settingsAutoSyncAllPacksDesc: String
    val settingsJvmBuilder: String
    val settingsJvmBuilderDesc: String
    val settingsAdaptiveMemory: String
    val settingsAdaptiveMemoryDesc: String
    val settingsMimicVersion: String
    val settingsMimicVersionDesc: String
    /** Placeholder shown inside the mimic-version text field when override is empty. Receives the built-in default version, e.g. `Default: 3.6.5`. */
    fun settingsMimicVersionPlaceholder(default: String): String

    /** Auto-sync progress strip — `Syncing <name> (3/7)` */
    fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int): String
    /** Auto-sync byte progress — `123 / 456 MB` */
    fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long): String

    /** Background-activity widget title, shown when the user hasn't set a custom one. */
    val widgetProgressTitle: String
    /** Background-activity widget body, shown when no sync is in flight. */
    val widgetProgressIdle: String
    /** Tab-container default label for an unnamed tab — receives the 1-based tab number, e.g. `Tab 2`. */
    fun widgetTabDefaultLabel(index: Int): String

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

    // --- 2FA (TOTP) ---
    // Code-path scaffolding kept for future auth providers that DO support 2FA
    // (see [[project_client_auth_extraction]]). For the SmartyCraft provider
    // these strings are unused at runtime; the banner copy below is what the
    // user actually sees when the upstream demands a 2FA code.
    val auth2faTitle: String
    val auth2faPrompt: String
    val auth2faPlaceholder: String
    val auth2faSubmit: String
    val auth2faCancel: String
    val auth2faInvalid: String
    val auth2faExpired: String

    /** Banner shown in LoginPanel when SmartyCraft demands 2FA. Apologetic + factual + actionable. */
    val auth2faUnsupportedTitle: String
    val auth2faUnsupportedBody: String
    val auth2faUnsupportedDismiss: String

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

    // --- Smarty server controls (Settings → Smarty) ---
    val settingsSectionSmarty: String
    val settingsOpenSmrtHelperTitle: String
    val settingsOpenSmrtHelperDesc: String
    val settingsStrictModCheckTitle: String
    val settingsStrictModCheckDesc: String
    val settingsNetworkAgentTitle: String
    val settingsNetworkAgentDesc: String
    val settingsSmartyAuthLibTitle: String
    val settingsSmartyAuthLibDesc: String

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
    fun settingsDataDirErrorPickerFailed(reason: String): String

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

    // --- Data dir migration UI (mandatory first-launch flow after Nexira -> Nexira rebrand) ---
    val migrationWelcome: String
    val migrationDescription: String
    val migrationFromHeader: String
    val migrationToHeader: String
    /** Templated: total size in MB and total file count. */
    fun migrationSize(megabytes: Int, files: Int): String
    val migrationStart: String
    val migrationInProgress: String
    /** Templated: filename currently being copied, relative to the legacy root. */
    fun migrationCurrentFile(file: String): String
    /** Templated: bytes-done in MB and total in MB. */
    fun migrationProgressBytes(doneMb: Int, totalMb: Int): String
    val migrationCompletedTitle: String
    val migrationCompletedBody: String
    val migrationFailedTitle: String
    /** Templated: human-readable error message. */
    fun migrationFailedBody(error: String): String
    val migrationRetry: String
    val migrationQuit: String

    // --- Placeholder screens (Library / Browse / PackDetail) ---
    /** Title shown on screens that exist for navigation routing but have no
     *  real implementation yet -- the new Library / Browse / PackDetail
     *  surfaces under Atelier. Replaces the screen body with a centered
     *  message so the nav target is visibly reachable but the user is not
     *  misled into expecting working functionality. */
    val placeholderNotImplemented: String
    val placeholderHint: String

    // --- Navigation entries (Library / Browse, added with placeholder screens) ---
    val navLibrary: String
    val navBrowse: String

    // --- Home view variant picker (in Settings -> Interface) ---
    val settingsHomeViewTitle: String
    val settingsHomeViewSub: String
    val settingsHomeViewClassic: String
    val settingsHomeViewLibrary: String
    val settingsHomeViewNew: String

    // --- UI style variant picker (in Settings -> Interface) ---
    val settingsUiStyleTitle: String
    val settingsUiStyleSub: String
    val settingsUiStyleCelestia: String
    val settingsUiStyleBrut: String

    // --- Settings: left-rail selection style ---
    /** Title of the nav-rail selection-style control. */
    val navSelectionTitle: String
    /** One-line description under the title. */
    val navSelectionSub: String
    val navStylePill: String
    val navStyleSquare: String
    val navStyleCircle: String
    val navStyleBar: String
    val navStyleDot: String
    val navStyleNone: String
    /** Label for the filled<->outlined unselected-icon swap toggle. */
    val navSelectionOutlineIcons: String
    /** Label for the selection accent-color field. */
    val navSelectionAccent: String
    /** Label for the hover / interaction-highlight toggle. */
    val navHoverHighlight: String

    // --- Settings two-column nav labels ---
    val settingsCategoryAppearance: String
    val settingsCategoryNetwork: String
    val settingsCategorySmarty: String
    val settingsCategoryExperimental: String
    val settingsCategoryAdvanced: String
    val settingsCategoryDiagnostics: String
    val settingsCategoryConsole: String

    // --- Settings > Console section ---
    val consoleSecDisplay: String
    val consoleSecColors: String
    val consoleSecFontSize: String
    val consoleSecWrap: String
    val consoleSecGutter: String
    val consoleSecTimestamps: String
    val consoleSecBuffer: String
    val consoleSecColorInfo: String
    val consoleSecColorWarn: String
    val consoleSecColorError: String
    val consoleSecColorAuto: String
    val consoleSecApplyNote: String
    val consoleSecHighlightRules: String
    val consoleSecFilterRules: String
    val consoleSecAddRule: String
    val consoleSecRulePattern: String
    val consoleSecRegex: String
    val consoleSecBold: String
    val consoleSecRulesEmpty: String
    val consoleSecArt: String
    val consoleSecArtAdd: String
    val consoleSecArtPaste: String
    val consoleSecArtEmpty: String

    // --- Profile two-column nav labels ---
    val profileCategoryAccount: String
    val profileCategorySignIn: String
    val profileCategorySecurity: String
    val profileForgetSavedSignIn: String
    val profileSecurityHint: String
    val accountsTitle: String
    val accountRemove: String
    val accountFaceLabel: String
    val accountFaceAuto: String
    val profileSignOutSmartycraft: String
    val profileSignOutMicrosoft: String
    val msaNotConfigured: String
    val wardrobeTitle: String
    val wardrobeSignedOut: String
    val wardrobeUpload: String
    val wardrobeApplySmartycraft: String
    val wardrobeEmpty: String
    val wardrobeSaved: String
    val wardrobeCapes: String
    val wardrobeApplyCape: String
    val wardrobeCapeClanHint: String
    val wardrobeDefaults: String
    val wardrobePoseStand: String
    val wardrobePoseWave: String
    val wardrobePoseSit: String
    val wardrobePoseFaceCover: String
    val wardrobePoseWalk: String

    // --- Background loop mode ---
    val backgroundLoopMode: String
    val backgroundLoopUseCodec: String
    val backgroundLoopForever: String
    val backgroundLoopOnce: String

    // --- Customization extension ---
    val customizationAccentClear: String
    val customizationSectionVisual: String
    val customizationSectionColors: String
    val customizationHexInvalid: String
    val themePickerAccentOverride: String

    // --- Browse screen ---
    val browseTitle: String
    val browseSearchPlaceholder: String
    val browseEmptyTitle: String
    val browseEmptyMessage: String
    val browseErrorTitle: String
    val browseErrorMessage: String
    val browseRetry: String
    /** Label on the Library action that imports a pack from a local file. */
    val browseImport: String
    /** Localized label for a Modrinth category id (e.g. `optimization`); brands/unknowns fall back to a humanized form. */
    fun modrinthCategory(id: String): String

    // --- Browse pack detail ---
    val browseDetailErrorTitle: String
    val browseDetailErrorMessage: String
    val browseDetailInstallReady: String
    val browseDetailInstallHint: String
    val browseDetailInstallButton: String
    val browseDetailTagsTitle: String
    val browseDetailAboutTitle: String
    /** Placeholder pack blurb shown until the mirror ships a real description. Receives the mod and asset counts. */
    fun browseDetailAbout(mods: Int, assets: Int): String
    val browseDetailAboutNote: String
    val browseDetailCompatTitle: String
    val browseDetailCompatMc: String
    val browseDetailCompatLoader: String
    val browseDetailCompatJava: String
    val browseDetailVersionTitle: String

    val browseDetailInstallRunningTitle: String
    /** Per-file install progress line. Receives the current filename and the file counter. */
    fun browseDetailInstallProgress(filename: String, current: Int, total: Int): String
    val browseDetailInstallStarting: String
    val browseDetailInstallDoneTitle: String
    val browseDetailInstallDoneHint: String
    val browseDetailInstallOpenLibrary: String
    val browseDetailInstallFailedTitle: String
    val browseDetailInstallFailedGeneric: String

    // ── Library / PackDetail / Files tab ────────────────────────────────
    val fileBrowserNoRoot: String
    val fileBrowserPickAFile: String
    val fileBrowserBinaryHint: String
    val fileBrowserOpenExternally: String
    /** Receives the cap in KB, e.g. `Preview truncated to first 256 KB`. */
    fun fileBrowserTextTruncated(maxKb: Long): String
    val fileBrowserEmptyFolder: String

    // ── Library / PackDetail / Content tab ──────────────────────────────
    val contentTabUnsupportedOrigin: String
    val contentDetachTitle: String
    val contentDetachBody: String
    /** Tracked mirror packs: optional mods already toggle; detach for the rest. */
    val contentTrackedOptionalBody: String
    val contentDetachButton: String
    val contentAddFiles: String
    val contentFindProjects: String
    val contentSearchPlaceholder: String
    val contentEmpty: String
    val contentFilterAll: String
    val contentFilterMods: String
    val contentFilterResourcePacks: String
    val contentFilterShaderPacks: String
    val contentDeleteTitle: String
    val contentDeleteBody: String
    val contentTabFetchErrorTitle: String
    val contentTabFetchErrorGeneric: String
    val contentTabRetry: String
    val contentTabRoleSection: String
    fun contentTabOptionalSection(count: Int): String
    fun contentTabIncompatibleWith(name: String): String
    fun contentTabModsSection(count: Int): String
    fun contentTabAssetsSection(count: Int): String
    val contentTabResolverIssuesTitle: String
    fun contentTabResolverMissing(count: Int): String
    fun contentTabResolverCycles(count: Int): String
    val contentTabRoleRecipeViewer: String
    val contentTabRoleMinimap: String
    val contentTabRoleBlockInfo: String
    val contentTabRolePerformance: String
    val contentTabRoleInventorySearch: String
    fun contentTabRoleAltCount(count: Int): String
    val contentTabRoleAlternativesHeader: String
    val contentTabModNoDescription: String
    fun contentTabModLicensePrefix(license: String): String
    val contentTabModUrlLabel: String
    fun contentTabModSizeLabel(kb: Long): String
    fun contentTabModDependencies(count: Int): String
    fun contentTabModMissingCount(count: Int): String
    val contentTabDepOptional: String
    val contentTabDepMissing: String
    val contentTabModOptional: String
    fun contentTabLibrariesSection(count: Int): String
    fun contentTabResourcePacksSection(count: Int): String
    fun contentTabShaderPacksSection(count: Int): String
    fun contentTabConfigsSection(count: Int): String
    fun contentTabOtherAssetsSection(count: Int): String
    fun contentTabAssetSizeLabel(kb: Long): String
    val contentTabAssetOptional: String
    val contentTabAssetNoDescription: String

    // ── Library / PackDetail / Worlds tab ───────────────────────────────
    fun worldsTabLocalSection(count: Int): String
    val worldsTabLocalEmpty: String
    fun worldsTabServersSection(count: Int): String
    val worldsTabServersEmpty: String
    val worldsTabErrorTitle: String
    val worldsTabErrorMessage: String
    /** Receives a short label like `5h`, `2d`, or `—` when never played. */
    fun worldsTabLastPlayed(rel: String): String
    val worldsTabServerHiddenLabel: String
    val worldsTabGameSurvival: String
    val worldsTabGameCreative: String
    val worldsTabGameAdventure: String
    val worldsTabGameSpectator: String
    val worldsTabGameUnknown: String
    val worldsTabDimOverworld: String
    val worldsTabDimNether: String
    val worldsTabDimEnd: String
    val worldsTabDimOther: String

    // ── Library / PackDetail / Tab labels ───────────────────────────────
    val packDetailTabContent: String
    val packDetailTabFiles: String
    val packDetailTabWorlds: String
    val packDetailTabLogs: String
    val packDetailTabSettings: String
    val consoleSessionLive: String
    fun consoleSessionPickerLabel(current: String): String

    // ── Library / PackDetail / Hero + Play bar + Not found ──────────────
    val packDetailReadyTitle: String
    /** Receives the instance dir name (a sanitized folder under `instances/`). */
    fun packDetailInstanceDirHint(dirName: String): String
    val packDetailPlay: String
    /** Shown above the Play button when the user is not authenticated. */
    val packDetailPlayLoginRequired: String
    val packDetailNotFoundTitle: String
    val packDetailNotFoundHint: String
    val packDetailNotFoundBack: String

    // --- Notification subsystem (a11y + minimal labels) ---
    val notificationExpandHistory: String
    val notificationCollapseHistory: String
    val notificationDismiss: String
    // Notification-history widget.
    val notifHistoryEmpty: String
    val notifHistoryClear: String
    /** "Do not disturb" mute toggle on the history widget (icon-button label). */
    val notifDoNotDisturb: String
    /** Count badge on a collapsed group of repeated notifications, e.g. "x3". */
    fun notifGroupCount(count: Int): String
    /** Panel title with the message count folded in, e.g. "6 messages". */
    fun notifCountTitle(count: Int): String
    /** "+N more" footer text + screen-reader label. Receives the overflow count. */
    fun notificationShowMore(count: Int): String
    /** Absolute timestamp shown on hover tooltip over the relative-time label. */
    fun notificationAbsoluteTime(instant: java.time.Instant): String

    // --- Notification driver templates (pack launch lifecycle) ---
    fun notifPackPreparing(packName: String): String
    fun notifPackStage(stage: String): String
    fun notifPackSyncing(packName: String): String
    /** "{current}/{total} files, 47%" or "...downloading...". `pctLabel` carries either. */
    fun notifPackSyncBody(current: Int, total: Int, pctLabel: String): String
    val notifPackSyncIndeterminate: String
    fun notifPackSyncPercent(pct: Int): String
    fun notifPackRunning(packName: String): String
    fun notifPackFailed(packName: String): String
    fun notifPackSessionEnded(packName: String): String
    val notifActionShowConsole: String
    val notifActionStop: String
    val notifActionPlayOffline: String
    fun notifReasonExitCode(code: Int): String
    val notifReasonInternal: String
    fun notifReasonInternalDetail(detail: String): String
    val notifReasonAuthFail: String
    fun notifReasonAuthFailDetail(detail: String): String
    val notifReasonOfflineNoClient: String
    val notifReasonOfflineNoManifest: String
    val notifReasonTwoFactorExpired: String
    fun notifReasonMissingAuthProvider(providerKey: String): String

    // --- Notification relative-time formatter (header label) ---
    val notifTimeNow: String
    fun notifTimeSeconds(seconds: Long): String
    fun notifTimeMinutes(minutes: Long): String
    fun notifTimeHours(hours: Long): String
    fun notifTimeDays(days: Long): String

    // --- Home (new) + launch tiles ---
    val homeRecentTitle: String
    val homeNoPacksTitle: String
    val homeNoPacksBody: String
    val browseOpen: String
    val homeQuickContinue: String
    val homeQuickStart: String
    val homeQuickButton: String
    fun homeHeroPlaytime(hours: Long): String
    val launchTileReady: String
    val launchTileBlocked: String

    // --- Library widgets ---
    val libraryEmptyTitle: String
    val libraryEmptyBody: String
    val libraryHeaderTitle: String
    val libraryHeaderSubtitle: String

    // --- Customization widget labels ---

    // --- Layout editor: common actions ---
    val editorClose: String
    val editorCancel: String
    val editorDelete: String
    val editorReset: String
    val editorResetAll: String
    val editorToFront: String
    val editorToBack: String
    // Edit-mode placeholder for a widget whose kind is no longer in the registry.
    val editorUnsupportedWidget: String

    // --- Widget palette / prop-editor labels (key-indirection) ---
    // @Widget(displayName) and @PropLabel carry a key; the palette and prop
    // editor resolve it via widgetLabel(). Unknown key returns itself, so label
    // conversion is incremental and never crashes.
    val widgetLabels: Map<String, String>
    fun widgetLabel(key: String): String = widgetLabels[key] ?: key

    // --- UI crash recovery (safe mode) ---
    val recoverySafeModeTitle: String
    val recoverySafeModeBody: String
    val recoverySafeModeQuit: String

    // --- Boot recovery mode (module toggles + resets; user-triggered or crash-latched) ---
    val recoveryTitle: String
    val recoveryBody: String
    val recoveryModulesHeading: String
    val recoveryModuleTray: String
    val recoveryModuleNotify: String
    val recoveryModuleSkinema: String
    val recoveryModuleKeyring: String
    val recoveryResetsHeading: String
    val recoveryResetLayout: String
    val recoveryResetCustomization: String
    val recoveryResetSettings: String
    val recoveryContinue: String
    val recoveryRelaunchFailed: String
    val recoveryRestartInApp: String

    // Boot-threshold readout (pre-Koin boot screen). Lowercase by design --
    // a BIOS-style readout, rendered via lowercase() at the call site.
    val thresholdStageFiles: String
    val thresholdStageNetwork: String
    val thresholdStageMigration: String
    val thresholdStageModules: String
    val thresholdErrorTitle: String
    val thresholdOpenLogs: String
    val thresholdQuit: String

    // Toast shown when the shell reloads itself after a recovered crash.
    val recoveryReloadedNotice: String
    val editorSave: String
    val editorApply: String
    val editorExport: String
    val editorWidgets: String

    // --- Layout editor: slot orientation ---
    val editorSlotStack: String
    val editorSlotRow: String
    val editorSlotGrid: String
    val editorSlotCanvas: String
    val editorSlotCubeGrid: String
    val editorSlotLayoutMenuTitle: String
    val editorSlotGridColumns: String
    val editorSlotGridColumnsDecrease: String
    val editorSlotGridColumnsIncrease: String
    val editorSlotLayoutHandle: String

    // --- Layout editor: prop panel ---
    val editorResetToDefault: String
    val editorBackingTitle: String
    /** Pill chip + header for a surface's own settings panel (e.g. the left rail). */
    val editorSurfaceSettings: String
    val editorBackingGlass: String
    val editorBackingCorner: String
    val editorBackingPadding: String
    val editorBackingPaddingTop: String
    val editorBackingPaddingEnd: String
    val editorBackingPaddingBottom: String
    val editorBackingPaddingStart: String
    /** Caption under the backing controls when glass is 0: the card is not drawn,
     *  but corner still clips the widget and padding still insets it. */
    val editorBackingNoGlassHint: String

    // --- Layout editor: presets ---
    val editorPresetsTitle: String
    val editorPresetsIntro: String
    val editorPresetNamePlaceholder: String
    fun editorPresetsSaved(count: Int): String
    val editorPresetsEmpty: String

    // --- Layout editor: palette ---
    val editorPaletteHide: String
    val editorPaletteHint: String
    val editorPaletteEmpty: String
    val editorPaletteSearch: String
    val editorPaletteNoMatch: String

    // --- Layout editor: empty slot + chrome ---
    val editorDragWidgetHere: String
    val editorDragReorder: String
    val editorConfigure: String
    val editorForceRemove: String
    val editorForceRemoveTitle: String
    fun editorForceRemoveBody(name: String): String

    // --- Layout editor: host (reset / pill / fab) ---
    val editorResetSurfaceTitle: String
    fun editorResetSurfaceBody(name: String): String
    val editorPreview: String
    val editorPreviewHidden: String
    val editorPaletteToggleHide: String
    val editorEscHint: String
    val editorFabEdit: String
    val editorFabDone: String

    // --- Layout editor: surface short names ---
    val editorSurfShortHome: String
    val editorSurfShortLibrary: String
    val editorSurfShortLeftRail: String
    val editorSurfShortRightRail: String
    val editorSurfShortAbout: String
    val editorSurfShortBg: String
    val editorSurfShortProfile: String
    val editorSurfShortServer: String
    val editorSurfShortTheme: String
    val editorSurfShortShell: String
    val editorSurfShortTopBar: String
    val editorSurfShortBody: String

    // --- Layout editor: surface long names ---
    val editorSurfHomeClassic: String
    val editorSurfHomeNew: String
    val editorSurfLibrary: String
    val editorSurfLeftRail: String
    val editorSurfRightRail: String
    val editorSurfAbout: String
    val editorSurfBg: String
    val editorSurfProfile: String
    val editorSurfServer: String
    val editorSurfTheme: String
    val editorSurfShell: String
    val editorSurfTopBar: String
    val editorSurfBody: String

    // --- Music player widgets ---
    val musicPlayerTitle: String
    val audioPlay: String
    val audioPause: String
    val audioStop: String
    val audioOpenFile: String
    val audioPickTrack: String
    val audioVolume: String
    val audioNoFile: String
    val audioStatusReady: String
    val audioStatusPlaying: String
    val audioStatusPaused: String
    val audioFormatHint: String
    val audioNoPlayerHere: String
    val audioAddMusicPlayer: String
    val audioErrorUnsupported: String
    val audioErrorOpenFailed: String
    val audioErrorDeviceBusy: String
    val audioErrorPlaybackFailed: String

    // --- Video player ---
    val videoFullscreen: String
    val videoExitFullscreen: String
    val videoMute: String
    val videoUnmute: String
    val videoReplay: String
    val videoError: String
    val videoLoading: String
    val videoOpenInBrowser: String
    val videoSkipBack: String
    val videoSkipForward: String
    val videoWidgetEmpty: String

    // --- Library pack card ---
    val packCardPlay: String
    val packCardSettings: String
    val packCardMore: String
    val packCardDeleteTitle: String
    val packCardDeleteBody: String
    val packCardNeverPlayed: String
    val packCardPlayedJustNow: String
    fun packCardPlayedMinutesAgo(n: Long): String
    fun packCardPlayedHoursAgo(n: Long): String
    fun packCardPlayedDaysAgo(n: Long): String
    val packCardPlayedLongAgo: String

    // --- Session chip + about logo a11y ---
    val sessionsActiveTitle: String
    val aboutLogoDesc: String
}
