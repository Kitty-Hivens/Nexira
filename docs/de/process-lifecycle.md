# Prozess-Lebenszyklus & Start-Pipeline

> **Modul:** `docs/de/process-lifecycle.md`
> **Kontext:** Detaillierte Aufschlüsselung der Spielstart-Sequenz, Dateiverifizierung und Prozess-Orchestrierung.

## 1. Die Start-Pipeline

Der Startprozess ist eine lineare Pipeline, die vom `LauncherService` verwaltet wird. Sie transformiert eine Benutzeranfrage in einen laufenden OS-Prozess. Die Pipeline ist nach dem **Fail-Fast**-Prinzip konzipiert: Wenn ein Schritt fehlschlägt (z. B. fehlendes Java, falscher Hash), stoppt die Ausführung sofort mit einer spezifischen Ausnahme.

### Sequenzdiagramm (Abstrakt)

1.  **Vorbereitung (Preparation):** Java Runtime verifizieren/installieren -> Verzeichnisstruktur sicherstellen.
2.  **Verifizierung (Verification):** Manifest abrufen -> Hashing & Integritätsprüfung -> Fehlende/Beschädigte Dateien herunterladen.
3.  **Konstruktion (Construction):** Native Bibliotheken auflösen -> Classpath bauen -> JVM-Argumente konstruieren.
4.  **Ausführung (Execution):** Prozess erzeugen -> Log-Listener anhängen.

---

## 2. Schritt 1: Umgebungsvorbereitung

Bevor Spieldateien berührt werden, stellt der Launcher sicher, dass die Host-Umgebung bereit ist. Diese Logik befindet sich in `EnvironmentPreparer` und `JavaManagerService`.

### 2.1 Auflösung der Java Runtime

**Service:** `JavaManagerService`

Der Launcher verlässt sich nicht auf das systemweite `JAVA_HOME`. Er verlangt strikt eine spezifische Java-Version für den Spielprozess (derzeit **Java 8, 17 oder 21**, abhängig von der Minecraft-Version).

1.  **Prüfung:** Verifiziert, ob eine gültige JRE im internen Pfad existiert (z. B. `updates/jre`).
2.  **Validierung:** Führt `java -version` aus, um zu bestätigen, dass die Binärdatei ausführbar ist und der geforderten Version/Architektur entspricht.
3.  **Fehlerbedingung:** Wenn Java fehlt oder defekt ist, wird eine `LaunchException` geworfen (Automatischer Java-Download ist geplant, erfolgt aber derzeit manuell/pre-packaged).

### 2.2 Verzeichnisstruktur

**Service:** `ClientFileHelper`

Stellt sicher, dass die Sandbox existiert:

* `client/`: Spiel-Stammordner (Arbeitsverzeichnis).
* `client/bin/`: Native Bibliotheken und Core-JARs.
* `client/assets/`: Minecraft Assets (Objekte, Indizes).
* `client/mods/`: Mod-Loader-Inhalte.

---

## 3. Schritt 2: Integritätsprüfung

**Service:** `FileIntegrityService`

Aura verwendet ein robustes Hashing-System, um zu garantieren, dass der Client exakt dem Zustand des Servers entspricht.

### 3.1 Das Manifest (`FileManifest`)

Die Verifizierung wird durch ein `FileManifest` DTO gesteuert, das vom Backend abgerufen wird.

* **Struktur:** Enthält eine Liste von `FileData`-Objekten.
* **Eigenschaften:** `path` (relativ), `hash` (SHA-1/SHA-256), `size` (Bytes), `required` (boolean).

### 3.2 Hashing-Algorithmus

Der Service iteriert durch jede im Manifest definierte Datei:

1.  **Existiert?** Wenn die Datei fehlt -> Markieren als **MISSING**.
2.  **Größe stimmt?** Wenn die Dateigröße vom Manifest abweicht -> Markieren als **INVALID**.
3.  **Hash stimmt?** Berechnet den SHA-1 (oder SHA-256) Hash der lokalen Datei.
    * Wenn `localHash != remoteHash` -> Markieren als **INVALID**.
4.  **Gültig:** Wenn alle Prüfungen bestanden -> Markieren als **VALID**.

**Optimierung:** Der Launcher führt diese Prüfungen parallel durch (unter Verwendung von Coroutines), um den Start bei großen Modpacks zu beschleunigen.

### 3.3 Behebung (Download)

**Service:** `FileDownloadService`

Jede als **MISSING** oder **INVALID** markierte Datei wird der Download-Warteschlange hinzugefügt.

* Dateien werden über `Ktor`/`OkHttp` heruntergeladen.
* Temporäre Dateien (`.part`) werden verwendet, um Korruption bei Download-Unterbrechungen zu verhindern.
* Nach dem Download wird der Hash erneut verifiziert.

---

## 4. Schritt 3: Befehlskonstruktion

**Service:** `GameCommandBuilder`

Sobald die Dateien sicher sind, konstruiert der Launcher den massiven Kommandozeilen-String zum Starten der JVM.

### 4.1 Argument-Gruppen

Die Argumente werden in einer bestimmten Reihenfolge gebaut:

1.  **Ausführbare Datei:** Pfad zur `java` Binary.
2.  **JVM Flags (Speicher):**
    * `-Xmx{allocated}M`: Heap-Größe (aus Einstellungen).
    * `-Xms512M`: Initiale Heap-Größe.
3.  **JVM Flags (System):**
    * `-Djava.library.path=...`: Pfad zu nativen Bibliotheken (LWJGL).
    * `-Dfile.encoding=UTF-8`: Kodierung erzwingen.
    * OS-spezifische Flags (z. B. macOS Dock-Icon-Einstellungen).
4.  **Classpath (`-cp`):**
    * **Service:** `ClasspathProvider`.
    * Scannt `client/bin` und `client/libraries`, um den Classpath-String zu bauen. Trennzeichen hängt vom OS ab (`:` für Unix, `;` für Windows).
5.  **Main Class:** z. B. `net.minecraft.client.main.Main`.
6.  **Spiel-Argumente:**
    * `--username`: Spieler-Login.
    * `--uuid`: Spieler-UUID.
    * `--accessToken`: Sitzungs-Token.
    * `--version`: Asset-Index-Version.
    * `--gameDir`: Pfad zu `client/`.
    * `--assetsDir`: Pfad zu `client/assets/`.

---

## 5. Schritt 4: Prozessausführung & Überwachung

**Service:** `LauncherService` & `ProcessLogHandler`

### 5.1 Prozess erzeugen

Die gebaute Befehlsliste wird an den `ProcessBuilder` übergeben.

* **Arbeitsverzeichnis:** Wird auf das Client-Root gesetzt (kritisch für Mods/Configs).
* **Umgebung:** Erbt den System-PATH, überschreibt aber spezifische Variablen bei Bedarf.

```kotlin
val process = processBuilder.start()
```

### 5.2 Log-Ausgabe-Handling

Der Minecraft-Prozess schreibt Logs nach `STDOUT` und Fehler nach `STDERR`.

* **Problem:** Wenn der Launcher auf den Prozess wartet, ohne diese Streams zu lesen, läuft der Puffer voll, was zum Hängen des Spiels führt (Deadlock).
* **Lösung:** `LauncherService` startet zwei Hintergrund-Coroutinen sofort nach dem Start:
1. Liest `process.inputStream` -> Leitet um zur Launcher-Konsole (INFO).
2. Liest `process.errorStream` -> Leitet um zur Launcher-Konsole (ERROR).



### 5.3 Beenden-Handling

Der `LauncherController` (in der UI-Schicht) wartet mittels `process.waitFor()` darauf, dass der Prozess beendet wird.

* **Exit Code 0:** Sauberes Herunterfahren.
* **Exit Code != 0:** Absturz. Der Controller lässt die Konsole offen, damit der Benutzer das Fehlerprotokoll sehen kann.
