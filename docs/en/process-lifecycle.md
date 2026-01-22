# Process Lifecycle & Launch Pipeline

> **Module:** `docs/en/process-lifecycle.md`
> **Context:** Detailed breakdown of the game launch sequence, file verification, and process orchestration.

## 1. The Launch Pipeline

The launch process is a linear pipeline managed by `LauncherService`. It transforms a user request into a running OS process. The pipeline is designed to be fail-fast: if any step fails (e.g., missing Java, bad hash), execution stops immediately with a specific exception.

### Sequence Diagram (Abstract)

1. **Preparation:** Verify/Install Java Runtime -> Ensure Directory Structure.
2. **Verification:** Fetch Manifest -> Hashing & Integrity Check -> Download Missing/Corrupted Files.
3. **Construction:** Resolve Natives -> Build Classpath -> Construct JVM Arguments.
4. **Execution:** Spawn Process -> Attach Log Listeners.

---

## 2. Step 1: Environment Preparation

Before touching game files, the launcher ensures the host environment is ready. This logic resides in `EnvironmentPreparer` and `JavaManagerService`.

### 2.1 Java Runtime Resolution

**Service:** `JavaManagerService`

The launcher does not rely on the system-wide `JAVA_HOME`. It strictly requires a specific Java version (currently configured for Java 21/25 compatibility).

1. **Check:** Verifies if a valid JRE exists at the internal path (e.g., `updates/jre`).
2. **Validation:** Runs `java -version` to confirm the binary is executable and matches the required version architecture.
3. **Fail Condition:** If Java is missing or broken, `LaunchException` is thrown (Auto-downloading of Java is planned but currently manual/pre-packaged).

### 2.2 Directory Structure

**Service:** `ClientFileHelper`

Ensures the sandbox exists:

* `client/`: Root game folder (working directory).
* `client/bin/`: Natives and core JARs.
* `client/assets/`: Minecraft assets (objects, indexes).
* `client/mods/`: Mod loader content.

---

## 3. Step 2: Integrity Verification

**Service:** `FileIntegrityService`

Aura uses a robust hashing system to guarantee that the client matches the server's state exactly.

### 3.1 The Manifest (`FileManifest`)

The verification is driven by a `FileManifest` DTO fetched from the backend.

* **Structure:** Contains a list of `FileData` objects.
* **Properties:** `path` (relative), `hash` (SHA-1/SHA-256), `size` (bytes), `required` (boolean).

### 3.2 Hashing Algorithm

The service iterates through every file defined in the manifest:

1. **Exists?** If the file is missing -> Mark as **MISSING**.
2. **Size Match?** If file size differs from manifest -> Mark as **INVALID**.
3. **Hash Match?** Calculates the SHA-1 (or SHA-256) hash of the local file.
* If `localHash != remoteHash` -> Mark as **INVALID**.


4. **Valid:** If all checks pass -> Mark as **VALID**.

**Optimization:** The launcher performs these checks in parallel (using Coroutines) to speed up startup on large modpacks.

### 3.3 Remediation (Download)

**Service:** `FileDownloadService`

Any file marked **MISSING** or **INVALID** is added to a download queue.

* Files are downloaded via `Ktor`/`OkHttp`.
* Temporary files (`.part`) are used to prevent corruption during download interruptions.
* After download, the hash is re-verified.

---

## 4. Step 3: Command Construction

**Service:** `GameCommandBuilder`

Once files are safe, the launcher constructs the massive command line string to start the JVM.

### 4.1 Argument Groups

The arguments are built in a specific order:

1. **Executable:** Path to `java` binary.
2. **JVM Flags (Memory):**
* `-Xmx{allocated}M`: Heap size (from Settings).
* `-Xms512M`: Initial heap size.


3. **JVM Flags (System):**
* `-Djava.library.path=...`: Path to native libraries (LWJGL).
* `-Dfile.encoding=UTF-8`: Enforce encoding.
* OS-specific flags (e.g., macOS dock icon settings).


4. **Classpath (`-cp`):**
* **Service:** `ClasspathProvider`.
* Scans `client/bin` and `client/libraries` to build the classpath string. Separator depends on OS (`:` for Unix, `;` for Windows).


5. **Main Class:** e.g., `net.minecraft.client.main.Main`.
6. **Game Arguments:**
* `--username`: Player login.
* `--uuid`: Player UUID.
* `--accessToken`: Session token.
* `--version`: Asset index version.
* `--gameDir`: Path to `client/`.
* `--assetsDir`: Path to `client/assets/`.



---

## 5. Step 4: Process Execution & Monitoring

**Service:** `LauncherService` & `ProcessLogHandler`

### 5.1 Spawning the Process

The built command list is passed to `ProcessBuilder`.

* **Working Directory:** Set to the client root (critical for mods/configs).
* **Environment:** Inherits system PATH but overrides specific vars if needed.

```kotlin
val process = processBuilder.start()
```

### 5.2 Log Output Handling

The Minecraft process writes logs to `STDOUT` and errors to `STDERR`.

* **Problem:** If the launcher waits for the process without reading these streams, the buffer fills up, causing the game to hang (Deadlock).
* **Solution:** `LauncherService` launches two background coroutines immediately after start:
1. Reads `process.inputStream` -> Redirects to Launcher Console (INFO).
2. Reads `process.errorStream` -> Redirects to Launcher Console (ERROR).



### 5.3 Exit Handling

The `LauncherController` (in UI layer) waits for the process to exit using `process.waitFor()`.

* **Exit Code 0:** Clean shutdown.
* **Exit Code != 0:** Crash. The controller keeps the console open so the user can see the error log.
