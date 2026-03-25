package hivens.launcher.update

import hivens.config.AppConfig
import hivens.core.api.HttpClientProvider
import hivens.core.data.LauncherUpdate
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.contentLength
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

class UpdateService(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    dataDirectory: Path
) {
    private val logger = LoggerFactory.getLogger(UpdateService::class.java)
    private val httpClient get() = clientProvider.current
    private val updateDir = dataDirectory.resolve("updates")
    private val lastCheckFile = updateDir.resolve(".last_check")

    companion object {
        private const val GITHUB_API_LATEST   = "https://api.github.com/repos/Kitty-Hivens/Aura-Launcher/releases/latest"
        private const val GITHUB_API_RELEASES = "https://api.github.com/repos/Kitty-Hivens/Aura-Launcher/releases"
        private const val CHECK_INTERVAL_HOURS = 12L
    }

    init {
        if (!Files.exists(updateDir)) {
            Files.createDirectories(updateDir)
        }
    }

    /**
     * Checks availability of updates with caching.
     */
    suspend fun checkForUpdate(force: Boolean = false): LauncherUpdate? = withContext(Dispatchers.IO) {
        try {
            if (!force && !shouldCheck()) {
                logger.debug("Update check skipped (cooldown)")
                return@withContext null
            }

            logger.info("Checking for launcher updates...")

            val response = httpClient.get(GITHUB_API_LATEST) {
                header("Accept", "application/vnd.github.v3+json")
            }

            if (response.status.value != 200) {
                logger.warn("GitHub API returned ${response.status}")
                return@withContext null
            }

            val release = json.decodeFromString<GitHubRelease>(response.bodyAsText())
            updateLastCheck()

            val currentVersion = AppConfig.CLIENT_VERSION.removePrefix("v")
            val latestVersion = release.tagName.removePrefix("v")

            if (compareVersions(latestVersion, currentVersion) <= 0) {
                logger.info("Launcher is up to date ($currentVersion)")
                return@withContext null
            }

            val asset = findAssetForCurrentOS(release.assets) ?: run {
                logger.warn("No compatible asset found for current OS")
                return@withContext null
            }

            val checksum = extractChecksum(release.body, asset.name)
            val isCritical = release.name.contains("[CRITICAL]", ignoreCase = true) ||
                             release.body?.contains("CRITICAL", ignoreCase = true) == true

            logger.info("Update available: $currentVersion -> $latestVersion")

            return@withContext LauncherUpdate(
                version = release.tagName,
                downloadUrl = asset.browserDownloadUrl,
                checksum = checksum,
                changelog = fetchChangelogBetween(currentVersion, latestVersion),
                isCritical = isCritical
            )

        } catch (e: Exception) {
            logger.error("Failed to check for updates", e)
            null
        }
    }

    /**
     * Downloads the update with progress.
     */
    suspend fun downloadUpdate(
        update: LauncherUpdate,
        onProgress: (downloaded: Long, total: Long, speed: Double) -> Unit
    ): Path = withContext(Dispatchers.IO) {
        val fileName = update.downloadUrl.substringAfterLast("/")
        val targetFile = updateDir.resolve(fileName)

        if (Files.exists(targetFile) && verifyChecksum(targetFile, update.checksum)) {
            logger.info("Update file already downloaded and verified")
            return@withContext targetFile
        }

        Files.deleteIfExists(targetFile)
        logger.info("Downloading update from ${update.downloadUrl}")

        var lastUpdateTime = System.currentTimeMillis()
        var lastDownloadedBytes = 0L

        try {
            httpClient.prepareGet(update.downloadUrl).execute { response ->
                val channel = response.bodyAsChannel()
                val totalBytes = response.contentLength() ?: 0L
                var downloadedBytes = 0L

                FileOutputStream(targetFile.toFile()).use { output ->
                    val buffer = ByteArray(8192)
                    
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        
                        output.write(buffer, 0, read)
                        downloadedBytes += read

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 500) {
                            val deltaBytes = downloadedBytes - lastDownloadedBytes
                            val deltaTime = (currentTime - lastUpdateTime) / 1000.0
                            val speed = if (deltaTime > 0) deltaBytes / deltaTime else 0.0
                            
                            onProgress(downloadedBytes, totalBytes, speed)
                            
                            lastUpdateTime = currentTime
                            lastDownloadedBytes = downloadedBytes
                        }
                    }
                }

                onProgress(downloadedBytes, totalBytes, 0.0)
                logger.info("Download completed: ${downloadedBytes / 1024 / 1024} MB")
            }

            if (!verifyChecksum(targetFile, update.checksum)) {
                Files.delete(targetFile)
                throw SecurityException("Checksum verification failed!")
            }

            logger.info("Checksum verified successfully")
            targetFile
            
        } catch (e: Exception) {
            Files.deleteIfExists(targetFile)
            throw e
        }
    }

    fun cleanupOldUpdates() {
        try {
            Files.list(updateDir).use { stream ->
                stream
                    // FIX: CI produces .exe (Inno Setup), not .msi — match actual artifact names
                    .filter { it.fileName.toString().matches(Regex(".*\\.(exe|dmg|AppImage)$")) }
                    .forEach { file ->
                        runCatching { Files.delete(file) }
                            .onSuccess { logger.debug("Deleted old update: {}", file.fileName) }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to cleanup updates directory", e)
        }
    }

    // ========== INTERNAL (visible for testing) ==========

    internal fun shouldCheck(): Boolean {
        if (!Files.exists(lastCheckFile)) return true
        
        return runCatching {
            val lastCheck = Files.readString(lastCheckFile).toLongOrNull() ?: return true
            val hoursSince = ChronoUnit.HOURS.between(
                Instant.ofEpochMilli(lastCheck),
                Instant.now()
            )
            hoursSince >= CHECK_INTERVAL_HOURS
        }.getOrDefault(true)
    }

    private fun updateLastCheck() {
        runCatching {
            Files.writeString(lastCheckFile, System.currentTimeMillis().toString())
        }
    }

    /**
     * Selects the correct installer asset for the current OS.
     *
     * Windows: `.exe`  (Inno Setup — see setup.iss / build_release.yml)
     * macOS:   `.dmg`
     * Linux:   `.AppImage`
     */
    internal fun findAssetForCurrentOS(assets: List<GitHubAsset>): GitHubAsset? {
        val osName = System.getProperty("os.name").lowercase()
        
        return when {
            // FIX: was .msi — CI builds .exe via Inno Setup, not MSI
            osName.contains("windows") -> assets.find {
                it.name.endsWith(".exe") && it.name.contains("Setup", ignoreCase = true)
            }
            osName.contains("mac") -> assets.find { it.name.endsWith(".dmg") }
            osName.contains("linux") -> assets.find { it.name.endsWith(".AppImage") }
            else -> null
        }
    }

    /**
     * Extracts SHA256 checksum for [fileName] from the GitHub release body.
     *
     * Supports two formats commonly found in release notes:
     *   1. Markdown table:  `| \`filename\` | \`hash\` |`
     *   2. Plain text:      `SHA256: filename - hash`
     */
    internal fun extractChecksum(releaseBody: String?, fileName: String): String {
        if (releaseBody == null) return ""

        // Format 1: Markdown table row  —  | `AuraLauncher-1.3.0-Setup.exe` | `abcdef...` |
        val tablePattern = """\|\s*`${Regex.escape(fileName)}`\s*\|\s*`([a-fA-F0-9]{64})`\s*\|""".toRegex()
        tablePattern.find(releaseBody)?.groupValues?.get(1)?.let { return it }

        // Format 2: Plain text  —  SHA256: filename - hash
        val plainPattern = """SHA256:\s*${Regex.escape(fileName)}\s*-\s*([a-fA-F0-9]{64})""".toRegex()
        return plainPattern.find(releaseBody)?.groupValues?.get(1) ?: ""
    }

    internal fun verifyChecksum(file: Path, expectedChecksum: String): Boolean {
        if (expectedChecksum.isEmpty()) {
            logger.warn("No checksum provided, skipping verification")
            return true
        }

        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            
            Files.newInputStream(file).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }

            val calculated = digest.digest().joinToString("") { "%02x".format(it) }
            calculated.equals(expectedChecksum, ignoreCase = true).also { isValid ->
                if (!isValid) {
                    logger.error("Checksum mismatch! Expected: $expectedChecksum, Got: $calculated")
                }
            }
        }.getOrDefault(false)
    }

    internal fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0

            if (p1 != p2) return p1.compareTo(p2)
        }

        return 0
    }

    private suspend fun fetchChangelogBetween(
        currentVersion: String,
        latestVersion: String
    ): String {
        val response = httpClient.get(GITHUB_API_RELEASES) {
            header("Accept", "application/vnd.github.v3+json")
        }
        if (response.status.value != 200) return ""

        val releases = json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())

        return releases
            .filter { release ->
                val v = release.tagName.removePrefix("v")
                compareVersions(v, currentVersion) > 0 &&
                        compareVersions(v, latestVersion)  <= 0
            }
            .sortedByDescending { it.tagName }
            .mapNotNull { release ->
                val section = extractWhatsChanged(release.body)
                if (section.isBlank()) null  // skip releases with no parseable changelog
                else "## ${release.tagName}\n\n$section"
            }
            .joinToString("\n\n---\n\n")
            .ifBlank { "No changelog available" }
    }

    private fun extractWhatsChanged(body: String?): String {
        if (body == null) return ""

        // Slice between "## What's Changed" and the next "---" or "##"
        val start = body.indexOf("## What's Changed")
        if (start == -1) return body.substringBefore("---").trim()

        val afterHeader = body.substring(start + "## What's Changed".length)
        return afterHeader
            .substringBefore("\n---")
            .substringBefore("\n## ")
            .trim()
            .ifBlank { "" }
    }
}

// ========== GITHUB API MODELS ==========

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset>,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long
)
