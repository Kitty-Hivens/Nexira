Here is the comprehensive documentation for the Dependency Injection system. It covers the Koin setup, module organization, and a step-by-step guide for adding new services without breaking the architecture.

Create the file: `docs/en/dependency-injection.md`

---

# Dependency Injection (Koin)

> **Module:** `docs/en/dependency-injection.md`
> **Context:** Managing the object graph, service wiring, and resolving dependencies at runtime.

## 1. Overview

Aura Launcher uses **Koin**, a pragmatic Dependency Injection (DI) framework for Kotlin. Unlike reflection-heavy frameworks (like Spring) or compile-time generators (like Dagger/Hilt), Koin uses a lightweight DSL to define the dependency graph at runtime.

### Why Koin?

1. **Zero Boilerplate:** No annotations (`@Inject`, `@Provides`) or code generation (`kapt`) required in the `core` or `launcher` modules.
2. **Multiplatform Ready:** Works natively with Compose Desktop and Kotlin JVM.
3. **Explicit Graph:** Dependencies are declared centrally in module files, making the architecture transparent.

---

## 2. The Dependency Graph

The DI graph is constructed hierarchically to respect the project's Clean Architecture layers. The graph is initialized once during the application startup.

### 2.1 Initialization

**Location:** `client-ui/src/desktopMain/kotlin/hivens/ui/Main.kt`

The `startKoin` function is the entry point. It must be called **before** any UI content is rendered to ensure controllers can inject their dependencies.

```kotlin
fun main() {
    startKoin {
        // Logging context (optional, useful for debugging)
        // androidLogger() // Not used in Desktop

        // Load modules
        modules(networkModule, appModule, uiModule)
    }

    application {
        // UI Rendering...
    }
}

```

### 2.2 Module Organization

The graph is split into three logical modules to maintain separation of concerns.

| Module Name         | Source File                         | Scope              | Description                                                                                                                |
|---------------------|-------------------------------------|--------------------|----------------------------------------------------------------------------------------------------------------------------|
| **`networkModule`** | `client-launcher/.../di/Modules.kt` | **Singleton**      | Provides low-level infrastructure: `OkHttpClient`, `Retrofit`, and JSON parsers. These are expensive objects created once. |
| **`appModule`**     | `client-launcher/.../di/Modules.kt` | **Singleton**      | The "Service Mesh". Binds abstract interfaces from `client-core` to concrete implementations in `client-launcher`.         |
| **`uiModule`**      | `client-ui/.../ui/Main.kt`          | **Factory/Single** | UI Controllers and ViewModels. Defined in the UI layer because `client-launcher` cannot see UI classes.                    |

---

## 3. Module Definitions

### 3.1 Network Module (`networkModule`)

Handles external connectivity. Note how `Retrofit` depends on `OkHttpClient` via `get()`.

```kotlin
val networkModule = module {
    // 1. Http Client
    single {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // 2. Retrofit Instance
    single {
        Retrofit.Builder()
            .baseUrl(AppConfig.API_BASE_URL)
            .client(get()) // Injects the OkHttpClient defined above
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}

```

### 3.2 Application Module (`appModule`)

This is where the "Clean Architecture" binding happens. We map the **Interface** (Contract) to the **Implementation**.

```kotlin
val appModule = module {
    // Services
    single<ILauncherService> { LauncherService(get(), get()) }
    single<IAuthService> { AuthService(get()) }
    single<ISettingsService> { SettingsService() }
    
    // Repositories
    single { SkinRepository(get()) }
}

```

* **`single<Interface> { Impl() }`**: This tells Koin: *"Whenever someone asks for `Interface`, give them this singleton instance of `Impl`"*.

### 3.3 UI Module (`uiModule`)

Controllers are often state-holders. While they are usually singletons in this desktop app, they can be `factory` if a fresh instance is needed per screen.

```kotlin
val uiModule = module {
    // Injection using Constructor Reference (::)
    // Equivalent to: single { LauncherController(get(), get(), ...) }
    singleOf(::LauncherController)
}

```

---

## 4. Injection Patterns

### 4.1 In Services (Constructor Injection)

This is the standard pattern for `client-launcher`. Dependencies are passed via the constructor.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/LauncherService.kt
class LauncherService(
    private val settingsService: ISettingsService, // Injected
    private val fileIntegrity: IFileIntegrityService // Injected
) : ILauncherService { ... }

```

### 4.2 In Compose UI (`koinInject`)

Composables cannot have constructors. We use the `koinInject()` function to retrieve dependencies from the context.

```kotlin
// client-ui/src/desktopMain/kotlin/hivens/ui/screens/DashboardScreen.kt
@Composable
fun DashboardScreen() {
    // Lazy injection
    val controller: LauncherController = koinInject()
    val settings: ISettingsService = koinInject()
    
    // Use them...
}

```

**⚠️ Warning:** Avoid calling `koinInject()` inside tight loops or drawing phases (`Canvas`). It does a map lookup which is fast but not zero-cost.

---

## 5. How-To: Adding a New Service

Follow this strict protocol to add a new feature (e.g., `DiscordRPCSrevice`) without breaking the architecture.

### Step 1: Define the Contract (`client-core`)

Create the interface. This allows the UI to speak to the service without knowing its code.

```kotlin
// client-core/src/main/kotlin/hivens/core/api/interfaces/IDiscordService.kt
interface IDiscordService {
    fun updatePresence(status: String)
}

```

### Step 2: Implement the Logic (`client-launcher`)

Write the implementation. You can inject other services here if needed.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/DiscordService.kt
class DiscordService(
    private val authService: IAuthService // Example dependency
) : IDiscordService {
    override fun updatePresence(status: String) { ... }
}

```

### Step 3: Register in Koin (`client-launcher`)

Open `hivens.launcher.di.Modules.kt` and add the definition to `appModule`.

```kotlin
val appModule = module {
    // ... existing definitions
    single<IDiscordService> { DiscordService(get()) }
}

```

### Step 4: Inject in UI (`client-ui`)

Now you can use it in your controller or screen.

```kotlin
class LauncherController(
    private val discordService: IDiscordService
) { ... }

```

---

## 6. Troubleshooting

### `NoBeanDefFoundException`

**Error:** `No definition found for class '...' Check your definitions!`
**Cause:** You forgot **Step 3**. The class exists, but Koin doesn't know about it.
**Fix:** Add `single { ... }` to `Modules.kt`.

### `InstanceCreationException`

**Error:** `Could not create instance for ...`
**Cause:** One of the dependencies required by your service is missing or failed to initialize.
**Fix:** Check the stack trace to see *which* argument failed to resolve. Recursively check its dependencies.

### Circular Dependency

**Error:** StackOverflow during startup.
**Cause:** Service A needs Service B, and Service B needs Service A.
**Fix:** Refactor logic. Extract the shared logic into a third Service C, or use `by inject()` (Lazy) inside one of the classes (not recommended).
