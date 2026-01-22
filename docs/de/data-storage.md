# Datenpersistenz & Speicher

> **Modul:** `docs/de/data-storage.md`
> **Kontext:** Lokale Dateistruktur, JSON-Konfigurationsformate und Zustandsverwaltung.

## 1. Übersicht

Der Aura Launcher speichert alle Benutzerdaten lokal im **JSON**-Format unter Verwendung von `kotlinx.serialization`. Die Anwendung verwendet keine lokale Datenbank (SQLite/Realm), um Overhead und Abhängigkeiten zu minimieren.

Das Stamm-Arbeitsverzeichnis (`workDir`) wird zur Laufzeit basierend auf dem Betriebssystem bestimmt (z. B. `%APPDATA%/AuraLauncher` unter Windows oder `~/.config/AuraLauncher` unter Linux).

---

## 2. Konfigurationsdateien

### 2.1 Anmeldedaten (`credentials.json`)

**Manager:** `hivens.launcher.CredentialsManager`

Speichert die aktive Benutzersitzung, um den automatischen Login zu ermöglichen.

* **Sicherheitshinweis:** Das Passwort wird in **Base64-Kodierung** gespeichert, nicht verschlüsselt. Dies bietet eine Verschleierung vor zufälliger Einsicht, ist aber nicht kryptographisch sicher. Diese Designentscheidung dient der Kompatibilität mit Legacy-Launchern.
* **Interne Struktur (`SavedCredentials`):**
    * `username`: Spielname (IGN).
    * `accessToken`: Das Sitzungs-Token vom Backend.
    * `uuid`: Eindeutige Spieler-ID.
    * `savedPasswordBase64`: Kodierte Passwort-Zeichenfolge.

```json
// Beispielstruktur
{
  "username": "Haru",
  "accessToken": "eyJhbG...",
  "uuid": "123e4567-e89b-...",
  "savedPasswordBase64": "cGFzc3dvcmQ="
}
```

### 2.2 Globale Einstellungen (`settings.json`)

**Manager:** `hivens.launcher.SettingsService`

Speichert anwendungsweite Einstellungen, die in `SettingsData` definiert sind.

* **Schlüsselfelder:**
* `memoryMB`: RAM-Zuweisung (Standard: 4096).
* `seasonalTheme`: Überschreibung des visuellen Themas (z. B. "WINTER", "AUTO").
* `closeAfterStart`: Boolescher Flag zum Schließen des Launchers nach dem Spielstart.


* **Verhalten:** Fehlt die Datei, werden Standardeinstellungen generiert.

### 2.3 Profile & Status (`profiles.json`)

**Manager:** `hivens.launcher.ProfileManager`

Speichert den lokalen Status, der spezifisch für die Client-Instanz ist und sich von den Serverdaten unterscheidet, die über die API abgerufen werden.

* **Format (`ProfilesContainer`):**
* `lastServerId`: Die `assetDir` des zuletzt ausgewählten Servers (zur UI-Wiederherstellung).
* `favorites`: Eine Liste von Server-IDs, die vom Benutzer als Favoriten markiert wurden.
* `profiles`: Eine Map von `InstanceProfile`-Objekten, die pro Server spezifische Überschreibungen enthalten (falls vorhanden).



---

## 3. Verzeichnisstruktur

Der Launcher verwaltet eine strikte Ordnerhierarchie für Spieldateien, um Isolation und Integrität zu gewährleisten.

**Manager:** `hivens.launcher.util.ClientFileHelper`

```text
workDir/
├── credentials.json       # Auth-Daten
├── settings.json          # Globale Konfig
├── profiles.json          # UI-Status & Favoriten
├── client/                # Spiel-Root (Arbeitsverzeichnis)
│   ├── assets/            # Minecraft Assets (Objekte/Indizes)
│   ├── bin/               # Native Dateien (.dll, .so) und Bibliotheken
│   ├── mods/              # Mod-Loader-Inhalte
│   ├── resourcepacks/     # Ressourcenpakete des Benutzers
│   └── options.txt        # Spieleinstellungen
└── updates/               # Temp-Ordner für JRE/Launcher-Updates

```

### 3.1 Bereinigungslogik (Cleanup)

Um "Mod-Fäule" (Ansammlung alter Dateien) zu verhindern, wird während der Startsequenz `ClientFileHelper.cleanDirectory` aufgerufen.

* **Mechanismus:** Listet alle Dateien in `client/mods` oder `client/bin` auf.
* **Filter:** Vergleicht gegen das `FileManifest` (Allowlist).
* **Aktion:** Löscht jede `.jar`, `.zip`, `.dll` oder `.so` Datei, die nicht explizit vom Server-Manifest benötigt wird.

---

## 4. DTO vs Domänenmodelle

Es ist wichtig, zwischen Datentypen für die Speicherung und solchen für die UI zu unterscheiden:

| Typ                    | Schicht           | Rolle                                               | Speicherung         |
|------------------------|-------------------|-----------------------------------------------------|---------------------|
| **`SavedCredentials`** | Launcher (Privat) | Minimales Speicher-DTO.                             | `credentials.json`  |
| **`SessionData`**      | Core (Öffentlich) | Vollständiges Sitzungsobjekt für die UI.            | *Arbeitsspeicher*   |
| **`ServerProfile`**    | Core (Modell)     | Entfernte Server-Infos (IP, Name).                  | *Von API abgerufen* |
| **`InstanceProfile`**  | Core (Daten)      | Lokale Client-Einstellungen (z. B. optionale Mods). | `profiles.json`     |
