# Architecture & Design

> **Module:** `docs/en/architecture.md`
> **Context:** System-wide architectural decisions, module organization, and data flow.

## 1. High-Level Overview

**Aura Launcher** (formerly SCOpenLauncher) implements a modular, reactive architecture designed to decouple the user interface from business logic. It follows a pragmatic implementation of **Clean Architecture** adapted for Desktop Compose.

The system is stratified into four distinct layers:

1. **Presentation Layer (`client-ui`)**:
    * **Responsibility:** Renders the UI, handles user input, and observes state.
    * **Tech:** Compose Multiplatform (Desktop), Koin (Consumption).
    * **Constraint:** Contains *no* business logic. Delegates all actions to Controllers.

2. **Orchestration Layer (`client-ui/logic`)**:
    * **Responsibility:** Bridges the UI and Domain layers. Manages reactive state (`LaunchState`), handles coroutine scopes for UI actions, and orchestrates multiple services (e.g., "Login then Launch").
    * **Key Component:** `LauncherController`.

3. **Infrastructure/Service Layer (`client-launcher`)**:
    * **Responsibility:** Implements the core business logic defined in `client-core`. Handles file I/O, networking, hashing, and OS process management.
    * **Tech:** Ktor Client (OkHttp Engine), `java.lang.ProcessBuilder`.

4. **Domain & Contract Layer (`client-core`)**:
    * **Responsibility:** Defines *what* (Interfaces) and the *data* (DTOs), but not the *how*.
    * **Constraint:** Pure Kotlin. No framework dependencies (Ktor, Compose, etc.).

## 2. Module Breakdown

The project uses a multi-module Gradle setup to enforce separation of concerns.

| Module                | Namespace         | Role & Dependencies                                                                                                                                                                                                    |
|-----------------------|-------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`client-config`**   | `hivens.config`   | **Static Configuration.**<br>Contains build-time constants (`AppConfig`) like Version, API URLs, and default window sizes.<br>*Dependencies: None*                                                                     |
| **`client-core`**     | `hivens.core`     | **Contracts & Data.**<br>Defines the "API" of the application. Includes interfaces (`ILauncherService`, `IAuthService`) and immutable data models (`SessionData`, `ServerProfile`).<br>*Dependencies: `client-config`* |
| **`client-launcher`** | `hivens.launcher` | **Implementation.**<br>The "Heavy Lifter". Implements interfaces from `core`. Contains the DI modules (`appModule`, `networkModule`) that wire these implementations.<br>*Dependencies: `client-core`*                 |
| **`client-ui`**       | `hivens.ui`       | **Presentation.**<br>The Entry Point (`Main.kt`). Defines the UI logic, screens, and components. Initializes the Koin graph.<br>*Dependencies: `client-launcher`*                                                      |

## 3. Dependency Injection (Koin)

The application constructs its object graph at runtime using **Koin**. The graph is strictly hierarchical.

### Initialization

The DI container is started in `hivens.ui.Main.kt` immediately before the application window is created.

```kotlin
startKoin {
    modules(networkModule, appModule, uiModule)
}
```

### Module Definitions

1. **`networkModule`** (Source: `client-launcher/di/Modules.kt`):
    * Provides singleton instances of `OkHttpClient` (Engine) and `HttpClient` (Ktor).
    * Configures SOCKS proxy, timeouts, and JSON serialization.

2. **`appModule`** (Source: `client-launcher/di/Modules.kt`):
    * Binds Core Interfaces to Launcher Implementations.
    * Example: `single<ILauncherService> { LauncherService(...) }`.
    * Example: `single<ISettingsService> { SettingsService(...) }`.

3. **`uiModule`** (Source: `client-ui/Main.kt`):
    * Provides UI-specific controllers.
    * Example: `singleOf(::LauncherController)`.
    * *Note:* Defined locally in `Main.kt` to keep `client-launcher` independent of UI logic.

## 4. Data Flow: The Launch Sequence

The interaction between layers during a game launch illustrates the architecture:

1. **UI Event:** User clicks "Play" in `DashboardScreen`.
2. **Controller Action:** `DashboardScreen` calls `controller.launch(session, server)`.
3. **State Update:** `LauncherController` updates `LaunchState` to `Preparing`.
4. **Service Orchestration:** `LauncherController` calls `launcherService.launchClient(...)`.
    * *Inside Service:* Checks Java → Checks Files → Builds Arguments → Starts Process.
5. **Process Handover:** The Service returns a `java.lang.Process`.
6. **Monitoring:** `LauncherController` monitors `process.waitFor()` on an IO thread and updates `LaunchState` to `GameRunning`.

## 5. Key Design Patterns

### 5.1 Interface Segregation

The `client-ui` module never instantiates logic classes directly. It requests dependencies via interfaces defined in `client-core`.

* *Benefit:* We can swap the entire `LauncherService` implementation (e.g., for testing or a different game engine) without changing a single line of UI code.

### 5.2 Reactive State (MVI-lite)

The UI observes a single source of truth for the launch status.

* **State:** `LauncherController.state` (Type: `StateFlow<LaunchState>`).
* **Events:** The UI triggers functions on the controller, which internally mutates the state. The UI *never* modifies state directly.

### 5.3 Theming Engine

Theming is handled by a custom `SeasonTheme` enum in `client-core`, decoupled from Compose.

* **Logic:** `SeasonTheme.getCurrentSeasonalTheme()` calculates the theme based on system date.
* **Rendering:** `CelestiaTheme` and `CelestiaBackground` in `client-ui` consume this enum to render specific visual effects (Snow, Fireflies, etc.).
