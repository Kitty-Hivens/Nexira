package hivens.ui.i18n

object GermanStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Login
    override val loginTitle        = "Aura Client"
    override val loginUsername     = "Benutzername"
    override val loginPassword     = "Passwort"
    override val loginRemember     = "Passwort speichern"
    override val loginButton       = "ANMELDEN"
    override val loginSuccess      = "ERFOLG"
    override val loginLoading      = "LADEN"
    override val loginErrorEmpty   = "Benutzername und Passwort eingeben"
    override val loginErrorGeneric = "Anmeldefehler"

    // Navigation
    override val navHome     = "Startseite"
    override val navProfile  = "Profil"
    override val navSettings = "Einstellungen"
    override val navConsole  = "Konsole"
    override val navLogout   = "Abmelden"
    override val navBack     = "Zurück"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WILLKOMMEN ZURÜCK, $name"
    override val dashboardNews         = "Neuigkeiten"
    override val dashboardServers      = "VERFÜGBARE SERVER"
    override val dashboardServersEmpty = "Keine Server gefunden"

    // Launch Control
    override val launchReady       = "Bereit zum Spielen"
    override val launchButton      = "SPIELEN"
    override val launchAbort       = "ABBRECHEN"
    override val launchRunning     = "Spiel läuft"
    override val launchResetError  = "FEHLER ZURÜCKSETZEN"
    override val launchDownloading = "Herunterladen:"

    // Launcher States
    override val stateInit        = "Initialisierung..."
    override val stateAuth        = "Authentifizierung..."
    override val stateAuthFail    = "Authentifizierungsfehler (Offline?)"
    override val stateNoPassword  = "Kein Passwort gefunden, aktuelle Sitzung wird verwendet."
    override val stateSync        = "Dateien synchronisieren..."
    override val stateJvm         = "JVM vorbereiten..."
    override val stateLaunching   = "Prozess starten..."
    override fun stateExitCode(code: Int)  = "Spiel mit Code $code beendet"
    override fun stateError(msg: String)   = "Fehler: $msg"
    override fun authSuccess(uuid: String) = "Anmeldung erfolgreich. UUID: $uuid"

    // Profile
    override val profileTitle              = "PROFIL"
    override val profileStatusLabel        = "Status"
    override val profileStatusOnline       = "Angemeldet"
    override val profileStatusOffline      = "Offline"
    override val profileBalance            = "Guthaben"
    override val profileTopUp              = "Guthaben aufladen"
    override val profileUploadSkin         = "Skin hochladen"
    override val profileUploadSkinLoading  = "Hochladen..."
    override val profileSkinFront          = "Vorderseite"
    override val profileSkinBack           = "Rückseite"
    override val profileSkinLoading        = "Skin wird geladen..."
    override val profileRefresh            = "Aktualisieren"
    override val profileUploadSuccess      = "Skin erfolgreich hochgeladen"
    override fun profileUploadError(msg: String) = "Upload-Fehler: $msg"

    // Settings
    override val settingsTitle              = "GLOBALE EINSTELLUNGEN"
    override val settingsSectionUI          = "Oberfläche"
    override val settingsSectionBehavior    = "Verhalten"
    override val settingsThemePicker        = "Design auswählen"
    override val settingsThemePickerSub     = "Farbschema anpassen"
    override val settingsDarkTheme          = "Dunkles Design"
    override val settingsSeasonEffect       = "Saisonaler Effekt"
    override val settingsSeasonEffectSub    = "Hintergrundanimation"
    override val settingsCloseAfterLaunch   = "Launcher nach Spielstart schließen"
    override val settingsSaved              = "Einstellungen gespeichert"
    override val settingsLanguage           = "Sprache"

    // Theme Picker
    override val themePickerTitle           = "DESIGN WÄHLEN"
    override val themePickerApply           = "ANWENDEN"
    override val themePickerPreview         = "VORSCHAU"
    override val themePickerSelected        = "Ausgewählt"
    override val themePickerColorPrimary    = "Primär"
    override val themePickerColorSecondary  = "Sekundär"
    override val themePickerColorBackground = "Hintergrund"
    override val themePickerColorSurface    = "Oberfläche"
    override val themePickerColorAccent     = "Akzent"
    override val themePickerColorSuccess    = "Erfolg"
    override val themePickerColorError      = "Fehler"
    override val themePickerBtnSample       = "Beispiel-Schaltfläche"
    override val themePickerBtnOutlined     = "Umrandete Schaltfläche"

    // News
    override val newsTitle   = "PROJEKTNEUIGKEITEN"
    override val newsLoading = "Neuigkeiten werden geladen..."
    override val newsEmpty   = "Noch keine Neuigkeiten..."
    override val newsNoImage = "KEIN BILD"

    // Server Detail
    override val serverDetailTitle         = "SERVER-INFORMATIONEN"
    override val serverDetailLoading       = ""
    override val serverDetailNoImage       = "Kein Bild"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Informationen fehlen"
    override val serverDetailMissingBody   = "Erstelle die Datei im Ordner:"
    override fun serverDetailMissingPath(path: String, file: String) = "Erstelle $file in:"

    // Server Settings
    override val serverSettingsSubtitle        = "Starteinstellungen"
    override val serverSettingsSectionSystem   = "SYSTEM"
    override val serverSettingsSectionMods     = "MODIFIKATIONEN"
    override val serverSettingsRam             = "RAM"
    override fun serverSettingsRamValue(mb: Int) = "RAM: $mb MB"
    override val serverSettingsJava            = "Java-Version"
    override fun serverSettingsJavaAuto(version: String) = "Automatisch ($version)"
    override val serverSettingsJavaHint        = "Leer lassen für das integrierte Java"
    override val serverSettingsOpenFolder      = "Ordner öffnen"
    override val serverSettingsReset           = "Client zurücksetzen"
    override val serverSettingsNoMods          = "Keine optionalen Mods"
    override val serverSettingsPickJava        = "Java auswählen"

    // Update
    override val updateTitle           = "Update verfügbar"
    override val updateTitleCritical   = "KRITISCHES UPDATE"
    override val updateCriticalBanner  = "Dieses Update enthält kritische Sicherheitskorrekturen."
    override val updateChangelog       = "Was ist neu:"
    override val updateLater           = "Später"
    override val updateDownload        = "Herunterladen und installieren"
    override val updateDownloadNow     = "JETZT HERUNTERLADEN"
    override val updateDownloading     = "Herunterladen..."
    override val updateInstall         = "Installieren und neustarten"
    override val updateRetry           = "Erneut versuchen"
    override val updateErrorTitle      = "Download-Fehler"
    override val updateErrorUnknown    = "Unbekannter Fehler"
    override val updateScheduleFailed  = "Update konnte nicht geplant werden"
    override fun updateVersion(version: String) = "Version $version"
    override val updateDetails         = "Details"

    // Console
    override val consoleTitle = "Debug-Konsole"
    override fun consoleTitleCount(n: Int) = "Spielausgabe ($n)"
    override val consoleCopyAll = "Alles kopieren"
    override val consoleClear   = "Leeren"

    // Tray
    override val trayShowHide = "Anzeigen / Ausblenden"
    override val trayConsole  = "Konsole öffnen"
    override val trayExit     = "Beenden"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics = "Diagnose"
    override val settingsOpenLogs           = "Log-Ordner öffnen"
    override val settingsOpenCrashReports   = "Absturzberichte"

    // File Manager
    override val fileCheckIntegrity = "Dateiintegrität wird überprüft..."
    override val fileNoUpdates      = "Dateien überprüft, keine Updates gefunden."
    override fun fileDownloading(n: Int) = "Updates werden heruntergeladen ($n Dateien)..."
    override val fileClientSetup    = "Client wird eingerichtet..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Offlinemodus"
    override val settingsOfflineModeDesc   = "Starten ohne Authentifizierung. Dateien werden nicht synchronisiert."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Offlinemodus — Authentifizierung übersprungen"
    override val stateOfflineSkipSync      = "Offlinemodus — Dateisynchronisierung übersprungen, lokale Dateien werden verwendet"
    override val stateOfflineNoClient      = "Client-Dateien nicht gefunden. Zuerst online herunterladen."

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs     = "JVM-Argumente"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
    override val serverSettingsResolution  = "Fenstergröße"
    override val serverSettingsWidth       = "Breite"
    override val serverSettingsHeight      = "Höhe"
    override val serverSettingsFullscreen  = "Vollbild"
    override val serverSettingsAutoConnect = "Automatisch mit Server verbinden"

    // --- Server Settings: Icon Upload ---
    override val serverSettingsPickIcon    = "Server-Icon auswählen"

    // =========================================================================
    // RAM Selector
    // =========================================================================
    override val ramCustomInputLabel = "Eigener Wert:"
    override fun ramSystemHint(systemRam: String, recommended: String) =
        "System: $systemRam • Empfohlen max: $recommended"

    // =========================================================================
    // Mod cards
    // =========================================================================
    override fun modConflictWarning(ids: String) = "Konflikt mit: $ids"
    override fun modIncompatibleHint(ids: String) = "Inkompatibel mit: $ids"

    // =========================================================================
    // Server grid
    // =========================================================================
    override val serversFavorites = "★ FAVORITEN"

    // =========================================================================
    // Custom Background
    // =========================================================================
    override val backgroundTitle          = "BENUTZERDEFINIERTER HINTERGRUND"
    override val backgroundSubtitle       = "Launcher-Hintergrund anpassen"
    override val backgroundEnable         = "Aktivieren"
    override val backgroundSectionImage   = "BILD"
    override val backgroundPickFile       = "Hintergrundbild auswählen"
    override val backgroundPickButton     = "Datei auswählen"
    override val backgroundSectionScale   = "SKALIERUNG"
    override val backgroundScaleCover     = "Füllen"
    override val backgroundScaleContain   = "Einpassen"
    override val backgroundScaleStretch   = "Strecken"
    override val backgroundScaleOriginal  = "Original"
    override val backgroundScaleTile      = "Kacheln"
    override val backgroundSectionPosition = "POSITION"
    override val backgroundAlignX         = "Horizontal"
    override val backgroundAlignY         = "Vertikal"
    override val backgroundSectionEffects = "EFFEKTE"
    override val backgroundBlur           = "Unschärfe"
    override val backgroundDarken         = "Abdunkeln"
    override val backgroundOpacity        = "Deckkraft"
    override val backgroundSaturation     = "Sättigung"
    override val backgroundParallax       = "Parallaxe"
    override val backgroundVignette       = "Vignette"
    override val backgroundSectionTint    = "FARBTÖNUNG"
    override val backgroundTintNone       = "Keine"
    override val backgroundTintNavy       = "Dunkelblau"
    override val backgroundTintViolet     = "Violett"
    override val backgroundTintEmerald    = "Smaragd"
    override val backgroundTintBordeaux   = "Bordeaux"
    override val backgroundTintSteel      = "Stahl"
    override val backgroundTintIntensity  = "Intensität"
    override val backgroundReset          = "Auf Standardwerte zurücksetzen"
    override val backgroundPreview        = "VORSCHAU"
    override val backgroundPreviewServer  = "Beispielserver"
    override val settingsBackground       = "Benutzerdefinierter Hintergrund"
    override val settingsBackgroundSub    = "Foto oder GIF als Launcher-Hintergrund"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "ÜBER DEN LAUNCHER"
    override fun aboutDescription(branding: String) = "Inoffizieller Launcher für $branding"
    override fun aboutBuildDate(date: String) = "Erstellt: $date"
    override val aboutSectionCreator       = "ERSTELLER"
    override val aboutSectionTechnologies  = "TECHNOLOGIEN"
    override val aboutSectionLicense       = "LIZENZ"
    override val aboutLicenseText          = "GPLv3 — Freie und quelloffene Software"
    override val aboutSectionUpdates       = "UPDATES"
    override val aboutCurrentVersion       = "Aktuelle Version"
    override val aboutCheckUpdates         = "Auf Updates prüfen"
    override val aboutChecking             = "Prüfe..."
    override val aboutUpToDate             = "Sie sind auf dem neuesten Stand!"
    override val aboutCheckAgain           = "Erneut prüfen"
    override fun aboutUpdateAvailable(version: String) = "Version $version verfügbar"
    override val aboutCriticalUpdate       = "Kritisches Update"
    override val aboutSectionSystem        = "SYSTEM"
    override val aboutOs                   = "Betriebssystem"
    override val aboutJvmHeap             = "JVM Heap"
    override val aboutSectionLinks         = "LINKS"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Fehler melden"
    override val aboutLinkReleases         = "Veröffentlichungen"
    override val settingsSectionAbout      = "ÜBER"

    // Tech stack descriptions
    override val techKotlinDesc  = "Hauptsprache"
    override val techComposeDesc = "UI-Framework"
    override val techKtorDesc    = "HTTP-Client"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Skin-Rendering"
    override val techCoilDesc    = "Bildladen"

    // --- Spawn Reset ---
    override val spawnResetButton  = "Zum Spawn zurückkehren"
    override val spawnResetLoading = "Zurücksetzen..."
    override val spawnResetSuccess = "Fertig! Neu einloggen"
    override val spawnResetError   = "Serverfehler"

    // --- Tray ---
    override val trayStatusIdle    = "● Bereit"
    override val trayStatusRunning = "▶ Spiel läuft"
    override val trayShow          = "Launcher anzeigen"
    override val trayServers       = "Server"
    override val trayNoServers     = "Keine Server geladen"

    // --- Settings: Start in tray ---
    override val settingsStartInTray     = "Im Tray starten"
    override val settingsStartInTrayDesc = "Minimiert starten; Fenster schließen versteckt es im Tray"
}
