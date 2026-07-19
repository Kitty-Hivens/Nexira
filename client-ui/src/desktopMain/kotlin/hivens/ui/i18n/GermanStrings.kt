package hivens.ui.i18n

import hivens.core.data.PackAuthRequirement

object GermanStrings : AppStrings {

    // App
    override val appName = "Nexira"

    // Login
    override val loginTitle        = "Nexira"
    override val loginUsername     = "Benutzername"
    override val loginPassword     = "Passwort"
    override val loginRemember     = "Passwort speichern"
    override val loginButton       = "Anmelden"
    override val loginErrorEmpty   = "Benutzername und Passwort eingeben"
    override val loginErrorGeneric = "Anmeldefehler"
    override val loginRegister     = "Konto erstellen"
    override val loginPlayOffline  = "Offline spielen"
    override val loginMicrosoft    = "Mit Microsoft anmelden"
    override val msaTitle          = "Microsoft-Anmeldung"
    override val msaInstruction    = "Diese Seite öffnen und den Code eingeben:"
    override val msaCopyCode       = "Code kopieren"
    override val msaOpenBrowser    = "Seite öffnen"
    override val msaWaiting        = "Warten auf Bestätigung..."

    // Navigation
    override val navLogout   = "Abmelden"
    override val navBack     = "Zurück"
    override val navForward  = "Vorwärts"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WILLKOMMEN ZURÜCK, $name"
    override val dashboardServers              = "VERFÜGBARE SERVER"
    override val dashboardServersEmpty         = "Keine Server gefunden"
    override val dashboardLoginRequiredTitle   = "Anmelden, um Server zu sehen"
    override val dashboardLoginRequiredHint    = "Die SmartyCraft-Serverliste ist nur nach Anmeldung verfügbar. Anmeldung im Bereich Profil."

    // Launch Control
    override val launchReady       = "Bereit zum Spielen"
    override val launchButton      = "Spielen"
    override val launchAbort       = "Abbrechen"
    override val launchRunning     = "Spiel läuft"
    override val launchDownloading = "Herunterladen:"
    override val launchPreparing   = "Vorbereitung"
    override val launchFailed      = "Start fehlgeschlagen"

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
    override fun stateHelperUnavailable(mcVersion: String) =
        "Kein open-smrt-Helfer für Minecraft $mcVersion. Start blockiert, damit der proprietäre Smarty-Mod nicht läuft; deaktiviere den Helfer-Tausch in den Einstellungen, um damit zu spielen."
    override fun stateAuthlibUnavailable(mcVersion: String) =
        "SmartyCraft-authlib für Minecraft $mcVersion nicht erhalten. Start blockiert: der Beitritt würde abgelehnt. Prüfe Verbindung und SmartyCraft-Anmeldung und versuche es erneut."
    override fun stateMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY ->
            "Dieses Pack braucht ein SmartyCraft-Konto. Bitte anmelden, um zu spielen."
        else ->
            "Dieses Pack erfordert eine Anmeldung bei '$providerKey'."
    }
    override fun authSuccess(uuid: String) = "Anmeldung erfolgreich. UUID: $uuid"

    // Profile
    override val profileTitle              = "Profil"
    override val profileStatusLabel        = "Status"
    override val profileStatusOnline       = "Angemeldet"
    override val profileStatusOffline      = "Offline"
    override val profileBalance            = "Guthaben"
    override val profileTopUp              = "Guthaben aufladen"
    override val profileUploadSkin         = "Skin hochladen"
    override val profileUploadSkinLoading  = "Hochladen..."
    override val profileSkinLoading        = "Skin wird geladen..."
    override val profileRefresh            = "Aktualisieren"
    override val profileUploadSuccess      = "Skin erfolgreich hochgeladen"
    override fun profileUploadError(msg: String) = "Upload-Fehler: $msg"

    // Settings
    override val settingsTitle              = "Globale Einstellungen"
    override val settingsSectionUI          = "Oberfläche"
    override val settingsSectionBehavior    = "Verhalten"
    override val settingsThemePicker        = "Design auswählen"
    override val settingsThemePickerSub     = "Farbschema anpassen"
    override val settingsDarkTheme          = "Dunkles Design"
    override val settingsDarkThemeDesc      = "Dunkles Oberflächen-Design"
    override val settingsThemeModeTitle             = "Themenquelle"
    override val settingsThemeModeManual            = "Manuell"
    override val settingsThemeModeSystem            = "System"
    override val settingsThemeModeWallpaper         = "Hintergrund"
    override val settingsThemeModeSystemUnavailable = "Systemschema ist in dieser Umgebung nicht verfügbar"
    override val settingsCloseAfterLaunch   = "Launcher nach Spielstart in Tray minimieren"
    override val settingsCloseAfterLaunchDesc = "Versteckt den Launcher im System-Tray, sobald das Spiel startet."
    override val settingsSaved              = "Einstellungen gespeichert"
    override val settingsLanguage           = "Sprache"

    // Theme Picker
    override val themePickerTitle           = "DESIGN WÄHLEN"
    override val themePickerApply           = "Anwenden"
    override val themePickerPreview         = "Vorschau"
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
    override val newsTitle   = "Projektneuigkeiten"
    override val newsEmpty   = "Noch keine Neuigkeiten..."
    override val newsFilterPlaceholder = "Neuigkeiten filtern"
    override val newsFilterClear        = "Filter zurücksetzen"
    override val railCollapse           = "Leiste einklappen"
    override val railExpand             = "Leiste ausklappen"
    override val windowMinimize         = "Minimieren"
    override val windowMaximize         = "Maximieren"
    override val windowRestore          = "Wiederherstellen"
    override val windowClose            = "Schließen"
    override val crumbHome              = "Start"
    override val crumbLoading           = "Wird geladen…"
    override val paginationPrev         = "Vorherige Seite"
    override val paginationNext         = "Nächste Seite"

    // Server Detail
    override val serverDetailTitle         = "SERVER-INFORMATIONEN"
    override val serverDetailNoImage       = "Kein Bild"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Informationen fehlen"
    override fun serverDetailMissingPath(path: String, file: String) = "Erstelle $file in:"

    // Server Settings
    override val serverSettingsSubtitle        = "Starteinstellungen"
    override val serverSettingsSectionSystem   = "System"
    override val serverSettingsSectionMods     = "Modifikationen"
    override val serverSettingsRam             = "RAM"
    override fun serverSettingsRamValue(mb: Int) = "RAM: $mb MB"
    override val serverSettingsJava            = "Java-Version"
    override fun serverSettingsJavaAuto(version: String) = "Automatisch ($version)"
    override val serverSettingsJavaHint        = "Leer lassen für das integrierte Java"
    override val serverSettingsOpenFolder      = "Ordner öffnen"
    override val serverSettingsReset           = "Client zurücksetzen"

    override val serverSettingsResetConfirmTitle = "Client zurücksetzen?"
    override val serverSettingsResetConfirmBody  = "Alle heruntergeladenen Dateien des Clients dieses Servers werden unwiderruflich gelöscht."
    override val backgroundResetConfirmTitle     = "Hintergrund zurücksetzen?"
    override val backgroundResetConfirmBody      = "Die gesamte Konfiguration des eigenen Hintergrunds kehrt auf die Standardwerte zurück."
    override val logoutConfirmTitle              = "Abmelden?"
    override val logoutConfirmBody               = "Deine gespeicherte Anmeldung wird von diesem Gerät entfernt. Zum erneuten Anmelden musst du deine Zugangsdaten wieder eingeben."

    override val serverSettingsNoMods          = "Keine optionalen Mods"
    override val serverSettingsPickJava        = "Java auswählen"

    // Update
    override val updateTitle           = "Update verfügbar"
    override val updateTitleCritical   = "Kritisches Update"
    override val updateTitleMandatory  = "PFLICHT-UPDATE"
    override val updateCriticalBanner  = "Dieses Update enthält kritische Sicherheitskorrekturen."
    override val updateMandatoryBanner =
        "Die Server-Kompatibilität älterer Versionen ist gebrochen. Der Launcher kann ohne dieses Update nicht starten."
    override fun updateMandatoryBannerWithReason(reason: String) =
        "Vom Upstream-Protokoll erforderlich: $reason"
    override val updateChangelog       = "Vollständiger Änderungsverlauf"
    override val updateHighlights      = "Was ist neu"
    override val updateViewOnGitHub    = "Auf GitHub öffnen"
    override val updateLater           = "Später"
    override val updateExit            = "Beenden"
    override val updateDownload        = "Herunterladen und installieren"
    override val updateDownloadNow     = "Jetzt herunterladen"
    override val updateDownloading     = "Herunterladen..."
    override val updateInstall         = "Installieren und neustarten"
    override val updateRetry           = "Erneut versuchen"
    override val updateErrorTitle      = "Download-Fehler"
    override val updateErrorUnknown    = "Unbekannter Fehler"
    override val updateScheduleFailed  = "Update konnte nicht geplant werden"
    override fun updateVersion(version: String) = "Version $version"
    override val updateDetails         = "Details"

    // Desktop entry install (Advanced)
    override val updateManagerInstallDesktop = ".desktop-Eintrag installieren"
    override val updateManagerDesktopDone    = "Desktop-Eintrag installiert"

    // Console
    override val consoleTitle = "Debug-Konsole"
    override val consoleEmptyHint = "Alles ruhig. Starte ein Pack, dann laufen die Logs hier ein."
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Spielausgabe ($filtered/$total)"
    override val consoleCopyAll = "Alles kopieren"
    override val consoleClear   = "Leeren"
    override val consoleWrap    = "Zeilenumbruch"
    override val consoleSaveToFile = "In Datei speichern"
    override val consoleSearchPlaceholder = "Suchen…"
    override val consoleCopied = "Kopiert"
    override val consoleCommandPlaceholder = "Befehl an Spiel (Enter, ↑↓ Verlauf, Esc)"
    override val consoleMenuCopyLine = "Zeile kopieren"
    override val consoleMenuCopySelection = "Auswahl kopieren"
    override val consoleSelectAll = "Alles auswählen"
    override val consoleSettingsLabel = "Konsole-Einstellungen"
    override val consoleShowGutter = "Schweregradleiste anzeigen"
    override val consoleHideGutter = "Schweregradleiste ausblenden"
    override val consoleShowTimestamps = "Zeitstempel anzeigen"
    override val consoleHideTimestamps = "Zeitstempel ausblenden"
    override val consoleStatusFollow = "folgt"
    override val consoleStatusPaused = "pausiert"
    override fun consoleStatusLines(filtered: Int, total: Int) = "Zeilen: $filtered/$total"
    override fun consoleStatusLinesWithHistory(filtered: Int, total: Int, history: Int) =
        "Zeilen: $filtered/$total  +$history Verlauf"
    override fun consoleStatusFiltered(warn: Int, error: Int) = "WARN $warn  ERROR $error"
    override fun consoleStatusMatch(current: Int, total: Int) = "Treffer $current/$total"

    // Tray
    override val trayConsole  = "Konsole öffnen"
    override val trayExit     = "Beenden"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics      = "Diagnose"
    override val settingsOpenLogs                = "Log-Ordner öffnen"
    override val settingsOpenCrashReports        = "Absturzberichte"
    override val settingsCreateDiagnosticBundle  = "Diagnosepaket erstellen"
    override val settingsDiagnosticBundleHint    = "Bündelt redigierte Logs, Absturzberichte, Aktionshistorie und Systeminformationen in einer ZIP — für den Support."
    override val settingsReportOnGithub          = "Mit Paket auf GitHub melden"

    // File Manager
    override fun fileDownloading(n: Int) =
        "Updates werden heruntergeladen ($n ${twoFormPlural(n, "Datei", "Dateien")})..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Offlinemodus"
    override val settingsOfflineModeDesc   = "Starten ohne Authentifizierung. Dateien werden nicht synchronisiert."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Offlinemodus — Authentifizierung übersprungen"
    override val stateOfflineSkipSync      = "Offlinemodus — Dateisynchronisierung übersprungen, lokale Dateien werden verwendet"
    override val stateOfflineNoClient      = "Client-Dateien nicht gefunden. Zuerst online herunterladen."
    override val stateOfflineNoManifest    = "Kein zwischengespeichertes Manifest für diesen Server. Mindestens einmal online anmelden, bevor offline gestartet wird."

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs     = "JVM-Argumente"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
    override val serverSettingsJvmBuildArgs = "Bauen"
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
    override fun ramAutoLabel(resolved: String) = "Auto · ~$resolved"

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
    override val backgroundTitle          = "Erscheinungsbild"
    override val backgroundSubtitle       = "Hintergrund, Thema und Palette des Launchers"
    override val backgroundEnable         = "Aktivieren"
    override val backgroundSectionImage   = "Bild oder Video"
    override val backgroundPickFile       = "Hintergrundbild oder -video auswählen"
    override val backgroundPickButton     = "Datei auswählen"
    override val backgroundSectionScale   = "Skalierung"
    override val backgroundScaleCover     = "Füllen"
    override val backgroundScaleContain   = "Einpassen"
    override val backgroundScaleStretch   = "Strecken"
    override val backgroundScaleOriginal  = "Original"
    override val backgroundScaleTile      = "Kacheln"
    override val backgroundSectionPosition = "Position"
    override val backgroundAlignX         = "Horizontal"
    override val backgroundAlignY         = "Vertikal"
    override val backgroundSectionEffects = "Effekte"
    override val backgroundBlur           = "Unschärfe"
    override val backgroundDarken         = "Abdunkeln"
    override val backgroundOpacity        = "Deckkraft"
    override val backgroundSaturation     = "Sättigung"
    override val backgroundParallax       = "Parallaxe"
    override val backgroundVignette       = "Vignette"
    override val backgroundAnimationSpeed = "Animationsgeschwindigkeit"
    override val backgroundSectionTint    = "FARBTÖNUNG"
    override val backgroundTintNone       = "Keine"
    override val backgroundTintNavy       = "Dunkelblau"
    override val backgroundTintViolet     = "Violett"
    override val backgroundTintEmerald    = "Smaragd"
    override val backgroundTintBordeaux   = "Bordeaux"
    override val backgroundTintSteel      = "Stahl"
    override val backgroundTintIntensity  = "Intensität"
    override val backgroundReset          = "Auf Standardwerte zurücksetzen"
    override val backgroundPreview        = "Vorschau"
    override val backgroundPreviewServer  = "Beispielserver"
    override val settingsBackground       = "Benutzerdefinierter Hintergrund"
    override val settingsBackgroundSub    = "Foto oder GIF als Launcher-Hintergrund"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "Über den Launcher"
    override fun aboutDescription(branding: String) = "Inoffizieller Launcher für $branding"
    override val locale = java.util.Locale.GERMAN
    override fun aboutBuildDate(date: String) = "Erstellt: $date"
    override val aboutRenderer = "Renderer"
    override val aboutSectionCreator       = "Ersteller"
    override val aboutSectionTechnologies  = "Technologien"
    override val aboutSectionLicense       = "Lizenz"
    override val aboutLicenseText          = "GPLv3 — Freie und quelloffene Software"
    override val aboutSectionUpdates       = "Updates"
    override val aboutCurrentVersion       = "Aktuelle Version"
    override val aboutCheckUpdates         = "Auf Updates prüfen"
    override val aboutChecking             = "Prüfe..."
    override fun aboutUpdateAvailable(version: String) = "Version $version verfügbar"
    override val aboutCriticalUpdate       = "Kritisches Update"
    override val aboutSectionSystem        = "System"
    override val aboutOs                   = "Betriebssystem"
    override val aboutSectionLinks         = "Links"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Fehler melden"
    override val aboutLinkReleases         = "Veröffentlichungen"
    override val settingsSectionAbout      = "ÜBER"

    // Tech stack descriptions
    override val techKotlinDesc  = "Hauptsprache"
    override val techComposeDesc = "UI-Framework"
    override val techKtorDesc    = "HTTP-Client"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Grafik-Renderer"
    override val techCoilDesc    = "Bildladen"

    // --- Spawn Reset ---
    override val spawnResetButton  = "Zum Spawn zurückkehren"
    override val spawnResetLoading = "Zurücksetzen..."
    override val spawnResetSuccess = "Fertig! Neu einloggen"
    override val spawnResetError   = "Serverfehler"

    // --- Tray ---
    override val trayStatusIdle    = "Wartend"
    override val trayStatusRunning = "Spiel läuft"
    override val trayShow          = "Launcher anzeigen"
    override val trayHintTitle     = "Nexira läuft weiter"
    override val trayHintBody      = "Das Fenster liegt im System-Tray. Klicke auf das Tray-Symbol, um es zurückzuholen."
    override val trayHintShow      = "Fenster anzeigen"

    // --- Settings: Experimental features ---
    override val settingsSectionExperimental    = "Experimentelle Funktionen"
    override val settingsExperimentalMaster     = "Experimentelle Funktionen"
    override val settingsExperimentalMasterDesc = "Hauptschalter. Wird er deaktiviert, werden beide Schalter darunter unabhängig von ihren gespeicherten Werten erzwungen ausgeschaltet."
    override val settingsSectionUpdates      = "Updates"
    override val settingsPreReleases         = "Vorabversionen"
    override val settingsPreReleasesDesc     = "Beta-Builds erhalten, bevor sie stabil werden."
    override val settingsMandatoryUpdates       = "Pflicht-Updates"
    override val settingsMandatoryUpdatesDesc   = "Den Start blockieren, bis kritische Updates installiert sind, wenn die Upstream-Protokoll-Kompatibilität bricht. Aktuell standardmäßig EIN."
    override val settingsAutoSyncAllPacks       = "Installierte Modpacks beim Start automatisch aktualisieren"
    override val settingsAutoSyncAllPacksDesc   = "Aktualisiert beim Launcher-Start jedes bereits installierte Server-Pack im Hintergrund. Kostet Bandbreite — nützlich, wenn du zwischen mehreren Servern wechselst und frischen Stand ohne Klick auf jeden willst."
    override val settingsAutoUpdatePacks        = "Installierte Modpacks automatisch aktualisieren"
    override val settingsAutoUpdatePacksDesc    = "Hält installierte Mirror-Packs auf dem neuesten Build. Sichere Updates laufen im Hintergrund; eine Minecraft- oder Loader-Änderung wartet auf deine Bestätigung. Ausschalten, um Packs von Hand zu aktualisieren."
    override val settingsJvmBuilder             = "Visueller JVM-Argument-Builder"
    override val settingsJvmBuilderDesc         = "Zeigt einen „Argumente bauen“-Button in den Server-Einstellungen. Wähle Garbage Collector, justiere Heap-Regionen, aktiviere AppCDS oder JFR — ohne Flags auswendig zu lernen. Vorgaben: Aikar's Rezept, GTNH-Klasse, ZGC für große Heaps und mehr."
    override val settingsAdaptiveMemory         = "Adaptiver Speicher"
    override val settingsAdaptiveMemoryDesc     = "Verfeinert den Heap jeder Instanz anhand des realen Verbrauchs über einige Sitzungen, aufbauend auf dem automatischen RAM-basierten Basiswert. Festen RAM-Wert setzen, um eine Instanz auszunehmen; ausschalten, um den automatischen Basiswert ohne Lernen zu behalten."
    override val settingsMimicVersion           = "Launcher-Version-Override"
    override val settingsMimicVersionDesc       = "Fixiert den Versions-String, der an den Upstream im Handshake und User-Agent gesendet wird. Leer lassen für den eingebauten Standard — nur setzen, wenn der Upstream seine Versions-Anforderung schneller anhebt als Nexiras Release-Zyklus. Wirkt beim nächsten Protokoll-Aufruf nach Speichern, kein Neustart nötig."
    override fun settingsMimicVersionPlaceholder(default: String) = "Standard: $default"
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Synchronisiere $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB MB"
    override val widgetProgressTitle = "Hintergrundaktivität"
    override val widgetProgressIdle = "Momentan wird nichts heruntergeladen."
    override fun widgetTabDefaultLabel(index: Int) = "Tab $index"

    // April Fools
    override fun aprilCloseTitle(escapes: Int) = when {
        escapes == 0 -> "Warte mal kurz..."
        escapes < 3  -> "Bist du sicher?"
        escapes < 6  -> "Bitte... wir hatten so eine tolle Zeit"
        escapes < 8  -> "Das wird jetzt peinlich für uns beide"
        else         -> "Na gut. Ich gebe auf."
    }

    override fun aprilCloseBody(escapes: Int) = when {
        escapes == 0 -> "Launcher hat heute so hart gearbeitet. Verlässt du es wirklich?"
        escapes < 3  -> "Alles was du brauchst ist hier. Der Knopf ist nur... schüchtern."
        escapes < 6  -> "Fluchtversuche: $escapes. Der Knopf kann nicht ewig fliehen."
        escapes < 8  -> "Du bist sehr beharrlich. Der Knopf wird müde. Fast geschafft..."
        else         -> "Du gewinnst. Du bist ein unglaublich ausdauernder Mensch."
    }

    override val aprilCloseStay      = "Bleiben"
    override val aprilCloseClose     = "Schließen"
    override val aprilCloseSurrender = "Schließen (endlich)"
    override val aprilCloseHideTray  = "Im Tray verstecken"
    override fun aprilCloseEscapeCount(current: Int, max: Int) =
        "Der Schließen-Knopf ist $current / $max Mal geflohen"

    // --- 2FA (TOTP) — #159 ---
    override val auth2faTitle           = "Zwei-Faktor-Authentifizierung"
    override val auth2faPrompt          = "Geben Sie den 6-stelligen Code aus Ihrer Authenticator-App ein, um die Anmeldung abzuschließen."
    override val auth2faPlaceholder     = "000000"
    override val auth2faSubmit          = "Bestätigen"
    override val auth2faCancel          = "Abbrechen"
    override val auth2faInvalid         = "Falscher Code. Bitte erneut versuchen."
    override val auth2faExpired         = "Die 2FA-Sitzung ist abgelaufen. Bitte erneut anmelden."

    override val auth2faUnsupportedTitle   = "Leider funktioniert 2FA hier nicht"
    override val auth2faUnsupportedBody    = "Wir können 2FA leider nicht unterstützen. Unsere Protokolle weichen stark von denen ab, die Smartycraft nutzt. 2FA ist bei uns technisch dabei, aber sobald du spielen willst, kommen einfach Fehler. Bitte deaktiviere 2FA in deinem Konto auf der Website."
    override val auth2faUnsupportedDismiss = "Verstanden"

    // --- SSL Warning ---
    override val sslWarningTitle        = "Serverzertifikat abgelaufen"
    override val sslWarningBody         = "Das SSL-Zertifikat des Servers ist abgelaufen. Die Verbindung ist möglicherweise unsicher — die Identität des Servers kann nicht verifiziert werden. Auf eigenes Risiko fortfahren?"
    override val sslWarningConnectAnyway = "Trotzdem verbinden"
    override val sslWarningCancel       = "Abbrechen"
    override val sslWarningTrustPrompt  = "Diesem Server vertrauen für:"
    override val sslWarningTrustHour    = "1 Stunde"
    override val sslWarningTrust30Days  = "30 Tage"
    override val sslWarningTrustAlways  = "Immer"

    override val settingsSectionNetwork = "Netzwerk"
    override val sslBypassListTitle     = "Aktive SSL-Bypässe"
    override val sslBypassNoEntries     = "Keine aktiven Bypässe"
    override val sslBypassRevoke        = "Widerrufen"
    override fun sslBypassExpiresAt(formatted: String) = "Läuft ab: $formatted"

    override val settingsForceProxyTitle = "Nur Proxy verwenden"
    override val settingsForceProxyDesc  = "Direktverbindung überspringen und alle Anfragen über den SmartyCraft-SOCKS-Proxy leiten. Aktivieren Sie dies, wenn in Ihrem Netzwerk Direktverbindungen blockiert werden."

    override val settingsSectionSmarty           = "Smarty-Server"
    override val settingsOpenSmrtHelperTitle      = "Alternativen smrt-Netzwerk-Helfer verwenden"
    override val settingsOpenSmrtHelperDesc       = "Ersetzt den originalen Smarty-Mod auf Smarty-Servern durch unseren quelloffenen Helfer. Dieselben Netzwerkfunktionen, aber ohne Überwachung. Gibt es für die Spielversion keinen Ersatz, wird der Start blockiert statt den Original-Mod auszuführen."
    override val settingsStrictModCheckTitle      = "Genaue Mod-Prüfung"
    override val settingsStrictModCheckDesc       = "Löscht nach der Synchronisierung alles im Mods-Ordner, was der Server nicht angefordert hat. Hält die Installation sauber, entfernt aber auch von Hand hinzugefügte Mods."
    override val settingsNetworkAgentTitle        = "Netzwerk-Agent verwenden"
    override val settingsNetworkAgentDesc         = "Richtet die Anmeldung des Spiels beim Start auf SmartyCraft aus: den Server-Beitritt und die Skin-Prüfung. Der Beitritt läuft dann über SmartyCraft und Skins werden weiterhin geladen, ohne SmartyCrafts gepatchte Anmelde-Bibliothek einzuspielen. Nötig, um SmartyCraft-Servern beizutreten."
    override val settingsSmartyAuthLibTitle       = "SmartyCrafts Anmelde-Bibliothek verwenden"
    override val settingsSmartyAuthLibDesc        = "Der ältere Weg: SmartyCrafts gepatchte Anmelde-Bibliothek aus dessen Client nehmen und statt der originalen in die Sammlung legen. Durch den Netzwerk-Agenten oben ersetzt und als Reserve behalten. Lässt sich die Datei nicht beziehen, wird der Start blockiert. Standardmäßig aus."

    override val settingsSectionDataDir       = "Datenverzeichnis"
    override val settingsDataDirCurrent       = "Aktueller Pfad:"
    override val settingsDataDirMove          = "Verschieben..."
    override val settingsDataDirPickerTitle   = "Neuen Speicherort für Nexira-Daten wählen"
    override val settingsDataDirConfirmTitle  = "Datenverzeichnis verschieben?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Nexira wird die Daten verschieben:\nvon: $source\nnach: $target\n\nDie Verschiebung wird beim nächsten Start angewendet."
    override val settingsDataDirRestartRequired = "Neustart erforderlich — Nexira wendet die Verschiebung beim nächsten Start an"
    override val settingsDataDirQuitNow         = "Jetzt beenden"
    override val settingsDataDirErrorSamePath   = "Das ist bereits das aktuelle Verzeichnis — wähle einen anderen Ordner"
    override val settingsDataDirErrorNotEmpty   = "Zielordner ist nicht leer — wähle einen leeren Ordner oder lösche dessen Inhalt"
    override fun settingsDataDirErrorPickerFailed(reason: String) =
        "Ordnerauswahl konnte nicht geöffnet werden: $reason"

    // ── JVM Args Builder ────────────────────────────────────────────────
    override val jvmTitle    = "JVM-Argument-Builder"
    override val jvmSubtitle = "Wähle ein Preset oder stelle Flags manuell zusammen. Das Ergebnis landet in jvmArgs."
    override val jvmPresetsHeader = "Presets"
    override val jvmTabGc      = "GC"
    override val jvmTabTuning  = "G1 / Z / Shenandoah"
    override val jvmTabCds     = "AppCDS"
    override val jvmTabJit     = "JIT"
    override val jvmTabPerf    = "Performance"
    override val jvmTabJfr     = "JFR"
    override val jvmTabCustom  = "Eigene"
    override val jvmCancel     = "Abbrechen"
    override val jvmApply      = "In jvmArgs übernehmen"
    override fun jvmPreviewFlagsCount(n: Int) = "Vorschau ($n ${twoFormPlural(n, "Flag", "Flags")})"

    override val jvmGcHeader            = "Garbage Collector"
    override val jvmGcG1Hint            = "Empfohlen für modifiziertes MC, Heap 4-32 GB."
    override val jvmGcZHint             = "Pausen unter einer Millisekunde. Java 17+, Heap ab 16 GB. Generational auf Java 21+."
    override val jvmGcShenandoahHint    = "Concurrent low-pause aus OpenJDK / Liberica. Java 17+."
    override val jvmGcParallelHint      = "Throughput-orientiert. Lange Stop-the-World-Pausen. Fast nie die richtige Wahl."
    override val jvmGcSerialHint        = "Einkernig. Nur für winzige Heaps (< 1 GB)."

    override val jvmG1Header                  = "G1GC-Tuning"
    override val jvmG1MaxPauseMillisHint      = "Zielpausenzeit. Niedriger = häufigere Sammlungen."
    override val jvmG1RegionSizeHint          = "Regionsgröße in MB. Größer = weniger Regionen, weniger Metadaten."
    override val jvmG1NewSizePercentHint      = "Mindest-Young-Generation als % vom Heap. Aikar: 30."
    override val jvmG1MaxNewSizePercentHint   = "Maximale Young-Generation als % vom Heap. Aikar: 40."
    override val jvmG1IhopHint                = "Wann Mixed-GC startet. Aikar: 15 (früh). Standard: 45."
    override val jvmG1ParallelRefProcHint     = "Referenzen parallel verarbeiten. Reiner Gewinn auf Mehrkern-CPUs."
    override val jvmG1PerfDisableSharedMemHint = "Kein /tmp/hsperfdata. Bricht VisualVM, ist aber sauberer für die Disk."

    override val jvmZHeader            = "ZGC-Tuning"
    override val jvmZGenerationalHint  = "Nur Java 21+. Teilt den Heap in Young / Old. Deutlich besser als non-generational."

    override val jvmShenandoahHeader        = "Shenandoah-Heuristik"
    override val jvmShenandoahAdaptiveHint  = "Standard. Balanciert Pause vs. Throughput."
    override val jvmShenandoahStaticHint    = "Sammlung an festen Schwellwerten auslösen."
    override val jvmShenandoahCompactHint   = "Aggressive Kompaktierung. Besser bei Speicherfreigabe."
    override val jvmShenandoahAggressiveHint = "Kontinuierliche Sammlung. Hohe Throughput-Kosten."

    override fun jvmTuningNotApplicable(gcName: String) =
        "Für $gcName ist kein Tuning verfügbar. Wechsle auf G1, Z oder Shenandoah im GC-Tab."

    override val jvmCdsHeader            = "Application Class Data Sharing"
    override val jvmCdsIntro             = "Klassenmetadaten zwischen Starts cachen. Für Modpacks mit 200+ Mods spart das 1-3 Sekunden pro Kaltstart nach dem ersten."
    override val jvmCdsModeDisabledLabel = "Deaktiviert"
    override val jvmCdsModeDisabledHint  = "Kein CDS. Standard."
    override val jvmCdsModeAutoLabel     = "Auto-Archiv (Java 19+)"
    override val jvmCdsModeAutoHint      = "JVM verwaltet das Archiv beim Beenden automatisch. Kein Pfad nötig."
    override val jvmCdsModeArchiveLabel  = "Beim Beenden archivieren"
    override val jvmCdsModeArchiveHint   = "Archiv beim Herunterfahren in den angegebenen Pfad schreiben."
    override val jvmCdsModeUseLabel      = "Bestehendes Archiv nutzen"
    override val jvmCdsModeUseHint       = "Vorgefertigtes Archiv aus dem angegebenen Pfad lesen."
    override val jvmCdsArchivePathLabel  = "Archiv-Pfad"

    override val jvmJitHeader        = "JIT-Compiler"
    override val jvmJitTieredHint    = "Ein = Aufwärmen via Interpreter → C1 → C2 (Standard). Aus = nur C2, langsamerer Start."
    override val jvmJitCodeCacheHint = "Größe des JIT-Code-Caches. JVM-Standard ist 240. Modifiziertes MC profitiert oft von 512+."

    override val jvmPerfHeader                  = "Performance- und OS-Flags"
    override val jvmPerfAlwaysPreTouchHint      = "Jede Heap-Seite beim Start anfassen. Langsamerer Start, konsistentere Laufzeit."
    override val jvmPerfDisableExplicitGcHint   = "System.gc() zu No-op machen. Manche Legacy-Mods missbrauchen es. Fast immer ein Gewinn."
    override val jvmPerfUseLargePagesHint       = "Benötigt vorab via sysctl allozierte Hugepages. ~2-5% Performance-Gewinn wenn richtig eingerichtet."
    override val jvmPerfTransparentHugePagesHint = "Einfacher als UseLargePages. Verursacht Latenz-Spitzen bei Defrag. Trade-off."
    override val jvmPerfNumaHint                = "NUMA-bewusste Allokation. Nur auf Multi-Socket-Systemen sinnvoll."
    override val jvmPerfHeapDumpHint            = "Heap-Dump bei OOM schreiben. Entscheidend für Diagnose."
    override val jvmPerfExitOnOomHint           = "Bei OOM beenden statt weiterzuhumpeln. Verhindert Zombie-Spielstand."

    override val jvmJfrHeader               = "Java Flight Recorder"
    override val jvmJfrIntro                = "Zeichnet JVM-Interna auf (Allokationen, GC, Threads, Locks). Die resultierende .jfr in JDK Mission Control oder IntelliJ zur Analyse öffnen."
    override val jvmJfrEnableLabel          = "JFR-Aufzeichnung aktivieren"
    override val jvmJfrEnableHint           = "Default-Settings = ~1% Overhead. Profile-Settings = ~5%, erfasst Method-Level."
    override val jvmJfrDurationLabel        = "Dauer (Minuten)"
    override val jvmJfrSettingsHeader       = "Settings-Preset"
    override val jvmJfrSettingsDefaultHint  = "Geringer Overhead, geeignet für normales Spiel."
    override val jvmJfrSettingsProfileHint  = "Method-Level-Profiling. ~5% Overhead."
    override val jvmJfrOutputPathLabel      = "Ausgabepfad der .jfr (optional)"

    override val jvmCustomHeader = "Eigene Flags"
    override val jvmCustomIntro  = "Zusätzliche Flags werden wortwörtlich angehängt. Für einmalige Experimente oder Vendor-Knöpfe, die wir noch nicht im UI haben. Durch Leerzeichen getrennt."
    override val jvmCustomLabel  = "Zusätzliche Argumente"

    // --- Data dir migration UI ---
    override val migrationWelcome      = "Willkommen bei Nexira"
    override val migrationDescription  = "Nexira heißt jetzt Nexira. Vor dem Start müssen deine vorhandenen Daten an den neuen Ort kopiert werden. Der alte Ordner bleibt als Sicherung unberührt; lösche ihn manuell, sobald alles funktioniert."
    override val migrationFromHeader   = "Von"
    override val migrationToHeader     = "Nach"
    override fun migrationSize(megabytes: Int, files: Int) =
        "$megabytes MB, $files ${twoFormPlural(files, "Datei", "Dateien")}"
    override val migrationStart        = "Jetzt migrieren"
    override val migrationInProgress   = "Migration zu Nexira"
    override fun migrationCurrentFile(file: String) = "Kopiere $file"
    override fun migrationProgressBytes(doneMb: Int, totalMb: Int) = "$doneMb MB von $totalMb MB"
    override val migrationCompletedTitle = "Migration abgeschlossen"
    override val migrationCompletedBody  = "Starte Nexira neu, um deine migrierten Daten zu nutzen."
    override val migrationFailedTitle    = "Migration fehlgeschlagen"
    override fun migrationFailedBody(error: String) = "Einige Dateien konnten nicht kopiert werden: $error"
    override val migrationRetry = "Wiederholen"
    override val migrationQuit  = "Nexira beenden"

    override val placeholderNotImplemented = "Noch nicht implementiert..."
    override val placeholderHint           = "Dieser Bildschirm ist reserviert, bis die Atelier-Arbeit ankommt."

    override val navLibrary = "Bibliothek"
    override val navBrowse  = "Durchsuchen"

    override val settingsHomeViewTitle   = "Startansicht"
    override val settingsHomeViewSub     = "Probiere die neue Library-first-Ansicht neben dem klassischen Dashboard. Jederzeit umschaltbar."
    override val settingsHomeViewClassic = "Klassisch"
    override val settingsHomeViewLibrary = "Library (Alpha)"
    override val settingsHomeViewNew     = "Neu (Prototyp)"

    override val settingsUiStyleTitle    = "UI-Stil"
    override val settingsUiStyleSub      = "Wechsle Form / Oberfläche / Bewegung unabhängig von der Farbpalette. Celestia ist die aktuelle abgerundete Glasoptik; Brut ist hart und flach."
    override val settingsUiStyleCelestia = "Celestia"
    override val settingsUiStyleBrut     = "Brut"

    // --- Auswahlstil der linken Leiste ---
    override val navSelectionTitle        = "Stil des aktiven Eintrags"
    override val navSelectionSub          = "Wie der aktive Eintrag in der linken Leiste hervorgehoben wird"
    override val navStylePill             = "Kapsel"
    override val navStyleSquare           = "Quadrat"
    override val navStyleCircle           = "Kreis"
    override val navStyleBar              = "Balken"
    override val navStyleDot              = "Punkt"
    override val navStyleNone             = "Ohne"
    override val navSelectionOutlineIcons = "Inaktive Symbole als Umriss"
    override val navSelectionAccent       = "Hervorhebungsfarbe"
    override val navHoverHighlight         = "Hervorhebung beim Überfahren"

    override val settingsCategoryAppearance   = "Erscheinungsbild"
    override val settingsCategoryNetwork      = "Netzwerk"
    override val settingsCategorySmarty       = "Smarty"
    override val settingsCategoryExperimental = "Experimentell"
    override val settingsCategoryAdvanced     = "Erweitert"
    override val settingsCategoryDiagnostics  = "Diagnose"
    override val settingsCategoryConsole      = "Konsole"
    override val consoleSecDisplay            = "Anzeige"
    override val consoleSecColors             = "Severity-Farben"
    override val consoleSecFontSize           = "Schriftgröße"
    override val consoleSecWrap               = "Zeilenumbruch"
    override val consoleSecGutter             = "Severity-Leiste"
    override val consoleSecTimestamps         = "Zeitstempel"
    override val consoleSecBuffer             = "Zeilenpuffer"
    override val consoleSecColorInfo          = "Info"
    override val consoleSecColorWarn          = "Warn"
    override val consoleSecColorError         = "Error"
    override val consoleSecColorAuto          = "Auto"
    override val consoleSecApplyNote          = "Änderungen greifen beim nächsten Öffnen der Konsole."
    override val consoleSecHighlightRules     = "Hervorhebungsregeln"
    override val consoleSecFilterRules        = "Filter / Stumm"
    override val consoleSecAddRule            = "Regel hinzufügen"
    override val consoleSecRulePattern        = "Muster"
    override val consoleSecRegex              = "regex"
    override val consoleSecBold               = "Fett"
    override val consoleSecRulesEmpty         = "Noch keine Regeln."
    override val consoleSecArt                 = "Leere-Konsole-Art"
    override val consoleSecArtAdd              = "Art hinzufügen"
    override val consoleSecArtPaste            = "ASCII- oder Braille-Art einfügen"
    override val consoleSecArtEmpty            = "Noch keine eigene Art."

    override val profileCategoryAccount = "Konto"
    override val profileCategorySignIn      = "Anmelden"
    override val profileCategorySecurity    = "Sicherheit"
    override val profileForgetSavedSignIn   = "Gespeicherte Anmeldung vergessen"
    override val profileSecurityHint        = "Deine Anmeldung ist auf diesem Gerät für die automatische Anmeldung gespeichert."
    override val accountsTitle               = "Konten"
    override val accountRemove               = "Entfernen"
    override val accountFaceLabel            = "Anzeigen als"
    override val accountFaceAuto             = "Auto"
    override val profileSignOutSmartycraft   = "Von SmartyCraft abmelden"
    override val profileSignOutMicrosoft     = "Von Microsoft abmelden"
    override val wardrobeTitle               = "Kleiderschrank"
    override val wardrobeSignedOut           = "Melde dich an, um Skins und Umhänge zu verwalten."
    override val wardrobeUpload               = "Hochladen"
    override val wardrobeApplySmartycraft     = "Anwenden (SmartyCraft)"
    override val wardrobeEmpty                = "Deine Bibliothek ist leer. Lade ein Skin-PNG hoch, um zu beginnen."
    override val wardrobeSaved               = "Gespeichert"
    override val wardrobeCapes               = "Umhänge"
    override val wardrobeApplyCape           = "Clan-Umhang setzen"
    override val wardrobeCapeClanHint        = "Umhänge gelten clanweit -- nur der Clan-Anführer kann einen setzen."
    override val wardrobeDefaults            = "Standard-Skins"
    override val wardrobePoseStand           = "Stehend"
    override val wardrobePoseWave            = "Winken"
    override val wardrobePoseSit             = "Sitzend"
    override val wardrobePoseFaceCover       = "Gesicht verdecken"
    override val wardrobePoseWalk            = "Gehen"

    override val backgroundLoopMode      = "Schleife"
    override val backgroundLoopUseCodec  = "Aus Codec"
    override val backgroundLoopForever   = "Endlos"
    override val backgroundLoopOnce      = "Einmal"

    override val customizationAccentClear     = "Überschreibung löschen"
    override val customizationSectionVisual   = "Visuell"
    override val customizationSectionColors   = "FARBÜBERSCHREIBUNGEN"
    override val customizationHexInvalid      = "Ungültiger Hex"
    override val themePickerAccentOverride    = "Akzent überschreiben (sofort)"

    override val browseTitle             = "Katalog"
    override val browseSearchPlaceholder = "Packs suchen"
    override val browseImport            = "Datei importieren"
    override val libraryAddAction        = "Pack hinzufügen"
    override val libraryNewLocalPack     = "Neues lokales Pack"
    override val libraryImportPack       = "Pack importieren"
    override val createPackName          = "Name"
    override val createPackMc            = "Minecraft-Version"
    override val createPackLoader        = "Loader"
    override val createPackLoaderVersion = "Loader-Version (optional)"
    override val createPackConfirm       = "Erstellen"
    override val createPackCancel        = "Abbrechen"
    override val createPackShowSnapshots = "Snapshots anzeigen"
    override val createPackHideSnapshots = "Snapshots ausblenden"
    override val browseEmptyTitle        = "Katalog ist leer"
    override val browseEmptyMessage      = "Der Mirror ist erreichbar, hat aber noch keine Packs veröffentlicht. Schau später wieder vorbei."
    override val browseErrorTitle        = "Mirror nicht erreichbar"
    override val browseErrorMessage      = "Mirror konnte nicht erreicht werden. Verbindung prüfen und erneut versuchen."
    override val browseRetry             = "Erneut versuchen"
    override fun modrinthCategory(id: String) = when (id) {
        "adventure"    -> "Abenteuer"
        "challenging"  -> "Anspruchsvoll"
        "combat"       -> "Kampf"
        "kitchen-sink" -> "Alles dabei"
        "lightweight"  -> "Leichtgewichtig"
        "magic"        -> "Magie"
        "multiplayer"  -> "Mehrspieler"
        "optimization" -> "Optimierung"
        "quests"       -> "Quests"
        "technology"   -> "Technik"
        else           -> humanizeCategory(id)
    }

    override val browseDetailErrorTitle    = "Pack konnte nicht geladen werden"
    override val browseDetailErrorMessage  = "Manifest konnte nicht geladen werden. Verbindung prüfen und erneut versuchen."
    override val browseDetailInstallReady  = "Bereit zur Installation"
    override val browseDetailInstallHint   = "Erstellt eine neue Instanz in deinem Datenverzeichnis."
    override val browseDetailInstallButton = "Installieren"
    override val browseDetailTagsTitle     = "Tags"
    override val browseDetailAboutTitle       = "Über dieses Pack"
    override fun browseDetailAbout(mods: Int, assets: Int) =
        "Dieses Pack enthält $mods ${twoFormPlural(mods, "Mod", "Mods")} und $assets ${twoFormPlural(assets, "Asset", "Assets")}."
    override val browseDetailAboutNote        = "Eine ausführliche Beschreibung erscheint hier, sobald der Mirror sie zum Manifest hinzufügt."
    override val browseDetailCompatTitle      = "Kompatibilität"
    override val browseDetailCompatMc         = "Minecraft"
    override val browseDetailCompatLoader     = "Loader"
    override val browseDetailCompatJava       = "Runtime"
    override val browseDetailVersionTitle     = "Version"

    override val browseDetailInstallRunningTitle  = "Installation läuft..."
    override fun browseDetailInstallProgress(filename: String, current: Int, total: Int) =
        "$filename  ($current / $total)"
    override val browseDetailInstallStarting      = "Wird gestartet..."
    override val browseDetailInstallDoneTitle     = "Installiert"
    override val browseDetailInstallDoneHint      = "Zur Library hinzugefügt."
    override val browseDetailInstallOpenLibrary   = "In Library öffnen"
    override val browseDetailInstallFailedTitle   = "Installation fehlgeschlagen"
    override val browseDetailInstallFailedGeneric = "Installation aus unbekanntem Grund fehlgeschlagen."

    override val fileBrowserNoRoot          = "Diese Instanz hat noch keine Dateien auf der Festplatte."
    override val fileBrowserPickAFile       = "Wähle links eine Datei für die Vorschau."
    override val fileBrowserBinaryHint      = "Binärdatei -- keine Vorschau verfügbar."
    override val fileBrowserOpenExternally  = "Extern öffnen"
    override fun fileBrowserTextTruncated(maxKb: Long) =
        "Vorschau auf die ersten $maxKb KB beschränkt. Öffne extern, um die ganze Datei zu sehen."
    override val fileBrowserEmptyFolder      = "(leer)"

    override val contentTabUnsupportedOrigin    = "Inhaltsansicht ist heute nur für Mirror-veröffentlichte Packs verfügbar. Andere Quellen folgen in einem Folge-PR."
    override val contentDetachTitle             = "Verfolgtes Pack"
    override val contentDetachBody              = "Loslösen, um Mods frei zu aktivieren, zu löschen und hinzuzufügen."
    override val contentTrackedOptionalBody     = "Optionale Mods werden hier umgeschaltet. Loslösen, um hinzuzufügen und zu entfernen."
    override val contentDetachButton            = "Loslösen"
    override val contentAddFiles                = "Dateien hinzufügen"
    override val contentFindProjects            = "Projekte finden"
    override val contentSearchPlaceholder       = "Inhalt durchsuchen..."
    override val contentEmpty                   = "Nichts gefunden"
    override val contentFilterAll               = "Alle"
    override val contentFilterMods              = "Mods"
    override val contentFilterResourcePacks     = "Ressourcen"
    override val contentFilterShaderPacks       = "Shader"
    override val contentDeleteTitle             = "Datei löschen?"
    override val contentDeleteBody              = "Die Datei wird endgültig von der Festplatte entfernt."
    override val contentActionDetails           = "Details"
    override val contentActionOpenPage          = "Seite öffnen"
    override val contentDetailAuthors           = "Autoren"
    override val contentDetailSize              = "Größe"
    override val contentTabFetchErrorTitle      = "Pack-Inhalt konnte nicht geladen werden"
    override val contentTabFetchErrorGeneric    = "Das Mirror-Manifest konnte nicht geladen werden."
    override val contentTabRetry                = "Erneut versuchen"
    override val contentTabRoleSection          = "Rollen-Slots"
    override fun contentTabOptionalSection(count: Int) = "Optionale Mods ($count)"
    override fun contentTabIncompatibleWith(name: String) = "Inkompatibel mit $name"
    override fun contentTabModsSection(count: Int) = "Mods ($count)"
    override fun contentTabAssetsSection(count: Int) = "Assets ($count)"
    override val contentTabResolverIssuesTitle  = "Manifest-Probleme erkannt"
    override fun contentTabResolverMissing(count: Int) = twoFormPlural(
        count,
        "$count Abhängigkeit verweist auf einen Mod, der nicht in diesem Pack ist.",
        "$count Abhängigkeiten verweisen auf Mods, die nicht in diesem Pack sind.",
    )
    override fun contentTabResolverCycles(count: Int) = twoFormPlural(
        count,
        "$count Abhängigkeits-Zyklus gefunden — Pack-Autor sollte den requires-Graph prüfen.",
        "$count Abhängigkeits-Zyklen gefunden — Pack-Autor sollte den requires-Graph prüfen.",
    )
    override val contentTabRoleRecipeViewer     = "Rezept-Übersicht"
    override val contentTabRoleMinimap          = "Minimap"
    override val contentTabRoleBlockInfo        = "Block-Info"
    override val contentTabRolePerformance      = "Performance"
    override val contentTabRoleInventorySearch  = "Inventar-Suche"
    override fun contentTabRoleAltCount(count: Int) =
        if (count == 0) "einzige Option" else "$count ${twoFormPlural(count, "Alternative", "Alternativen")}"
    override val contentTabRoleAlternativesHeader = "Alternativen in diesem Pack"
    override val contentTabModNoDescription     = "Noch keine Beschreibung im Manifest."
    override fun contentTabModLicensePrefix(license: String) = "Lizenz: $license"
    override val contentTabModUrlLabel          = "Mod-Seite"
    override fun contentTabModSizeLabel(kb: Long) = "$kb KB"
    override fun contentTabModDependencies(count: Int) = "Abhängigkeiten ($count)"
    override fun contentTabModMissingCount(count: Int) = "$count fehlt"
    override val contentTabDepOptional          = "optional"
    override val contentTabDepMissing           = "fehlt"
    override val contentTabModOptional          = "optional"
    override fun contentTabLibrariesSection(count: Int)     = "Bibliotheken ($count)"
    override fun contentTabResourcePacksSection(count: Int) = "Ressourcenpakete ($count)"
    override fun contentTabShaderPacksSection(count: Int)   = "Shader-Pakete ($count)"
    override fun contentTabConfigsSection(count: Int)       = "Konfigurationen ($count)"
    override fun contentTabOtherAssetsSection(count: Int)   = "Sonstige Dateien ($count)"
    override fun contentTabAssetSizeLabel(kb: Long) = "$kb KB"
    override val contentTabAssetOptional        = "optional"
    override val contentTabAssetNoDescription   = "Noch keine Beschreibung im Manifest."

    override fun worldsTabLocalSection(count: Int) = "Lokale Welten ($count)"
    override val worldsTabLocalEmpty            = "Noch keine gespeicherten Welten. Starte eine neue Einzelspieler-Welt im Spiel und sie erscheint hier."
    override fun worldsTabServersSection(count: Int) = "Server aus dem Verlauf ($count)"
    override val worldsTabServersEmpty          = "Keine Server im Multiplayer-Verlauf dieser Instanz."
    override val worldsTabErrorTitle            = "Welten konnten nicht gelesen werden"
    override val worldsTabErrorMessage          = "Die Spielstände oder die Serverliste dieser Instanz konnten nicht gelesen werden. Die Dateien sind möglicherweise beschädigt oder nicht lesbar."
    override fun worldsTabLastPlayed(rel: String) = "Zuletzt gespielt: $rel"
    override val worldsTabServerHiddenLabel     = "Aus der Spielinternen Liste ausgeblendet"
    override val worldsTabGameSurvival          = "Überleben"
    override val worldsTabGameCreative          = "Kreativ"
    override val worldsTabGameAdventure         = "Abenteuer"
    override val worldsTabGameSpectator         = "Zuschauer"
    override val worldsTabGameUnknown           = "Unbekannter Modus"
    override val worldsTabDimOverworld          = "Oberwelt"
    override val worldsTabDimNether             = "Nether"
    override val worldsTabDimEnd                = "Ende"
    override val worldsTabDimOther              = "Sonstige"

    override val packDetailTabContent           = "Inhalt"
    override val packDetailTabFiles             = "Dateien"
    override val packDetailTabWorlds            = "Welten"
    override val packDetailTabLogs              = "Logs"
    override val packDetailTabSettings          = "Einstellungen"
    override val packVersionSection             = "Version und Updates"
    override val packVersionInstalled           = "Installierter Build"
    override val packVersionCheck               = "Prüfen"
    override val packVersionWorking             = "Arbeite..."
    override val packVersionUpToDate            = "Auf dem neuesten Build"
    override fun packVersionAvailable(version: String) = "Build $version verfügbar"
    override val packVersionSafe                = "Sicheres Update"
    override val packVersionNeedsCare           = "Ändert Minecraft oder den Loader — ein Snapshot wird zuerst erstellt"
    override val packVersionUpdateNow           = "Jetzt aktualisieren"
    override val packVersionFollowLatest        = "Neuestem folgen"
    override val packVersionFollowLatestDesc    = "Dieses Pack automatisch auf den neuesten Build aktualisieren."
    override val packVersionOtherBuilds         = "Andere Versionen"
    override val packVersionSwitch              = "Wechseln"
    override val packVersionCurrentTag          = "Aktuell"
    override val packVersionUpdateBadge         = "Update"
    override val packVersionCheckFailed         = "Updates konnten nicht geprüft werden"

    override val packVersionsTitle              = "Paketversionen"
    override val packVersionsAllVersions        = "Alle Versionen"
    override val packVersionsLatestTag          = "Neueste"
    override fun packVersionsRebuilds(n: Int)   = "+$n ${twoFormPlural(n, "Rebuild", "Rebuilds")} ohne Änderungen"
    override val packVersionsChannelRelease     = "Release"
    override val packVersionsChannelBeta        = "Beta"
    override val packVersionsChannelAlpha       = "Alpha"
    override fun packVersionsCounts(mods: Int, assets: Int) =
        "$mods ${twoFormPlural(mods, "Mod", "Mods")}, $assets ${twoFormPlural(assets, "Asset", "Assets")}"
    override val packVersionsDiffVsPrevious     = "Zum vorherigen Build"
    override val packVersionsDiffVsInstalled    = "Zum installierten"
    override val packVersionsIdentical          = "Keine Dateiänderungen: Rebuild mit neuem Label"
    override val packVersionsFirstBuild         = "Erster Build des Pakets, kein Vergleich möglich"
    override fun packVersionsAdded(n: Int)      = "Hinzugefügt ($n)"
    override fun packVersionsUpdated(n: Int)    = "Aktualisiert ($n)"
    override fun packVersionsRemoved(n: Int)    = "Entfernt ($n)"
    override val packVersionsSectionMods        = "Mods"
    override val packVersionsSectionAssets      = "Paketdateien"
    override val packVersionsSectionPack        = "Eigenschaften"
    override val packVersionsSwitchTo           = "Zu diesem Build wechseln"
    override val packVersionsConfirmTitle       = "Version wechseln?"
    override fun packVersionsConfirmBody(from: String, to: String) =
        "Die Instanz wechselt von $from zu $to. Vorher wird ein Wiederherstellungspunkt angelegt."
    override fun packVersionsPlanCounts(add: Int, update: Int, remove: Int) = "Änderungen: +$add, ~$update, -$remove"
    override fun packVersionsConflicts(n: Int) =
        "$n ${twoFormPlural(n, "Konflikt", "Konflikte")} mit eigenen Änderungen: die Paketdateien landen daneben als .new"
    override fun packVersionsApplying(current: Int, total: Int, name: String) = "Anwenden $current/$total: $name"
    override fun packVersionsApplied(version: String) = "Fertig: jetzt auf Build $version"
    override fun packVersionsFailed(reason: String) = "Fehlgeschlagen: $reason"
    override val packVersionsRetry              = "Erneut versuchen"
    override val packVersionsLoadError          = "Mirror nicht erreichbar, Versionsliste nicht geladen"

    override val packSettingsTitle              = "Pack-Einstellungen"
    override val packSettingsClose              = "Schließen"
    override val packSettingsCategoryGeneral    = "Allgemein"
    override val packSettingsCategoryRuntime    = "Start"
    override val packSettingsCategoryVersion    = "Version"
    override val packSettingsCategoryContent    = "Inhalt"
    override val packSettingsCategoryData       = "Daten"
    override val packSettingsIdentity           = "Identität"
    override val packSettingsName               = "Name"
    override val packSettingsNamePlaceholder    = "Pack-Name"
    override val packSettingsNotes              = "Notizen"
    override val packSettingsNotesPlaceholder   = "Notizen für dich"
    override val packSettingsSource             = "Quelle"
    override fun packSettingsForkedFrom(name: String) = "Abgeleitet von $name"
    override val packSettingsPackId             = "Pack-ID"
    override val packSettingsMemory             = "Arbeitsspeicher"
    override val packSettingsEnvironment        = "Umgebung"
    override val packSettingsJava               = "Java"
    override fun packSettingsJavaManaged(major: Int) = "Verwaltet -- Java $major"
    override val packSettingsJavaCustom         = "Eigener Java-Pfad"
    override val packSettingsJavaPathPlaceholder = "/pfad/zu/bin/java"
    override val packSettingsJavaReset          = "Verwaltet nutzen"
    override val packSettingsJvmArgs            = "JVM-Argumente"
    override val packSettingsJvmArgsDefault     = "Standard"
    override val packSettingsJvmArgsEdit        = "Bearbeiten"
    override val packSettingsWindow             = "Spielfenster"
    override val packSettingsWindowOverride     = "Eigene Fenstergröße"
    override val packSettingsWindowOverrideDesc = "Sonst behält der Client seine gemerkte Größe"
    override val packSettingsWidth              = "Breite"
    override val packSettingsHeight             = "Höhe"
    override val packSettingsFullscreen         = "Vollbild"
    override val packSettingsOptional           = "Optionaler Inhalt"
    override val packSettingsOptionalNone       = "Dieses Pack hat keine Optionen"
    override val packSettingsDependencies       = "Abhängigkeiten"
    override val packSettingsDependenciesNone   = "Nichts fehlt"
    override fun packSettingsMissing(name: String) = "Fehlt: $name"
    override val packSettingsContentUnavailable = "Inhaltsliste ohne Manifest nicht verfügbar"
    override val packSettingsContentLoading     = "Lädt"
    override val packSettingsStorage            = "Speicherort"
    override val packSettingsFolder             = "Pack-Ordner"
    override val packSettingsOpenFolder         = "Öffnen"
    override val packSettingsSizeComputing      = "Größe wird berechnet"
    override val packSettingsDetach             = "In lokale Kopie lösen"
    override val packSettingsDetachDesc         = "Werde deine eigene Kopie; Herkunft bleibt erhalten"
    override val packSettingsDetachAction       = "Lösen"
    override val packSettingsRepair             = "Dateien prüfen und reparieren"
    override val packSettingsRepairDesc         = "Das Pack erneut mit dem Mirror abgleichen"
    override val packSettingsRepairAction       = "Reparieren"
    override val packSettingsRepairDone         = "Dateien neu abgeglichen"
    override val packSettingsDangerZone         = "Gefahrenzone"
    override val packSettingsDelete             = "Pack löschen"
    override val packSettingsDeleteDesc         = "Die Instanzdateien werden endgültig gelöscht"
    override val packVersionSnapshots           = "Wiederherstellungspunkte"
    override val packVersionRestore             = "Wiederherstellen"
    override val packVersionSnapshotsHint       = "Ein Snapshot bewahrt deine Änderungen; wird vor einem strukturellen Update erstellt"
    override val consoleSessionLive             = "Allgemein"
    override fun consoleSessionPickerLabel(current: String) = "Log: $current"

    override val packDetailReadyTitle           = "Bereit zum Spielen"
    override fun packDetailInstanceDirHint(dirName: String) = "Instanz-Ordner: instances/$dirName"
    override val packDetailPlay                 = "Spielen"
    override val packDetailPlayLoginRequired    = "Anmelden zum Spielen"
    override val packDetailNotFoundTitle        = "Instanz nicht gefunden"
    override val packDetailNotFoundHint         = "Möglicherweise in einem anderen Fenster entfernt."
    override val packDetailNotFoundBack         = "Zurück zur Library"

    // --- Notification subsystem ---
    override val notificationExpandHistory   = "Benachrichtigungsverlauf einblenden"
    override val notificationCollapseHistory = "Benachrichtigungsverlauf ausblenden"
    override val notificationDismiss         = "Benachrichtigung schliessen"
    override val notifHistoryEmpty           = "Noch keine Nachrichten"
    override val notifHistoryClear           = "Leeren"
    override val notifDoNotDisturb           = "Nicht stören"
    override fun notifGroupCount(count: Int) = "×$count"
    override fun notifCountTitle(count: Int) = if (count == 1) "$count Nachricht" else "$count Nachrichten"
    override fun notificationShowMore(count: Int)               = "+$count weitere"
    override fun notificationAbsoluteTime(instant: java.time.Instant): String =
        java.time.format.DateTimeFormatter
            .ofPattern("d. MMMM yyyy, HH:mm:ss", java.util.Locale.GERMAN)
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)

    override fun notifPackPreparing(packName: String)   = "Vorbereitung: $packName"
    override fun notifPackStage(stage: String)          = "Phase: $stage"
    override fun notifPackSyncing(packName: String)     = "Synchronisiere $packName"
    override fun notifPackSyncBody(current: Int, total: Int, pctLabel: String) =
        "$current/$total Dateien, $pctLabel"
    override val notifPackSyncIndeterminate             = "wird heruntergeladen..."
    override fun notifPackSyncPercent(pct: Int)         = "$pct %"
    override fun notifPackRunning(packName: String)     = "$packName laeuft"
    override fun notifPackFailed(packName: String)      = "$packName konnte nicht gestartet werden"
    override fun notifPackSessionEnded(packName: String) = "Sitzung $packName beendet"
    override fun notifInstallSyncing(packName: String)  = "Installiere $packName"
    override fun notifInstallDone(packName: String)     = "$packName installiert"
    override fun notifInstallFailed(packName: String)   = "$packName konnte nicht installiert werden"
    override fun notifInstallCancelled(packName: String) = "Installation von $packName abgebrochen"
    override val notifActionCancel                      = "Abbrechen"
    override val notifActionShowConsole                 = "Konsole anzeigen"
    override val notifActionStop                        = "Stoppen"
    override val notifActionPlayOffline                 = "Offline spielen"
    override fun notifReasonExitCode(code: Int)         = "Spiel mit Code $code beendet"
    override val notifReasonInternal                    = "Interner Fehler"
    override fun notifReasonInternalDetail(detail: String) = detail
    override val notifReasonAuthFail                    = "Anmeldung fehlgeschlagen"
    override fun notifReasonAuthFailDetail(detail: String) = detail
    override val notifReasonOfflineNoClient             = "Pack-Dateien fehlen auf der Platte"
    override val notifReasonOfflineNoManifest           = "Kein Manifest im Cache; einmal online gehen, um zu synchronisieren"
    override val notifReasonTwoFactorExpired            = "Bitte erneut anmelden, um die Anmeldedaten zu aktualisieren"
    override fun notifReasonMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY -> "Bei SmartyCraft anmelden, um dieses Pack zu spielen"
        else                                          -> "Anmeldung bei '$providerKey' nötig, um dieses Pack zu spielen"
    }

    override val notifTimeNow                           = "Jetzt"
    override fun notifTimeSeconds(seconds: Long)        = "$seconds s"
    override fun notifTimeMinutes(minutes: Long)        = "$minutes min"
    override fun notifTimeHours(hours: Long)            = "$hours h"
    override fun notifTimeDays(days: Long)              = "$days T"

    // --- Home (new) + launch tiles ---
    override val homeRecentTitle    = "Deine Modpacks"
    override val homeNoPacksTitle   = "Noch keine Modpacks"
    override val homeNoPacksBody    = "Installiere etwas über Browse, dann erscheinen deine Modpacks hier."
    override val browseOpen         = "Browse öffnen"
    override val homeQuickContinue  = "Fortsetzen"
    override val homeQuickStart     = "Starten"
    override val homeQuickButton    = "Spielen"
    override fun homeHeroPlaytime(hours: Long) = "$hours Std. gespielt"
    override val launchTileReady    = "Starten"
    override val launchTileBlocked  = "Noch nicht spielbar"

    // --- Library widgets ---
    override val libraryEmptyTitle     = "Noch leer"
    override val libraryEmptyBody      = "Installiere ein Modpack über Browse, dann erscheint es hier."
    override val libraryHeaderTitle    = "Bibliothek"
    override val libraryHeaderSubtitle = "Installierte Modpacks"

    // --- Customization widget labels ---

    // --- Layout editor: common actions ---
    override val editorClose   = "Schließen"
    override val editorCancel  = "Abbrechen"
    override val editorDelete  = "Löschen"
    override val editorReset   = "Zurücksetzen"
    override val editorUnsupportedWidget = "Nicht unterstütztes Widget"
    override val editorResetAll = "Alles zurücksetzen"
    override val editorToFront = "In den Vordergrund"
    override val editorToBack = "In den Hintergrund"
    override val widgetLabels: Map<String, String> = mapOf(
        "widget.about.credits" to "Mitwirkende und Technik",
        "widget.about.credits.title" to "Überschrift (Mitwirkende)",
        "widget.about.links.card" to "Links",
        "widget.about.links.card.title" to "Überschrift",
        "widget.about.logo" to "Logo und Version",
        "widget.about.logo.title" to "Überschrift",
        "widget.about.logo.showVersion" to "Version anzeigen",
        "widget.about.logo.showBuildDate" to "Build-Datum anzeigen",
        "widget.about.logo.showTagline" to "Untertitel anzeigen",
        "widget.about.system.card" to "System",
        "widget.about.system.card.title" to "Überschrift",
        "widget.about.update.panel" to "Updates",
        "widget.about.update.panel.title" to "Überschrift",
        "widget.appshell.region.center" to "Hauptbereich",
        "widget.appshell.region.collapsed" to "Eingeklappt",
        "widget.appshell.region.swipeToCollapse" to "Mit Wischen einklappen",
        "widget.appshell.region.frostTier" to "Glas",
        "widget.appshell.region.glassAlphaPct" to "Glas, %",
        "widget.appshell.region.left" to "Linke Leiste",
        "widget.appshell.region.top" to "Titelleiste",
        "widget.appshell.region.body" to "Hauptbereich",
        "widget.appshell.topbar.breadcrumb" to "Brotkrümel",
        "widget.appshell.topbar.heightDp" to "Höhe",
        "widget.appshell.topbar.cornerStyle" to "Eckenstil",
        "widget.appshell.topbar.groupStyle" to "Gruppierung",
        "widget.appshell.topbar.frostTier" to "Glas",
        "widget.appshell.topbar.controls" to "Fenstertasten",
        "widget.appshell.region.right" to "Rechte Leiste",
        "widget.appshell.region.showDivider" to "Trennlinie",
        "widget.appshell.region.widthDp" to "Breite (0 = flexibel)",
        "widget.appshell.rightrail.compactnews" to "Neuigkeiten",
        "widget.appshell.rightrail.compactnews.maxItems" to "Max. Einträge (0 = alle)",
        "widget.appshell.rightrail.compactnews.showTitle" to "Titel anzeigen",
        "widget.bg.enable.toggle" to "Hintergrund an/aus",
        "widget.bg.fx.animspeed" to "Animationstempo",
        "widget.bg.fx.blur" to "Unschärfe",
        "widget.bg.fx.darken" to "Abdunkeln",
        "widget.bg.fx.opacity" to "Deckkraft",
        "widget.bg.fx.parallax" to "Parallaxe",
        "widget.bg.fx.saturation" to "Sättigung",
        "widget.bg.fx.vignette" to "Vignette",
        "widget.bg.image.picker" to "Hintergrundbild",
        "widget.bg.loop.mode" to "Wiedergabeschleife",
        "widget.bg.position.x" to "Position X",
        "widget.bg.position.y" to "Position Y",
        "widget.bg.preview" to "Vorschau",
        "widget.bg.reset" to "Hintergrund zurücksetzen",
        "widget.bg.scale.mode" to "Skalierung",
        "widget.bg.tint" to "Tönung",
        "widget.container.group" to "Gruppe",
        "widget.checklist" to "Checkliste",
        "widget.checklist.add" to "Punkt hinzufügen...",
        "widget.checklist.empty" to "Noch nichts",
        "widget.checklist.hideCompleted" to "Erledigte ausblenden",
        "widget.checklist.title" to "Titel",
        "widget.container.tabs" to "Tabs",
        "widget.container.tabs.label1" to "Tab 1",
        "widget.container.tabs.label2" to "Tab 2",
        "widget.container.tabs.label3" to "Tab 3",
        "widget.container.tabs.tabCount" to "Tabs",
        "widget.home.classic.content" to "Klassisches Dashboard",
        "widget.home.new.clock" to "Uhr",
        "widget.home.new.clock.accent" to "Akzentfarbe",
        "widget.home.new.clock.faceSize" to "Zifferblattgröße",
        "widget.home.new.clock.format24h" to "24-Stunden-Format",
        "widget.home.new.clock.mode" to "Modus",
        "widget.home.new.clock.showSeconds" to "Sekunden",
        "widget.home.new.clock.title" to "Überschrift",
        "widget.home.new.hero" to "Pack-Hero-Karte",
        "widget.home.new.hero.height" to "Höhe",
        "widget.home.new.hero.showMeta" to "Metadaten",
        "widget.home.new.launchbutton" to "Startknopf",
        "widget.home.new.launchbutton.label" to "Beschriftung",
        "widget.home.new.music" to "Musikplayer",
        "widget.home.new.music.title" to "Überschrift",
        "widget.home.new.playback.mini" to "Mini-Player",
        "widget.home.new.progress" to "Hintergrundaktivität",
        "widget.home.new.progress.idleText" to "Leerlauftext",
        "widget.home.new.progress.title" to "Überschrift",
        "widget.home.new.quicklaunch" to "Schnellstart",
        "widget.home.new.quicklaunch.buttonLabel" to "Knopfbeschriftung",
        "widget.home.new.recent" to "Pack-Kacheln",
        "widget.home.new.recent.maxTiles" to "Anzahl Kacheln",
        "widget.home.new.recent.title" to "Überschrift",
        "widget.home.new.spacer" to "Abstand",
        "widget.home.new.spacer.height" to "Höhe",
        "widget.home.new.video" to "Videoplayer",
        "widget.home.new.video.url" to "Video-URL",
        "widget.home.new.welcome" to "Willkommensbanner",
        "widget.home.new.welcome.customGreeting" to "Eigener Begrüßungstext",
        "widget.home.new.welcome.showSubtitle" to "Untertitel anzeigen",
        "widget.library.body" to "Bibliotheksinhalt",
        "widget.library.body.emptyText" to "Text für leeren Zustand",
        "widget.library.body.emptyTitle" to "Titel für leeren Zustand",
        "widget.library.header" to "Bibliothekskopf",
        "widget.library.header.subtitle" to "Untertitel",
        "widget.library.header.title" to "Überschrift",
        "widget.library.header.show" to "Kopf anzeigen",
        "widget.nav.entry" to "Navigationspunkt",
        "widget.notes.scratch" to "Notizen",
        "widget.notes.scratch.placeholder" to "Schreib etwas...",
        "widget.notes.scratch.title" to "Titel",
        "widget.notifications.history" to "Nachrichtenverlauf",
        "widget.notifications.history.expandUp" to "Nach oben ausklappen",
        "widget.notifications.history.clock12h" to "12-Stunden-Format (am/pm)",
        "widget.notifications.history.verticalTime" to "Zeit gestapelt",
        "widget.profile.account.section" to "SmartyCraft",
        "widget.profile.signin" to "Microsoft",
        "widget.profile.nav" to "Profilnavigation",
        "widget.profile.skin.section" to "Skin",
        "widget.profile.skin.section.previewHeight" to "Vorschauhöhe",
        "widget.server.details.banner" to "Server-Banner",
        "widget.server.details.banner.cornerRadius" to "Eckenrundung",
        "widget.server.details.description" to "Serverbeschreibung",
        "widget.server.details.tagbar" to "Server-Tags",
        "widget.server.details.title" to "Servertitel",
        "widget.theme.picker.grid" to "Themen-Raster",
        "widget.theme.picker.preview" to "Themen-Vorschau",
    )
    override val recoverySafeModeTitle = "Oberfläche nicht wiederherstellbar"
    override val recoverySafeModeBody  = "Die Oberfläche ist mehrfach hintereinander abgestürzt. Ein Absturzbericht wurde gespeichert. Starte den Launcher neu."
    override val recoverySafeModeQuit  = "Beenden"

    override val recoveryTitle              = "Wiederherstellungsmodus"
    override val recoveryBody               = "Deaktiviere ein Modul oder setze einen beschädigten Zustand zurück, dann fortfahren. Änderungen greifen beim Neustart des Launchers."
    override val recoveryModulesHeading     = "Module deaktivieren"
    override val recoveryModuleTray         = "System-Tray"
    override val recoveryModuleNotify       = "Benachrichtigungen"
    override val recoveryModuleSkinema      = "Medien-Hintergründe"
    override val recoveryModuleKeyring      = "System-Schlüsselbund"
    override val recoveryResetsHeading      = "Zurücksetzen"
    override val recoveryResetLayout        = "Layout"
    override val recoveryResetCustomization = "Anpassung"
    override val recoveryResetSettings      = "Einstellungen"
    override val recoveryContinue           = "Normal starten"
    override val recoveryRelaunchFailed     = "Automatischer Neustart fehlgeschlagen. Öffne den Launcher erneut."
    override val recoveryRestartInApp       = "Im Wiederherstellungsmodus neu starten"
    override val thresholdStageFiles     = "dateien werden geprüft"
    override val thresholdStageNetwork   = "netzwerkstatus"
    override val thresholdStageMigration = "migrationsprüfung"
    override val thresholdStageModules   = "module werden gestartet"
    override val thresholdErrorTitle     = "start fehlgeschlagen"
    override val thresholdOpenLogs       = "log-ordner öffnen"
    override val thresholdQuit           = "beenden"
    override val recoveryReloadedNotice = "Oberfläche nach einem Fehler neu geladen"
    override val editorSave    = "Speichern"
    override val editorApply   = "Anwenden"
    override val editorExport  = "Exportieren"
    override val editorWidgets = "Widgets"

    // --- Layout editor: slot orientation ---
    override val editorSlotStack  = "Stapel"
    override val editorSlotRow    = "Reihe"
    override val editorSlotGrid   = "Raster"
    override val editorSlotCanvas = "Leinwand"
    override val editorSlotCubeGrid = "Würfel"
    override val editorSlotLayoutMenuTitle     = "Layout"
    override val editorSlotGridColumns         = "Spalten"
    override val editorSlotGridColumnsDecrease = "Weniger Spalten"
    override val editorSlotGridColumnsIncrease = "Mehr Spalten"
    override val editorSlotLayoutHandle        = "Slot-Layout"

    // --- Layout editor: prop panel ---
    override val editorResetToDefault = "Auf Standard zurücksetzen"
    override val editorBackingTitle   = "Hintergrund"
    override val editorSurfaceSettings = "Einstellungen"
    override val editorBackingGlass   = "Glas-Deckkraft"
    override val editorBackingCorner  = "Ecke"
    override val editorBackingPadding = "Abstand (alle Seiten)"
    override val editorBackingPaddingTop    = "Abstand oben"
    override val editorBackingPaddingEnd    = "Abstand rechts"
    override val editorBackingPaddingBottom = "Abstand unten"
    override val editorBackingPaddingStart  = "Abstand links"
    override val editorBackingNoGlassHint   = "Ohne Glas ist keine Unterlage sichtbar. Ecke und Abstand wirken weiterhin auf das Widget."

    // --- Layout editor: presets ---
    override val editorPresetsTitle          = "Presets"
    override val editorPresetsIntro          = "Ein Schnappschuss von Layout, Theme und Stil. Jetzt speichern, jederzeit laden."
    override val editorPresetNamePlaceholder = "Preset-Name..."
    override fun editorPresetsSaved(count: Int) = "Gespeichert ($count)"
    override val editorPresetsEmpty          = "Leer. Speichere das aktuelle Layout als erstes Preset."

    // --- Layout editor: palette ---
    override val editorPaletteHide  = "Palette ausblenden"
    override val editorPaletteHint  = "In einen Slot ziehen"
    override val editorPaletteEmpty = "Widget-Registry ist leer (Build-Fehler)."
    override val editorPaletteSearch = "Widgets suchen…"
    override val editorPaletteNoMatch = "Keine Treffer"

    // --- Layout editor: empty slot + chrome ---
    override val editorDragWidgetHere   = "Widget hierher ziehen"
    override val editorDragReorder      = "Zum Sortieren ziehen"
    override val editorConfigure        = "Einstellen"
    override val editorForceRemove      = "Erzwungen entfernen"
    override val editorForceRemoveTitle = "Widget erzwungen entfernen?"
    override fun editorForceRemoveBody(name: String) =
        "\"$name\" ist als nicht entfernbar markiert. Solche Widgets bleiben normalerweise an Ort und Stelle, damit du nicht ohne Navigation dastehst. Wenn du sicher bist, dass es hier nicht gebraucht wird, kannst du es jetzt entfernen. Falls nötig, setze die Oberfläche über das Menü neben dem Oberflächen-Chip auf Standard zurück."

    // --- Layout editor: host (reset / pill / fab) ---
    override val editorResetSurfaceTitle = "Oberfläche auf Standard zurücksetzen?"
    override fun editorResetSurfaceBody(name: String) =
        "\"$name\" kehrt zur Widget-Anordnung aus dem eingebauten Standard-Layout zurück. Alle lokalen Änderungen auf dieser Oberfläche (hinzugefügte Widgets, Umsortierungen, Löschungen) gehen verloren. Andere Oberflächen bleiben unberührt."
    override val editorPreview           = "Vorschau"
    override val editorPreviewHidden     = "Ausgeblendet"
    override val editorPaletteToggleHide = "Ausblenden"
    override val editorEscHint           = "Esc zum Beenden"
    override val editorFabEdit           = "Layout bearbeiten"
    override val editorFabDone           = "Bearbeitung beenden"

    // --- Layout editor: surface short names ---
    override val editorSurfShortHome      = "Start"
    override val editorSurfShortLibrary   = "Bibliothek"
    override val editorSurfShortLeftRail  = "Linke Leiste"
    override val editorSurfShortRightRail = "Rechte Leiste"
    override val editorSurfShortAbout     = "Über"
    override val editorSurfShortBg        = "Hintergrund"
    override val editorSurfShortProfile   = "Profil"
    override val editorSurfShortServer    = "Server"
    override val editorSurfShortTheme     = "Themes"
    override val editorSurfShortShell     = "Hülle"
    override val editorSurfShortTopBar    = "Oben"
    override val editorSurfShortBody      = "Bereich"

    // --- Layout editor: surface long names ---
    override val editorSurfHomeClassic = "Start (klassisch)"
    override val editorSurfHomeNew     = "Start (neu)"
    override val editorSurfLibrary     = "Bibliothek"
    override val editorSurfLeftRail    = "Seitenleiste"
    override val editorSurfRightRail   = "Rechte Leiste"
    override val editorSurfAbout       = "Über"
    override val editorSurfBg          = "Hintergrund-Einstellungen"
    override val editorSurfProfile     = "Profil"
    override val editorSurfServer      = "Server-Details"
    override val editorSurfTheme       = "Themenauswahl"
    override val editorSurfShell        = "App-Hülle"
    override val editorSurfTopBar       = "Obere Leiste"
    override val editorSurfBody         = "Hauptbereich"

    // --- Music player widgets ---
    override val musicPlayerTitle      = "Musik-Player"
    override val audioPlay             = "Abspielen"
    override val audioPause            = "Pause"
    override val audioStop             = "Stopp"
    override val audioOpenFile         = "Datei öffnen"
    override val audioPickTrack        = "Track auswählen"
    override val audioVolume           = "Lautstärke"
    override val audioNoFile           = "Keine Datei"
    override val audioStatusReady      = "Bereit"
    override val audioStatusPlaying    = "Spielt"
    override val audioStatusPaused     = "Pausiert"
    override val audioFormatHint       = "MP3, FLAC, OGG, WAV und mehr."
    override val audioNoPlayerHere     = "Kein Player auf diesem Layout"
    override val audioAddMusicPlayer   = "Music player hinzufügen"
    override val audioErrorUnsupported = "Format nicht unterstützt oder Datei beschädigt."
    override val audioErrorOpenFailed  = "Datei konnte nicht geöffnet werden"
    override val audioErrorDeviceBusy  = "Audiogerät ist belegt"
    override val audioErrorPlaybackFailed = "Wiedergabe fehlgeschlagen"

    // --- Video player ---
    override val videoFullscreen     = "Vollbild"
    override val videoExitFullscreen = "Vollbild verlassen"
    override val videoMute           = "Stummschalten"
    override val videoUnmute         = "Ton ein"
    override val videoReplay         = "Erneut abspielen"
    override val videoError          = "Video konnte nicht abgespielt werden"
    override val videoLoading        = "Video wird geladen…"
    override val videoOpenInBrowser  = "Im Browser öffnen"
    override val videoSkipBack        = "10 Sekunden zurück"
    override val videoSkipForward     = "10 Sekunden vor"
    override val videoWidgetEmpty     = "Video-URL in den Widget-Einstellungen festlegen"

    // --- Library pack card ---
    override val packCardPlay          = "Spielen"
    override val packCardSettings      = "Einstellungen"
    override val packCardMore          = "Mehr"
    override val packCardDeleteTitle   = "Instanz löschen?"
    override val packCardDeleteBody    = "Die Instanz und alle ihre Dateien (Welten, Einstellungen, Mods) werden endgültig entfernt. Das kann nicht rückgängig gemacht werden."
    override val packCardNeverPlayed   = "Nie gespielt"
    override val packCardPlayedJustNow = "gerade eben"
    override fun packCardPlayedMinutesAgo(n: Long) = "vor $n Min"
    override fun packCardPlayedHoursAgo(n: Long)   = "vor $n Std"
    override fun packCardPlayedDaysAgo(n: Long)    = "vor $n T"
    override val packCardPlayedLongAgo = "vor langem"

    // --- Session chip + about logo a11y ---
    override val sessionsActiveTitle = "Aktive Sitzungen"
    override val aboutLogoDesc       = "App-Logo"
}
