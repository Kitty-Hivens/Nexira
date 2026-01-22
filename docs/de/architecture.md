# Architektur & Design

> **Modul:** `docs/de/architecture.md`
> **Kontext:** Systemweite Architekturentscheidungen, Modulorganisation und Datenfluss.

## 1. High-Level Übersicht

**Aura Launcher** (ehemals SCOpenLauncher) implementiert eine modulare, reaktive Architektur, die darauf ausgelegt ist, die Benutzeroberfläche von der Geschäftslogik zu entkoppeln. Das Projekt folgt einer pragmatischen Implementierung der **Clean Architecture**, angepasst für Desktop Compose.

Das System ist in vier verschiedene Schichten unterteilt:

1.  **Präsentationsschicht (`client-ui`)**:
    * **Verantwortlichkeit:** Rendert die UI, verarbeitet Benutzereingaben und beobachtet den Status.
    * **Tech:** Compose Multiplatform (Desktop), Koin (Consumption).
    * **Einschränkung:** Enthält *keine* Geschäftslogik. Delegiert alle Aktionen an Controller.

2.  **Orchestrierungsschicht (`client-ui/logic`)**:
    * **Verantwortlichkeit:** Brücke zwischen UI- und Domänenschichten. Verwaltet den reaktiven Status (`LaunchState`), handhabt Coroutine-Scopes für UI-Aktionen und orchestriert mehrere Dienste (z. B. "Erst Login, dann Starten").
    * **Schlüsselkomponente:** `LauncherController`.

3.  **Infrastruktur-/Service-Schicht (`client-launcher`)**:
    * **Verantwortlichkeit:** Implementiert die Kern-Geschäftslogik, die in `client-core` definiert ist. Behandelt Datei-E/A, Netzwerk, Hashing und OS-Prozessmanagement.
    * **Tech:** Ktor Client (OkHttp Engine), `java.lang.ProcessBuilder`.

4.  **Domänen- & Vertragsschicht (`client-core`)**:
    * **Verantwortlichkeit:** Definiert *was* (Schnittstellen) und die *Daten* (DTOs), aber nicht das *wie*.
    * **Einschränkung:** Reines Kotlin. Keine Framework-Abhängigkeiten (Ktor, Compose usw.).

## 2. Modulaufbau

Das Projekt verwendet eine Multi-Modul-Gradle-Struktur, um die Trennung von Verantwortlichkeiten (Separation of Concerns) zu erzwingen.

| Modul                 | Namespace         | Rolle & Abhängigkeiten                                                                                                                                                                                                          |
|:----------------------|:------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`client-config`**   | `hivens.config`   | **Statische Konfiguration.**<br>Enthält Build-Time-Konstanten (`AppConfig`) wie Version, API-URLs und Standardfenstergrößen.<br>*Abhängigkeiten: Keine*                                                                         |
| **`client-core`**     | `hivens.core`     | **Verträge & Daten.**<br>Definiert die "API" der Anwendung. Enthält Schnittstellen (`ILauncherService`, `IAuthService`) und unveränderliche Datenmodelle (`SessionData`, `ServerProfile`).<br>*Abhängigkeiten: `client-config`* |
| **`client-launcher`** | `hivens.launcher` | **Implementierung.**<br>Der "Schwerarbeiter". Implementiert Schnittstellen aus `core`. Enthält die DI-Module (`appModule`, `networkModule`), die diese Implementierungen verkabeln.<br>*Abhängigkeiten: `client-core`*          |
| **`client-ui`**       | `hivens.ui`       | **Präsentation.**<br>Der Einstiegspunkt (`Main.kt`). Definiert die UI-Logik, Screens und Komponenten. Initialisiert den Koin-Graphen.<br>*Abhängigkeiten: `client-launcher`*                                                    |

## 3. Dependency Injection (Koin)

Die Anwendung baut ihren Objektgraphen zur Laufzeit mit **Koin** auf. Der Graph ist streng hierarchisch.

### Initialisierung

Der DI-Container wird in `hivens.ui.Main.kt` unmittelbar vor der Erstellung des Anwendungsfensters gestartet.

```kotlin
startKoin {
    modules(networkModule, appModule, uiModule)
}
```

### Moduldefinitionen

1. **`networkModule`** (Quelle: `client-launcher/di/Modules.kt`):
* Stellt Singleton-Instanzen von `OkHttpClient` (Engine) und `HttpClient` (Ktor) bereit.
* Konfiguriert SOCKS-Proxy, Timeouts und JSON-Serialisierung.


2. **`appModule`** (Quelle: `client-launcher/di/Modules.kt`):
* Bindet Core-Schnittstellen an Launcher-Implementierungen.
* Beispiel: `single<ILauncherService> { LauncherService(...) }`.
* Beispiel: `single<ISettingsService> { SettingsService(...) }`.


3. **`uiModule`** (Quelle: `client-ui/Main.kt`):
* Stellt UI-spezifische Controller bereit.
* Beispiel: `singleOf(::LauncherController)`.
* *Hinweis:* Lokal in `Main.kt` definiert, um `client-launcher` unabhängig von UI-Logik zu halten.



## 4. Datenfluss: Die Startsequenz

Die Interaktion zwischen den Schichten während eines Spielstarts veranschaulicht die Architektur:

1. **UI-Event:** Benutzer klickt auf "Spielen" im `DashboardScreen`.
2. **Controller-Aktion:** `DashboardScreen` ruft `controller.launch(session, server)` auf.
3. **Status-Update:** `LauncherController` aktualisiert `LaunchState` auf `Preparing`.
4. **Service-Orchestrierung:** `LauncherController` ruft `launcherService.launchClient(...)` auf.
* *Im Service:* Java prüfen → Dateien prüfen → Argumente bauen → Prozess starten.


5. **Prozessübergabe:** Der Service gibt einen `java.lang.Process` zurück.
6. **Überwachung:** `LauncherController` überwacht `process.waitFor()` in einem IO-Thread und aktualisiert `LaunchState` auf `GameRunning`.

## 5. Wichtige Designmuster

### 5.1 Interface Segregation (Schnittstellentrennung)

Das Modul `client-ui` instanziiert niemals Logikklassen direkt. Es fordert Abhängigkeiten über Schnittstellen an, die in `client-core` definiert sind.

* *Vorteil:* Wir können die gesamte `LauncherService`-Implementierung austauschen (z. B. für Tests oder eine andere Engine), ohne eine einzige Zeile UI-Code zu ändern.

### 5.2 Reaktiver Status (MVI-lite)

Die UI beobachtet eine einzige Quelle der Wahrheit (Single Source of Truth) für den Startstatus.

* **Status:** `LauncherController.state` (Typ: `StateFlow<LaunchState>`).
* **Events:** Die UI löst Funktionen auf dem Controller aus, der intern den Status mutiert. Die UI ändert den Status *niemals* direkt.

### 5.3 Theming Engine

Das Theming wird von einem benutzerdefinierten `SeasonTheme`-Enum in `client-core` gesteuert, entkoppelt von Compose.

* **Logik:** `SeasonTheme.getCurrentSeasonalTheme()` berechnet das Thema basierend auf dem Systemdatum.
* **Rendering:** `CelestiaTheme` und `CelestiaBackground` in `client-ui` verwenden dieses Enum, um visuelle Effekte (Schnee, Glühwürmchen usw.) zu rendern.
