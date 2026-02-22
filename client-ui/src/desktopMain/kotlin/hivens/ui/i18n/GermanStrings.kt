package hivens.ui.i18n

object GermanStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Splash
    override val splashLoading = "Wird geladen..."

    // Login
    override val loginTitle = "Aura Client"
    override val loginUsername = "Benutzername"
    override val loginPassword = "Passwort"
    override val loginRemember = "Passwort speichern"
    override val loginButton = "ANMELDEN"
    override val loginSuccess = "ERFOLG"
    override val loginLoading = "LADEN"
    override val loginErrorEmpty = "Benutzername und Passwort eingeben"
    override val loginErrorGeneric = "Anmeldefehler"

    // Navigation
    override val navHome = "Startseite"
    override val navProfile = "Profil"
    override val navSettings = "Einstellungen"
    override val navConsole = "Konsole"
    override val navLogout = "Abmelden"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WILLKOMMEN ZURÜCK, $name"
    override val dashboardNews = "Neuigkeiten"
    override val dashboardServers = "VERFÜGBARE SERVER"
    override val dashboardServersEmpty = "Keine Server gefunden"

    // Launch Control
    override val launchReady = "Bereit zum Spielen"
    override val launchButton = "SPIELEN"
    override val launchAbort = "ABBRECHEN"
    override val launchRunning = "Spiel läuft"
    override val launchResetError = "FEHLER ZURÜCKSETZEN"
    override val launchDownloading = "Herunterladen:"

    // Launcher States
    override val stateInit = "Initialisierung..."
    override val stateAuth = "Authentifizierung..."
    override val stateAuthFail = "Authentifizierungsfehler (Offline?)"
    override val stateNoPassword = "Kein Passwort gefunden, aktuelle Sitzung wird verwendet."
    override val stateSync = "Dateien synchronisieren..."
    override val stateJvm = "JVM vorbereiten..."
    override val stateLaunching = "Prozess starten..."
    override fun stateExitCode(code: Int) = "Spiel mit Code $code beendet"
    override fun stateError(msg: String) = "Fehler: $msg"
    override fun authSuccess(uuid: String) = "Anmeldung erfolgreich. UUID: $uuid"

    // Profile
    override val profileTitle = "PROFIL"
    override val profileStatusOnline = "Angemeldet"
    override val profileStatusOffline = "Offline"
    override val profileBalance = "Guthaben"
    override val profileTopUp = "Guthaben aufladen"
    override val profileUploadSkin = "Skin hochladen"
    override val profileUploadSkinLoading = "Hochladen..."
    override val profileSkinFront = "Vorderseite"
    override val profileSkinBack = "Rückseite"
    override val profileSkinLoading = "Skin wird geladen..."

    // Settings
    override val settingsTitle = "GLOBALE EINSTELLUNGEN"
    override val settingsSectionUI = "Oberfläche"
    override val settingsSectionBehavior = "Verhalten"
    override val settingsThemePicker = "Design auswählen"
    override val settingsThemePickerSub = "Farbschema anpassen"
    override val settingsDarkTheme = "Dunkles Design"
    override val settingsSeasonEffect = "Saisonaler Effekt"
    override val settingsSeasonEffectSub = "Hintergrundanimation"
    override val settingsCloseAfterLaunch = "Launcher nach Spielstart schließen"
    override val settingsSaved = "Einstellungen gespeichert"
    override val settingsLanguage = "Sprache"

    // Theme Picker
    override val themePickerTitle = "DESIGN WÄHLEN"
    override val themePickerApply = "ANWENDEN"
    override val themePickerPreview = "VORSCHAU"
    override val themePickerSelected = "Ausgewählt"
    override val themePickerColorPrimary = "Primär"
    override val themePickerColorSecondary = "Sekundär"
    override val themePickerColorBackground = "Hintergrund"
    override val themePickerColorSurface = "Oberfläche"
    override val themePickerColorAccent = "Akzent"
    override val themePickerColorSuccess = "Erfolg"
    override val themePickerColorError = "Fehler"
    override val themePickerBtnSample = "Beispiel-Schaltfläche"
    override val themePickerBtnOutlined = "Umrandete Schaltfläche"

    // News
    override val newsTitle = "PROJEKTNEUIGKEITEN"
    override val newsLoading = "Neuigkeiten werden geladen..."
    override val newsEmpty = "Noch keine Neuigkeiten..."
    override val newsNoImage = "KEIN BILD"

    // Server Detail
    override val serverDetailTitle = "SERVER-INFORMATIONEN"
    override val serverDetailLoading = ""
    override val serverDetailNoImage = "Kein Bild"
    override val serverDetailMissingTitle = "Informationen fehlen"
    override val serverDetailMissingBody = "Erstelle die Datei im Ordner:"
    override fun serverDetailMissingPath(path: String, file: String) = "Erstelle $file in:"

    // Server Settings
    override val serverSettingsSectionSystem = "SYSTEM"
    override val serverSettingsSectionMods = "MODIFIKATIONEN"
    override val serverSettingsRam = "RAM"
    override fun serverSettingsRamValue(mb: Int) = "RAM: $mb MB"
    override val serverSettingsJava = "Java-Version"
    override fun serverSettingsJavaAuto(version: String) = "Automatisch ($version)"
    override val serverSettingsJavaHint = "Leer lassen für das integrierte Java"
    override val serverSettingsOpenFolder = "Ordner öffnen"
    override val serverSettingsReset = "Client zurücksetzen"
    override val serverSettingsNoMods = "Keine optionalen Mods"
    override val serverSettingsPickJava = "Java auswählen"

    // Update
    override val updateTitle = "Update verfügbar"
    override val updateTitleCritical = "KRITISCHES UPDATE"
    override val updateCriticalBanner = "Dieses Update enthält kritische Sicherheitskorrekturen."
    override val updateChangelog = "Was ist neu:"
    override val updateLater = "Später"
    override val updateDownload = "Herunterladen und installieren"
    override val updateDownloadNow = "JETZT HERUNTERLADEN"
    override val updateDownloading = "Herunterladen..."
    override val updateInstall = "Installieren und neustarten"
    override val updateRetry = "Erneut versuchen"
    override val updateErrorTitle = "Download-Fehler"

    // Console
    override val consoleTitle = "Debug-Konsole"
    override fun consoleTitleCount(n: Int) = "Spielausgabe ($n)"
    override val consoleCopyAll = "Alles kopieren"
    override val consoleClear = "Leeren"

    // Tray
    override val trayShowHide = "Anzeigen / Ausblenden"
    override val trayConsole = "Konsole öffnen"
    override val trayExit = "Beenden"

    // Seasons
    override val seasonAuto = "Automatisch"
    override val seasonNone = "Deaktiviert"
    override val seasonWinter = "Winter (Schnee)"
    override val seasonNewYear = "Neujahr"
    override val seasonSpring = "Frühling (Kirschblüte)"
    override val seasonSummer = "Sommer (Glühwürmchen)"
    override val seasonAutumn = "Herbst (Blätterfall)"

    // File Manager
    override val fileCheckIntegrity = "Dateiintegrität wird überprüft..."
    override val fileNoUpdates = "Dateien überprüft, keine Updates gefunden."
    override fun fileDownloading(n: Int) = "Updates werden heruntergeladen ($n Dateien)..."
    override val fileClientSetup = "Client wird eingerichtet..."
}
