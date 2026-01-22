# UI & Theming Engine

> **Modul:** `docs/de/ui-theming.md`
> **Kontext:** Implementierungsdetails der visuellen Ebene, saisonale Anpassungslogik und benutzerdefinierte Rendering-Komponenten.

## 1. Technologie-Stack

Die Oberfläche des Auralaunchers ist vollständig mit **Compose Multiplatform (Desktop)** erstellt. Es verzichtet auf veraltete Swing/JavaFX-Komponenten zugunsten eines modernen, deklarativen UI-Ansatzes.

* **Rendering:** Skia (via Compose).
* **Fenstermanagement:** `androidx.compose.ui.window` (Rahmenlos, Transparent).
* **Zustandsverwaltung:** `mutableStateOf` / `remember` (Lokal), `StateFlow` (Global via Controller).

---

## 2. Saisonales Theming-System

Aura implementiert eine dynamische "Atmosphere Engine", die die visuelle Stimmung der Anwendung basierend auf dem realen Kalender anpasst. Diese Logik ist von der Rendering-Ebene entkoppelt und befindet sich in `client-core`.

### 2.1 Die Logik (`SeasonTheme`)

**Quelle:** `hivens.core.data.SeasonTheme`

Das Thema wird durch `SeasonTheme.getCurrentSeasonalTheme()` bestimmt, welches das Systemdatum prüft:

| Theme Enum | Auslösezeitraum   | Visuelle Absicht                                       |
|:-----------|:------------------|:-------------------------------------------------------|
| `NEW_YEAR` | 20. Dez – 14. Jan | Intensiver Schnee, festliche Atmosphäre.               |
| `WINTER`   | 1. Dez – 28. Feb  | Ruhigerer Schneefall, kalte Palette.                   |
| `SPRING`   | 1. Mär – 31. Mai  | Sakura-Blütenblätter (Rosa), sanfte Brise.             |
| `SUMMER`   | 1. Jun – 31. Aug  | Glühwürmchen (Gelb/Grünes Leuchten), nächtliche Vibes. |
| `AUTUMN`   | 1. Sep – 30. Nov  | Fallendes Laub (Orange/Braun), Windeffekte.            |
| `NONE`     | (Fallback)        | Statischer Hintergrund, keine Partikel.                |

### 2.2 Benutzer-Override

Der Benutzer kann die automatische Erkennung über die Einstellungen überschreiben. Diese Präferenz wird in `SettingsData` gespeichert und über den `ISettingsService` in die UI injiziert.

---

## 3. Rendering-Pipeline (`CelestiaBackground`)

Der Hintergrund ist kein statisches Bild. Es ist eine prozedural generierte Leinwand (Canvas), die radiale Verläufe mit einer Partikelsimulationsschicht kombiniert.

**Quelle:** `hivens.ui.Main.kt`

### 3.1 Der "Atmende" Hintergrund

Aura verwendet eine Endlosschleife für Animationen, um die Koordinaten der Hintergrundverläufe zu modifizieren und so einen "lebendigen" Effekt zu erzeugen.

```kotlin
val t by infiniteTransition.animateFloat(...)
val x1 = width * 0.5f + cos(t) * width * 0.3f
val y1 = height * 0.5f + sin(t) * height * 0.2f
```

* **Mathematik:** Verwendet `sin` und `cos` Funktionen, um die Gradientenzentren um den Mittelpunkt des Fensters kreisen zu lassen.
* **Farben:** Abgeleitet von `CelestiaTheme.colors.primary` und `success` mit geringer Alpha (0.05f - 0.15f), um die Lesbarkeit des Textes zu gewährleisten.

### 3.2 Partikelsystem (`SeasonalEffectsLayer`)

**Quelle:** `hivens.ui.components.Particle.kt`

Eine benutzerdefinierte, leichtgewichtige Partikel-Engine läuft über dem Hintergrund.

* **Zustand:** Partikel werden in einer `SnapshotStateList` gespeichert.
* **Update-Schleife:** `LaunchedEffect` mit `withFrameNanos` löst in jedem Frame ein Physik-Update aus.
* **Verhalten:**
* *Schnee:* Bewegt sich nach unten (`y + speed`), schwankt leicht auf X (`sin`). Respawnt oben.
* *Glühwürmchen:* Bewegen sich langsam in zufällige Richtungen, Deckkraft pulsiert (`alpha` Animation).
* *Sakura:* Bewegt sich nach unten rechts (`x + wind`, `y + gravity`), rotiert.



---

## 4. Komponenten-Architektur

Die UI folgt einer "Glassmorphismus"-Designsprache, implementiert durch benutzerdefinierte Composables.

### 4.1 GlassCard

Eine Container-Komponente, die Milchglas simuliert.

* **Visuelles:** Halbtransparente Oberfläche (`Surface` mit Alpha), abgerundete Ecken (`RoundedCornerShape`) und ein subtiler Rand.
* **Verwendung:** Wird für die Seitenleiste, Serverliste und Einstellungsfelder verwendet, damit der animierte Hintergrund durchscheinen kann.

### 4.2 Navigation (`AppState`)

Die Anwendung verwendet eine einfache State Machine für die Navigation und vermeidet komplexe Routing-Bibliotheken.

**Zustände:**

1. **`Splash`**: Initiales Laden (Asset-Prüfungen, Auto-Login).
2. **`Login`**: Eingabe der Anmeldedaten (falls Sitzung ungültig).
3. **`Shell`**: Der Hauptcontainer für authentifizierte Benutzer.

**Shell-Navigation:**
Innerhalb von `Shell` schaltet eine sekundäre Navigation zwischen Inhaltsbildschirmen (`Home`, `Profile`, `Settings`) um, während die Seitenleiste statisch bleibt.

---

## 5. Themen-Palette (`CelestiaTheme`)

**Quelle:** `hivens.ui.theme.CelestiaTheme.kt`

Die Anwendung definiert ein eigenes Farbsystem `CelestiaColors`, anstatt sich auf die Standard-Material-`Colors` zu verlassen.

* **Primary:** Hauptmarkenfarbe (Violett/Blau).
* **Background:** Tiefe dunkle Farbe (fast schwarz) für Kontrast.
* **Surface:** Etwas heller für Karten.
* **Text:** Variationen mit hoher (Primary) und mittlerer (Secondary) Hervorhebung.

Das `CelestiaTheme`-Composable stellt diese Farben über `LocalCelestiaColors` (CompositionLocal) bereit und macht sie überall im UI-Baum verfügbar.
