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

    // Navigation
    override val navLogout   = "Abmelden"
    override val navBack     = "Zurück"

    // Dashboard
    override fun dashboardWelcome(name: String) = "WILLKOMMEN ZURÜCK, $name"
    override val dashboardServers              = "VERFÜGBARE SERVER"
    override val dashboardServersEmpty         = "Keine Server gefunden"
    override val dashboardLoginRequiredTitle   = "Anmelden, um Server zu sehen"
    override val dashboardLoginRequiredHint    = "Bitte die rechte Seitenleiste zur Anmeldung verwenden. Die Serverliste ist auf SMARTYcraft nur nach Anmeldung verfügbar."

    // Launch Control
    override val launchReady       = "Bereit zum Spielen"
    override val launchButton      = "Spielen"
    override val launchAbort       = "Abbrechen"
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
    override val profileSkinFront          = "Vorderseite"
    override val profileSkinBack           = "Rückseite"
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
    override val settingsCloseAfterLaunch   = "Launcher nach Serverstart in Tray minimieren"
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

    // Console
    override val consoleTitle = "Debug-Konsole"
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Spielausgabe ($filtered/$total)"
    override val consoleCopyAll = "Alles kopieren"
    override val consoleClear   = "Leeren"
    override val consoleWrap    = "Zeilenumbruch"
    override val consoleSaveToFile = "In Datei speichern"
    override val consoleSearchPlaceholder = "Suchen…"
    override val consoleCopied = "Kopiert"
    override val consoleMenuCopyLine = "Zeile kopieren"
    override val consoleMenuCopySelection = "Auswahl kopieren"
    override val consoleStatusFollow = "folgt"
    override val consoleStatusPaused = "pausiert"
    override fun consoleStatusLines(filtered: Int, total: Int) = "Zeilen: $filtered/$total"
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
    override fun fileDownloading(n: Int) = "Updates werden heruntergeladen ($n Dateien)..."

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
    override val backgroundTitle          = "Benutzerdefinierter Hintergrund"
    override val backgroundSubtitle       = "Launcher-Hintergrund anpassen"
    override val backgroundEnable         = "Aktivieren"
    override val backgroundSectionImage   = "Bild"
    override val backgroundPickFile       = "Hintergrundbild auswählen"
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
    override val aboutTitle                = "ÜBER DEN LAUNCHER"
    override fun aboutDescription(branding: String) = "Inoffizieller Launcher für $branding"
    override fun aboutBuildDate(date: String) = "Erstellt: $date"
    override val aboutSectionCreator       = "Ersteller"
    override val aboutSectionTechnologies  = "Technologien"
    override val aboutSectionLicense       = "Lizenz"
    override val aboutLicenseText          = "GPLv3 — Freie und quelloffene Software"
    override val aboutSectionUpdates       = "Updates"
    override val aboutCurrentVersion       = "Aktuelle Version"
    override val aboutCheckUpdates         = "Auf Updates prüfen"
    override val aboutChecking             = "Prüfe..."
    override val aboutUpToDate             = "Sie sind auf dem neuesten Stand!"
    override val aboutCheckAgain           = "Erneut prüfen"
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
    override val techSkiaDesc    = "Skin-Rendering"
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
    override val trayServers       = "Server"
    override val trayNoServers     = "Keine Server geladen"

    // --- Settings: Experimental features ---
    override val settingsSectionExperimental    = "Experimentelle Funktionen"
    override val settingsExperimentalMaster     = "Experimentelle Funktionen"
    override val settingsExperimentalMasterDesc = "Hauptschalter. Wird er deaktiviert, werden beide Schalter darunter unabhängig von ihren gespeicherten Werten erzwungen ausgeschaltet."
    override val settingsMandatoryUpdates       = "Pflicht-Updates"
    override val settingsMandatoryUpdatesDesc   = "Den Start blockieren, bis kritische Updates installiert sind, wenn die Upstream-Protokoll-Kompatibilität bricht. Aktuell standardmäßig EIN."
    override val settingsPrereleaseChannel      = "Vorab-Release-Kanal"
    override val settingsPrereleaseChannelDesc  = "RC- und Beta-Builds erhalten. Ermöglicht Protokoll-Fixes vor dem nächsten stabilen Release. Aktuell standardmäßig EIN."
    override val settingsAutoSyncAllPacks       = "Installierte Modpacks beim Start automatisch aktualisieren"
    override val settingsAutoSyncAllPacksDesc   = "Aktualisiert beim Launcher-Start jedes bereits installierte Server-Pack im Hintergrund. Kostet Bandbreite — nützlich, wenn du zwischen mehreren Servern wechselst und frischen Stand ohne Klick auf jeden willst."
    override val settingsJvmBuilder             = "Visueller JVM-Argument-Builder"
    override val settingsJvmBuilderDesc         = "Zeigt einen „Argumente bauen“-Button in den Server-Einstellungen. Wähle Garbage Collector, justiere Heap-Regionen, aktiviere AppCDS oder JFR — ohne Flags auswendig zu lernen. Vorgaben: Aikar's Rezept, GTNH-Klasse, ZGC für große Heaps und mehr."
    override val settingsMimicVersion           = "Launcher-Version-Override"
    override val settingsMimicVersionDesc       = "Fixiert den Versions-String, der an den Upstream im Handshake und User-Agent gesendet wird. Leer lassen für den eingebauten Standard — nur setzen, wenn der Upstream seine Versions-Anforderung schneller anhebt als Nexiras Release-Zyklus. Wirkt beim nächsten Protokoll-Aufruf nach Speichern, kein Neustart nötig."
    override fun settingsMimicVersionPlaceholder(default: String) = "Standard: $default"
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Synchronisiere $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB MB"

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
    override fun jvmPreviewFlagsCount(n: Int) = "Vorschau ($n Flags)"

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
    override fun migrationSize(megabytes: Int, files: Int) = "$megabytes MB, $files Dateien"
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

    override val settingsCategoryAppearance   = "Erscheinungsbild"
    override val settingsCategoryNetwork      = "Netzwerk"
    override val settingsCategoryExperimental = "Experimentell"
    override val settingsCategoryAdvanced     = "Erweitert"
    override val settingsCategoryDiagnostics  = "Diagnose"

    override val profileCategorySkin    = "Skin"
    override val profileCategoryAccount = "Konto"

    override val backgroundLoopMode      = "Schleife"
    override val backgroundLoopUseCodec  = "Aus Codec"
    override val backgroundLoopForever   = "Endlos"
    override val backgroundLoopOnce      = "Einmal"

    override val settingsCustomizationExt    = "Anpassung (exp.)"
    override val settingsCustomizationExtSub = "Dichte, Akzent, Glas-Deckkraft, Farbüberschreibungen"
    override val customizationTitle           = "Anpassung"
    override val customizationSubtitle        = "Experimentelle Feinabstimmung"
    override val customizationDensity         = "Dichteskala"
    override val customizationGlassIntensity  = "Glas-Deckkraft"
    override val customizationAccentOverride  = "Akzent überschreiben"
    override val customizationAccentClear     = "Überschreibung löschen"
    override val customizationSectionVisual   = "Visuell"
    override val customizationSectionColors   = "FARBÜBERSCHREIBUNGEN"
    override val customizationExperimentalToggle = "Alle Farben überschreiben"
    override val customizationExperimentalSub    = "7-Farb-Matrix freischalten. Kann unleserlich werden."
    override val customizationReset           = "Alles zurücksetzen"
    override val customizationHexInvalid      = "Ungültiger Hex"
    override val themePickerAccentOverride    = "Akzent überschreiben (sofort)"

    override val browseTitle        = "Katalog"
    override val browseSubtitle     = "Auf dem Mirror veröffentlichte Packs"
    override val browseEmptyTitle   = "Katalog ist leer"
    override val browseEmptyMessage = "Der Mirror ist erreichbar, hat aber noch keine Packs veröffentlicht. Schau später wieder vorbei."
    override val browseErrorTitle   = "Mirror nicht erreichbar"
    override val browseErrorMessage = "Mirror konnte nicht erreicht werden. Verbindung prüfen und erneut versuchen."
    override val browseRetry        = "Erneut versuchen"

    override val browseDetailErrorTitle    = "Pack konnte nicht geladen werden"
    override val browseDetailErrorMessage  = "Manifest konnte nicht geladen werden. Verbindung prüfen und erneut versuchen."
    override val browseDetailInstallReady  = "Bereit zur Installation"
    override val browseDetailInstallHint   = "Erstellt eine neue Instanz in deinem Datenverzeichnis."
    override val browseDetailInstallButton = "Installieren"
    override val browseDetailTagsTitle     = "Tags"
    override val browseDetailAboutTitle       = "Über dieses Pack"
    override val browseDetailAboutPlaceholder = "Dieses Pack enthält %d Mods und %d Assets."
    override val browseDetailAboutNote        = "Eine ausführliche Beschreibung erscheint hier, sobald der Mirror sie zum Manifest hinzufügt."
    override val browseDetailCompatTitle      = "Kompatibilität"
    override val browseDetailCompatMc         = "Minecraft"
    override val browseDetailCompatLoader     = "Loader"
    override val browseDetailCompatJava       = "Java"
    override val browseDetailVersionTitle     = "Version"

    override val browseDetailInstallRunningTitle  = "Installation läuft..."
    override val browseDetailInstallProgress      = "%s  (%d / %d)"
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
    override val contentTabFetchErrorTitle      = "Pack-Inhalt konnte nicht geladen werden"
    override val contentTabFetchErrorGeneric    = "Das Mirror-Manifest konnte nicht geladen werden."
    override val contentTabRetry                = "Erneut versuchen"
    override val contentTabRoleSection          = "Rollen-Slots"
    override fun contentTabOptionalSection(count: Int) = "Optionale Mods ($count)"
    override fun contentTabIncompatibleWith(name: String) = "Inkompatibel mit $name"
    override fun contentTabModsSection(count: Int) = "Mods ($count)"
    override fun contentTabAssetsSection(count: Int) = "Assets ($count)"
    override val contentTabResolverIssuesTitle  = "Manifest-Probleme erkannt"
    override fun contentTabResolverMissing(count: Int) =
        if (count == 1) "1 Abhängigkeit verweist auf einen Mod, der nicht in diesem Pack ist."
        else "$count Abhängigkeiten verweisen auf Mods, die nicht in diesem Pack sind."
    override fun contentTabResolverCycles(count: Int) =
        if (count == 1) "1 Abhängigkeits-Zyklus gefunden — Pack-Autor sollte den requires-Graph prüfen."
        else "$count Abhängigkeits-Zyklen gefunden — Pack-Autor sollte den requires-Graph prüfen."
    override val contentTabRoleRecipeViewer     = "Rezept-Übersicht"
    override val contentTabRoleMinimap          = "Minimap"
    override val contentTabRoleBlockInfo        = "Block-Info"
    override val contentTabRolePerformance      = "Performance"
    override val contentTabRoleInventorySearch  = "Inventar-Suche"
    override fun contentTabRoleAltCount(count: Int) =
        if (count == 0) "einzige Option" else "$count Alternative${if (count == 1) "" else "n"}"
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
    override val notifActionShowConsole                 = "Konsole anzeigen"
    override val notifActionStop                        = "Stoppen"
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
    override fun notifTimeSeconds(seconds: Long)        = "${seconds} s"
    override fun notifTimeMinutes(minutes: Long)        = "${minutes} min"
    override fun notifTimeHours(hours: Long)            = "${hours} h"
    override fun notifTimeDays(days: Long)              = "${days} T"
}
