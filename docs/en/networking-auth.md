# Networking & Authentication

> **Module:** `docs/en/networking-auth.md`
> **Context:** API communication, session management, and server data synchronization.

## 1. Network Stack Configuration

Aura Launcher centralizes all HTTP communication through a unified network module provided by **Retrofit 2** and **OkHttp 4**.

### 1.1 Infrastructure (`networkModule`)

**Source:** `hivens.launcher.di.Modules.kt`

The network stack is initialized as a singleton in the Koin graph.

* **OkHttpClient:** Configured with extended timeouts (30s) to handle slow modpack downloads and server handshakes.
* **Retrofit:** Uses `KotlinxSerializationConverterFactory` to parse JSON responses into immutable Data Classes.
* **Base URL:** Defined in `AppConfig.API_BASE_URL`.

```kotlin
// Conceptual configuration
single {
    OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
}

```

---

## 2. Authentication Architecture

The authentication system bridges the user interface and the SmartyCraft backend. It is designed to be stateless on the server side but stateful (session-based) on the client.

### 2.1 The Contract (`IAuthService`)

**Source:** `hivens.core.api.interfaces.IAuthService`

The core module defines the strict contract for login operations. The UI layer depends *only* on this interface.

```kotlin
interface IAuthService {
    @Throws(AuthException::class)
    suspend fun login(login: String, password: String, serverId: String): SessionData
}

```

* **Parameters:**
* `login`: Username or Email.
* `password`: Plaintext password.
* `serverId`: The target server ID (e.g., "main", "rpg"). Used to validate whitelists or fetch server-specific permissions during login.


* **Returns:** `SessionData` containing the access token and profile.
* **Throws:** `AuthException` if credentials are invalid or the server is unreachable.

### 2.2 Session Management (`SessionData`)

**Source:** `hivens.core.data.SessionData`

A successful login results in a `SessionData` object, which acts as the "Identity Token" for the session.

| Property         | Type      | Description                                                                                          |
|------------------|-----------|------------------------------------------------------------------------------------------------------|
| `playerName`     | `String`  | Display name (IGN).                                                                                  |
| `uuid`           | `String`  | Mojang-style UUID (dashed).                                                                          |
| `accessToken`    | `String`  | Bearer token for API requests and game join verification.                                            |
| `cachedPassword` | `String?` | **Security Note:** The launcher temporarily caches the password to facilitate auto-login on restart. |

### 2.3 Auto-Login Strategy

The launcher does not currently use long-lived Refresh Tokens. Instead, it employs a "Password Replay" strategy:

1. On startup, `CredentialsManager` loads the last session from `credentials.json`.
2. If `cachedPassword` is present, `Main.kt` automatically calls `authService.login()` in the background.
3. If this fails (e.g., password changed), the user is redirected to the Login Screen.

---

## 3. Server Management

Fetching server status and metadata is handled by the `ServerListService`.

### 3.1 Data Flow

1. **Request:** `IServerListService.fetchDashboardData()` is called.
2. **DTO Fetch:** The service queries the backend for `SmartyResponse<DashboardData>`.
* `SmartyResponse`: A generic wrapper containing status codes and payload.
* `DashboardData`: Contains a list of `SmartyServer` DTOs and `SmartyNews` items.


3. **Mapping:** The logic layer maps the raw `SmartyServer` DTOs into rich domain models `ServerProfile` used by the UI.

### 3.2 Server Profiles

The `ServerProfile` model drives the UI Dashboard. It includes:

* `name`: Display name (e.g., "HiTech 1.12.2").
* `assetDir`: The identifier used for asset lookup and folder separation (e.g., `hitech`).
* `serverAddress`: IP/Port for the game client connection.

---

## 4. File Downloads

While game files are handled by the `LauncherService`, the actual retrieval is delegated to `IFileDownloadService`.

* **Implementation:** `FileDownloadService` uses the same `OkHttpClient` instance but typically configures a separate `ProgressResponseBody` interceptor to track download percentage for the UI progress bar.
* **Concurrency:** Downloads are performed in parallel using Kotlin Coroutines to maximize bandwidth usage during initial client installation.
