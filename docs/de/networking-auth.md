# Netzwerk & Authentifizierung

> **Modul:** `docs/de/networking-auth.md`
> **Kontext:** API-Kommunikation, Sitzungsverwaltung und Serverdaten-Synchronisation.

## 1. Konfiguration des Netzwerk-Stacks

Der Aura Launcher zentralisiert die gesamte HTTP-Kommunikation über einen modernen, asynchronen Netzwerk-Stack, der vom **Ktor Client** mit der **OkHttp**-Engine bereitgestellt wird.

### 1.1 Infrastruktur (`networkModule`)

**Quelle:** `hivens.launcher.di.Modules.kt`

Der Netzwerk-Stack wird als Singleton im Koin-Grafen initialisiert.

* **OkHttpClient:** Dient als zugrundeliegende Engine. Konfiguriert mit **SOCKS Proxy**-Unterstützung (immer aktiviert über `AppConfig.Proxy`) und verlängerten Timeouts für langsame Verbindungen.
* **Ktor HttpClient:** Die Hauptschnittstelle für Anfragen. Er kapselt den vorkonfigurierten `OkHttpClient` und installiert Standard-Plugins:
    * `ContentNegotiation`: Verwendet `kotlinx.serialization` zum Parsen von JSON.
    * `HttpTimeout`: Setzt globale Anfrage-Limits (10 Min. pro Anfrage, 30 Sek. für Verbindung).
    * `DefaultRequest`: Fügt automatisch den `User-Agent`-Header (`SMARTYlauncher/X.X.X`) und `Content-Type` hinzu.

```kotlin
// Konzeptionelle Konfiguration
single<HttpClient> {
    HttpClient(OkHttp) {
        engine { preconfigured = get<OkHttpClient>() }
        install(ContentNegotiation) { json(...) }
    }
}
```

---

## 2. Authentifizierungsarchitektur

Das Authentifizierungssystem fungiert als Brücke zum SmartyCraft-Backend (API V3). Es verwendet eine benutzerdefinierte Implementierung anstelle einer deklarativen REST-Schnittstelle.

### 2.1 Der Vertrag (`IAuthService`)

**Quelle:** `hivens.core.api.interfaces.IAuthService`

Das Core-Modul definiert den Vertrag, der von der UI-Schicht verwendet wird.

```kotlin
interface IAuthService {
    @Throws(AuthException::class)
    suspend fun login(login: String, password: String, serverId: String): SessionData
}
```

### 2.2 Implementierung (`AuthService`)

**Quelle:** `hivens.core.api.AuthService`

Die Implementierung verwendet `HttpClient`, um eine Form-Data-Anfrage an `AppConfig.AUTH_URL` zu senden.

* **Sicherheitslogik:**
1. **MD5-Hashing:** Das Passwort wird vor dem Senden gehasht (MD5).
2. **Hardware-ID (MAC):** Eine zufällige MAC-Adresse wird generiert, um bei jedem Login ein neues Gerät zu emulieren (`generateRandomMac`).
3. **Game-Token-Generierung:** Der Service implementiert einen benutzerdefinierten kryptographischen Handshake, der AES-Entschlüsselung des Sitzungsschlüssels und MD5-"Salting" umfasst (`generateGameToken`).


* **Anfrageformat:**
  Sendet einen Formularparameter `json`, der ein serialisiertes `AuthRequest`-Objekt mit Systemtelemetrie (OS, Java-Version, Bitness) enthält.
* **Antwortverarbeitung:**
* Parst manuell rohe String-Antworten, um veraltete Textfehler zu behandeln ("Bad login", "User not found").
* Dekodiert JSON in `AuthResponse`, wenn die erste Prüfung bestanden wird.



### 2.3 Sitzungsdaten (`SessionData`)

**Quelle:** `hivens.core.data.SessionData`

Ein erfolgreicher Login gibt ein `SessionData`-Objekt zurück.

| Eigenschaft    | Beschreibung                                                                               |
|----------------|--------------------------------------------------------------------------------------------|
| `accessToken`  | Das berechnete "Finale Game-Token", das für den Serverbeitritt erforderlich ist.           |
| `uuid`         | Die UUID des Spielers (bereinigt, ohne Bindestriche).                                      |
| `fileManifest` | Die Dateiliste (`FileManifest`), die vom Server zur Integritätsprüfung zurückgegeben wird. |
| `balance`      | Das Guthaben des Benutzers (Echtgeld/Münzen), das vom Auth-System zurückgegeben wird.      |

---

## 3. Server-Management

Das Abrufen von Serverstatus und Nachrichten wird vom `ServerListService` über das Repository verwaltet.

### 3.1 Repository-Pattern

**Quelle:** `hivens.core.api.ServerRepository`

Das `ServerRepository` ist für das Abrufen und Cachen der `DashboardData` verantwortlich.

* **Mechanismus:** Führt eine GET-Anfrage an das Backend aus.
* **Daten:** Gibt eine `SmartyResponse` zurück, die die Serverliste und Nachrichten enthält.

---

## 4. Datei-Downloads

Spiel-Assets und Bibliotheken werden mit dem `FileDownloadService` heruntergeladen.

* **Engine:** Verwendet dieselbe `OkHttpClient`-Instanz (direkt oder über Ktor) und nutzt den gemeinsamen Thread-Pool.
* **Integrität:** Downloads werden gegen die im `FileManifest` (aus der Auth-Antwort) bereitgestellten Hashes geprüft.
