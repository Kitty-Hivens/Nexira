# Aura Launcher Developer Wiki

> **Status:** Active Maintenance
> **Version:** 1.2.3-dev

Willkommen in der offiziellen technischen Dokumentation für den **Auralauncher**.
Dieses Wiki richtet sich an Core-Entwickler und Maintainer. Es bietet einen tiefen Einblick in die Architektur, Subsysteme und Designentscheidungen hinter dem Projekt.

## 🧭 Navigation

### 🏗️ Kernarchitektur

Verständnis des High-Level-Designs und der Code-Organisation.

* **[Architektur & Design](../de/architecture.md)**
    * *Themen:* Clean Architecture Layer, Modulaufbau, Tech-Stack.
* **[Dependency Injection](../de/dependency-injection.md)**
    * *Themen:* Koin-Setup, Service-Verkabelung, Hinzufügen neuer Komponenten.

### ⚙️ Interne Systeme

Der "Maschinenraum" des Launchers.

* **[Prozess-Lebenszyklus](../de/process-lifecycle.md)**
    * *Themen:* Start-Pipeline, Dateiverifizierung (Hashing), Java-Runtime-Prüfung, Prozessüberwachung.
* **[Netzwerk & Authentifizierung](../de/networking-auth.md)**
    * *Themen:* Ktor/OkHttp-Stack, Login-Flow, Serverlisten-Abruf, Session-Management.
* **[Datenpersistenz](../de/data-storage.md)**
    * *Themen:* JSON-Konfigurationsdateien, Verzeichnisstruktur, Profilverwaltung.

### 🎨 Benutzeroberfläche

Visuelles, Rendering und User Experience.

* **[UI & Theming Engine](../de/ui-theming.md)**
    * *Themen:* Compose Multiplatform, Saisonale Themen (`SeasonTheme`), Partikelsystem, Benutzerdefinierte Komponenten.

---

## 🚀 Schnellstart für Entwickler

1.  **Voraussetzungen:**
    * JDK 21+
    * IntelliJ IDEA (empfohlen)
    * Git

2.  **Projekt bauen:**
    ```bash
    # Aus dem Stammverzeichnis ausführen
    ./gradlew :client-ui:packageDistribution
    ```

3.  **Code-Stil:**
    * Folge den Standard-Kotlin-Konventionen.
    * Verwende `koinInject()` nur in Top-Level Composable oder Controllern.
    * Keine Logik in UI-Komponenten; nutze Controller.

---

## ⚖️ Lizenz

Aura Launcher ist Open-Source-Software, lizenziert unter der **GNU GPL v3**.
Siehe die [LICENSE](../../LICENSE) Datei für weitere Details.

*Dokumentation gepflegt vom Hivens Team.*
