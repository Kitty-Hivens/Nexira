# Dependency Injection (Koin)

> **Modul:** `docs/de/dependency-injection.md`
> **Kontext:** Verwaltung des Objektgraphen, Service-Verkabelung und Auflösung von Abhängigkeiten zur Laufzeit.

## 1. Übersicht

Der Aura Launcher verwendet **Koin**, ein pragmatisches Framework für Dependency Injection (DI) in Kotlin. Im Gegensatz zu reflexionslastigen Frameworks (wie Spring) oder Generatoren zur Kompilierzeit (wie Dagger/Hilt) nutzt Koin eine leichtgewichtige DSL, um den Abhängigkeitsgraphen zur Laufzeit zu definieren.

### Warum Koin?

1. **Null Boilerplate:** keine Annotationen (`@Inject`, `@Provides`) oder Codegenerierung (`kapt`) in den Modulen `core` oder `launcher` erforderlich.
2. **Multiplatform Ready:** Funktioniert nativ mit Compose Desktop und Kotlin JVM.
3. **Expliziter Graph:** Abhängigkeiten werden zentral in Moduldateien deklariert, was die Architektur transparent macht.

---

## 2. Der Abhängigkeitsgraph

Der DI-Graph wird hierarchisch aufgebaut, um die Schichten der Clean Architecture des Projekts zu respektieren. Der Graph wird einmalig während des Anwendungsstarts initialisiert.

### 2.1 Initialisierung

**Ort:** `client-ui/src/desktopMain/kotlin/hivens/ui/Main.kt`

Die Funktion `startKoin` ist der Einstiegspunkt. Sie muss aufgerufen werden, **bevor** irgendein UI-Inhalt gerendert wird, um sicherzustellen, dass Controller ihre Abhängigkeiten injizieren können.

```kotlin
fun main() {
    startKoin {
        // Logging-Kontext (optional, nützlich für Debugging)
        // androidLogger() // In der Desktop-Version nicht verwendet

        // Module laden
        modules(networkModule, appModule, uiModule)
    }

    application {
        // UI-Rendering...
    }
}
```

### 2.2 Modulorganisation

Der Graph ist in drei logische Module unterteilt, um die Trennung der Verantwortlichkeiten (Separation of Concerns) zu wahren.

| Modulname           | Quelldatei                          | Scope (Gültigkeitsbereich) | Beschreibung                                                                                                                                                    |
|---------------------|-------------------------------------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`networkModule`** | `client-launcher/.../di/Modules.kt` | **Singleton**              | Stellt Low-Level-Infrastruktur bereit: `OkHttpClient` (Engine), `HttpClient` (Ktor) und JSON-Konfig. Dies sind "teure" Objekte, die nur einmal erstellt werden. |
| **`appModule`**     | `client-launcher/.../di/Modules.kt` | **Singleton**              | Das "Service-Geflecht". Bindet abstrakte Schnittstellen aus `client-core` an konkrete Implementierungen in `client-launcher`.                                   |
| **`uiModule`**      | `client-ui/.../ui/Main.kt`          | **Factory/Single**         | UI-Controller und ViewModels. In der UI-Schicht definiert, da `client-launcher` keine UI-Klassen sehen kann.                                                    |

---

## 3. Moduldefinitionen

### 3.1 Netzwerk-Modul (`networkModule`)

Behandelt externe Verbindungen. Wir verwenden **Ktor Client**, der auf der **OkHttp**-Engine basiert. Der `OkHttpClient` wird separat konfiguriert, um SOCKS-Proxys zu unterstützen, und wird anwendungsweit geteilt.

```kotlin
val networkModule = module {
    // 1. JSON-Konfiguration (Kotlinx Serialization)
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
        }
    }

    // 2. OkHttp-Engine (SOCKS Proxy & Timeouts)
    single<OkHttpClient> {
        // Proxy-Authentifizierungseinrichtung...
        OkHttpClient.Builder()
            .connectTimeout(AppConfig.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))
            .build()
    }

    // 3. Ktor HttpClient
    single<HttpClient> {
        val okHttpInstance = get<OkHttpClient>()

        HttpClient(OkHttp) {
            engine { preconfigured = okHttpInstance }
            
            install(ContentNegotiation) {
                json(get()) // Injiziert die obige Json-Instanz
            }
            
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                connectTimeoutMillis = 30_000
            }
            
            defaultRequest {
                header("User-Agent", "SMARTYlauncher/${AppConfig.LAUNCHER_VERSION}")
                contentType(ContentType.Application.Json)
            }
        }
    }
    
    // Repositories (verwendet singleOf für Konstruktor-Injektion)
    singleOf(::ServerRepository)
    singleOf(::SkinRepository)
}
```

### 3.2 Anwendungs-Modul (`appModule`)

Hier geschieht die Bindung im Sinne der "Clean Architecture". Wir mappen das **Interface** (Vertrag) auf die **Implementierung**.

```kotlin
val appModule = module {
    // Services
    single<ILauncherService> { LauncherService(get(), get()) }
    single<IAuthService> { AuthService(get()) } // Injiziert HttpClient
    single<ISettingsService> { SettingsService() }
}
```

* **`single<Interface> { Impl() }`**: Dies sagt Koin: *"Wann immer jemand nach `Interface` fragt, gib ihm diese einzige Instanz von `Impl`"*.
* **`singleOf(::Class)`**: Ein modernes Koin-DSL-Feature, das automatisch alle Konstruktorparameter auflöst.

### 3.3 UI-Modul (`uiModule`)

Controller sind oft Zustandsbehälter. Obwohl sie in dieser Desktop-App meist Singletons sind, können sie `factory` sein, wenn für jeden Screen eine frische Instanz benötigt wird.

```kotlin
val uiModule = module {
    // Injektion mittels Konstruktor-Referenz
    singleOf(::LauncherController)
}
```

---

## 4. Injektionsmuster

### 4.1 In Services (Konstruktor-Injektion)

Dies ist das Standardmuster für `client-launcher`. Abhängigkeiten werden über den Konstruktor übergeben.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/LauncherService.kt
class LauncherService(
    private val settingsService: ISettingsService, // Injiziert
    private val fileIntegrity: IFileIntegrityService // Injiziert
) : ILauncherService { ... }
```

### 4.2 In Compose UI (`koinInject`)

Composable-Funktionen haben keine Konstruktoren. Wir verwenden die Funktion `koinInject()`, um Abhängigkeiten aus dem Kontext abzurufen.

```kotlin
// client-ui/src/desktopMain/kotlin/hivens/ui/screens/DashboardScreen.kt
@Composable
fun DashboardScreen() {
    // Lazy Injection
    val controller: LauncherController = koinInject()
    val settings: ISettingsService = koinInject()
    
    // Verwendung...
}
```

**⚠️ Warnung:** Vermeiden Sie den Aufruf von `koinInject()` innerhalb enger Schleifen oder Zeichenphasen (`Canvas`). Dies führt einen Map-Lookup durch, der zwar schnell, aber nicht kostenlos ist.

---

## 5. Anleitung: Hinzufügen eines neuen Services

Folgen Sie diesem strikten Protokoll, um ein neues Feature (z. B. `DiscordRPCSrevice`) hinzuzufügen, ohne die Architektur zu brechen.

### Schritt 1: Definieren Sie den Vertrag (`client-core`)

Erstellen Sie das Interface. Dies ermöglicht der UI, mit dem Service zu sprechen, ohne dessen Code zu kennen.

```kotlin
// client-core/src/main/kotlin/hivens/core/api/interfaces/IDiscordService.kt
interface IDiscordService {
    fun updatePresence(status: String)
}
```

### Schritt 2: Implementieren Sie die Logik (`client-launcher`)

Schreiben Sie die Implementierung. Sie können hier bei Bedarf andere Services injizieren.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/DiscordService.kt
class DiscordService(
    private val authService: IAuthService // Beispiel-Abhängigkeit
) : IDiscordService {
    override fun updatePresence(status: String) { ... }
}
```

### Schritt 3: Registrieren in Koin (`client-launcher`)

Öffnen Sie `hivens.launcher.di.Modules.kt` und fügen Sie die Definition zum `appModule` hinzu.

```kotlin
val appModule = module {
    // ... bestehende Definitionen
    single<IDiscordService> { DiscordService(get()) }
}
```

### Schritt 4: Injizieren in der UI (`client-ui`)

Jetzt können Sie ihn in Ihrem Controller oder Screen verwenden.

```kotlin
class LauncherController(
    private val discordService: IDiscordService
) { ... }
```

---

## 6. Fehlerbehebung

### `NoBeanDefFoundException`

**Fehler:** `No definition found for class '...' Check your definitions!`
**Ursache:** Sie haben **Schritt 3** vergessen. Die Klasse existiert, aber Koin weiß nichts davon.
**Lösung:** Fügen Sie `single { ... }` oder `singleOf(...)` in `Modules.kt` hinzu.

### `InstanceCreationException`

**Fehler:** `Could not create instance for ...`
**Ursache:** Eine der Abhängigkeiten, die Ihr Service benötigt, fehlt oder konnte nicht initialisiert werden.
**Lösung:** Prüfen Sie den Stacktrace, um zu sehen, *welches* Argument nicht aufgelöst werden konnte. Prüfen Sie rekursiv dessen Abhängigkeiten.

### Zirkuläre Abhängigkeit (Circular Dependency)

**Fehler:** `StackOverflow` während des Starts.
**Ursache:** Service A benötigt Service B, und Service B benötigt Service A.
**Lösung:** Refactoring der Logik. Lagern Sie die gemeinsame Logik in einen dritten Service C aus oder verwenden Sie `by inject()` (Lazy) innerhalb einer der Klassen (nicht empfohlen).
