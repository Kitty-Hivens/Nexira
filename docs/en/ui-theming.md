# UI & Theming Engine

> **Module:** `docs/en/ui-theming.md`
> **Context:** Implementation details of the visual layer, seasonal adaptation logic, and custom rendering components.

## 1. Technology Stack

Aura Launcher's interface is built entirely with **Compose Multiplatform (Desktop)**. It abandons legacy Swing/JavaFX components in favor of a modern, declarative UI approach.

* **Rendering:** Skia (via Compose).
* **Window Management:** `androidx.compose.ui.window` (Undecorated, Transparent).
* **State Management:** `mutableStateOf` / `remember` (Local), `StateFlow` (Global via Controllers).

---

## 2. Seasonal Theming System

Aura implements a dynamic "Atmosphere Engine" that adapts the visual mood of the application based on the real-world calendar. This logic is decoupled from the rendering layer, residing in `client-core`.

### 2.1 The Logic (`SeasonTheme`)

**Source:** `hivens.core.data.SeasonTheme`

The theme is determined by `SeasonTheme.getCurrentSeasonalTheme()`, which checks the system date:

| Theme Enum | Trigger Period  | Visual Intent                                |
|------------|-----------------|----------------------------------------------|
| `NEW_YEAR` | Dec 20 – Jan 14 | Intense snow, festive atmosphere.            |
| `WINTER`   | Dec 1 – Feb 28  | Calmer snow, cold palette.                   |
| `SPRING`   | Mar 1 – May 31  | Sakura petals (Pink), gentle breeze.         |
| `SUMMER`   | Jun 1 – Aug 31  | Fireflies (Yellow/Green glow), night vibes.  |
| `AUTUMN`   | Sep 1 – Nov 30  | Falling leaves (Orange/Brown), wind effects. |
| `NONE`     | (Fallback)      | Static background, no particles.             |

### 2.2 User Override

The user can override the automatic detection via Settings. This preference is persisted in `SettingsData` and injected into the UI via `ISettingsService`.

---

## 3. Rendering Pipeline (`CelestiaBackground`)

The background is not a static image. It is a procedurally generated canvas that combines radial gradients with a particle simulation layer.

**Source:** `hivens.ui.Main.kt`

### 3.1 The "Breathing" Background

Aura uses an infinite animation loop to modify the coordinates of the background gradients, creating a "living" effect.

```kotlin
val t by infiniteTransition.animateFloat(...)
val x1 = width * 0.5f + cos(t) * width * 0.3f
val y1 = height * 0.5f + sin(t) * height * 0.2f
```

* **Math:** Uses `sin` and `cos` functions to orbit the gradient centers around the window's midpoint.
* **Colors:** Derived from `CelestiaTheme.colors.primary` and `success` with low alpha (0.05f - 0.15f) to ensure text readability.

### 3.2 Particle System (`SeasonalEffectsLayer`)

**Source:** `hivens.ui.components.Particle.kt`

A custom lightweight particle engine runs on top of the background.

* **State:** Particles are stored in a `SnapshotStateList`.
* **Update Loop:** `LaunchedEffect` with `withFrameNanos` triggers a physics update every frame.
* **Behavior:**
* *Snow:* Moves down (`y + speed`), slightly wobbles on X (`sin`). Respawns at top.
* *Fireflies:* Moves slowly in random directions, opacity pulsates (`alpha` animation).
* *Sakura:* Moves down-right (`x + wind`, `y + gravity`), rotates.



---

## 4. Component Architecture

The UI follows a "Glassmorphism" design language, implemented via custom composables.

### 4.1 GlassCard

A container component that simulates frosted glass.

* **Visuals:** Semi-transparent surface (`Surface` with alpha), rounded corners (`RoundedCornerShape`), and a subtle border.
* **Usage:** Used for the Sidebar, Server List, and Settings panels to allow the animated background to shine through.

### 4.2 Navigation (`AppState`)

The application uses a simple State Machine for navigation, avoiding complex routing libraries.

**States:**

1. **`Splash`**: Initial loading (Asset checks, Auto-login).
2. **`Login`**: Credential entry (if session invalid).
3. **`Shell`**: The main container for authenticated users.

**Shell Navigation:**
Inside `Shell`, a secondary navigation switches between content screens (`Home`, `Profile`, `Settings`) while keeping the Sidebar static.

---

## 5. Theme Palette (`CelestiaTheme`)

**Source:** `hivens.ui.theme.CelestiaTheme.kt`

The application defines a custom color system `CelestiaColors` rather than relying on the standard Material `Colors`.

* **Primary:** Main brand color (Violet/Blue).
* **Background:** Deep dark color (almost black) for contrast.
* **Surface:** Slightly lighter for cards.
* **Text:** High-emphasis (Primary) and Medium-emphasis (Secondary) variations.

The `CelestiaTheme` composable provides these colors via `LocalCelestiaColors` CompositionLocal, making them accessible anywhere in the UI tree.
