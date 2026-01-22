# Data Persistence & Storage

> **Module:** `docs/en/data-storage.md`
> **Context:** Local file structure, JSON configuration formats, and state management.

## 1. Overview

Aura Launcher persists all user data locally using **JSON** format via `kotlinx.serialization`. The application does not use a local database (SQLite/Realm) to minimize overhead and dependencies.

The root working directory (`workDir`) is determined at runtime based on the OS (e.g., `%APPDATA%/AuraLauncher` on Windows or `~/.config/AuraLauncher` on Linux).

---

## 2. Configuration Files

### 2.1 Credentials (`credentials.json`)

**Manager:** `hivens.launcher.CredentialsManager`

Stores the active user session to enable auto-login.

* **Security Notice:** The password is stored using **Base64 encoding**, not encryption. This provides obfuscation against casual observation but is not cryptographically secure. This design choice mirrors legacy launcher compatibility.
* **Internal Structure (`SavedCredentials`):**
* `username`: Player's IGN.
* `accessToken`: The session token from the backend.
* `uuid`: Player's unique ID.
* `savedPasswordBase64`: Encoded password string.



```json
// Example Structure
{
  "username": "Haru",
  "accessToken": "eyJhbG...",
  "uuid": "123e4567-e89b-...",
  "savedPasswordBase64": "cGFzc3dvcmQ="
}

```

### 2.2 Global Settings (`settings.json`)

**Manager:** `hivens.launcher.SettingsService`

Persists application-wide preferences defined in `SettingsData`.

* **Key Fields:**
* `memoryMB`: RAM allocation (Default: 4096).
* `seasonalTheme`: Visual theme override (e.g., "WINTER", "AUTO").
* `closeAfterStart`: Boolean flag to exit launcher after game start.


* **Behavior:** Missing file results in default settings generation.

### 2.3 Profiles & State (`profiles.json`)

**Manager:** `hivens.launcher.ProfileManager`

Stores local state that is specific to the client instance, distinct from the server data fetched from the API.

* **Format (`ProfilesContainer`):**
* `lastServerId`: The `assetDir` of the last selected server (for UI restoration).
* `favorites`: A list of server IDs marked as favorites by the user.
* `profiles`: A map of `InstanceProfile` objects containing per-server overrides (if any).



---

## 3. Directory Structure

The launcher manages a strict directory hierarchy for game files to ensure isolation and integrity.

**Manager:** `hivens.launcher.util.ClientFileHelper`

```text
workDir/
├── credentials.json       # Auth data
├── settings.json          # Global config
├── profiles.json          # UI state & favorites
├── client/                # Game Root (Running directory)
│   ├── assets/            # Minecraft Assets (Objects/Indexes)
│   ├── bin/               # Natives (.dll, .so) and libraries
│   ├── mods/              # Mod loader content
│   ├── resourcepacks/     # User resource packs
│   └── options.txt        # Game settings
└── updates/               # Temp folder for JRE/Launcher updates

```

### 3.1 Cleanup Logic

To prevent "mod rot" (accumulating old files), `ClientFileHelper.cleanDirectory` is called during the launch sequence.

* **Mechanism:** It lists all files in `client/mods` or `client/bin`.
* **Filter:** Compares against the `FileManifest` (allowlist).
* **Action:** Deletes any `.jar`, `.zip`, `.dll`, or `.so` file that is not explicitly required by the server manifest.

---

## 4. DTO vs Domain Models

It is important to distinguish between the data types used in storage versus those used in the UI:

| Type                   | Layer              | Role                                        | Storage            |
|------------------------|--------------------|---------------------------------------------|--------------------|
| **`SavedCredentials`** | Launcher (Private) | Minimal storage DTO.                        | `credentials.json` |
| **`SessionData`**      | Core (Public)      | Full session object used by UI.             | *Runtime Memory*   |
| **`ServerProfile`**    | Core (Model)       | Remote server info (IP, Name).              | *Fetched from API* |
| **`InstanceProfile`**  | Core (Data)        | Local client settings (e.g. optional mods). | `profiles.json`    |
