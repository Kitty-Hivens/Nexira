package hivens.launcher.update

import hivens.config.Branding
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.LauncherUpdate
import hivens.core.data.ReleaseManifest
import hivens.core.data.UpdateChannelMeta
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
    dataDirectory: Path,
    private val settingsService: ISettingsService
) {
    private val logger = LoggerFactory.getLogger(UpdateService::class.java)
    private val httpClient get() = clientProvider.current
    private val updateDir = dataDirectory.resolve("updates")
    private val lastCheckFile = updateDir.resolve(".last_check")
    private val lastMetaCheckFile = updateDir.resolve(".last_meta_check")

    companion object {
        private const val GITHUB_REPO          = "Kitty-Hivens/Aura-Launcher"
        private const val GITHUB_API_LATEST    = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        private const val GITHUB_API_RELEASES  = "https://api.github.com/repos/$GITHUB_REPO/releases"
        private const val GITHUB_RELEASE_PAGE  = "https://github.com/$GITHUB_REPO/releases/tag"
        private const val CHECK_INTERVAL_HOURS = 12L

        /**
         * Meta-only poll cadence. Far tighter than [CHECK_INTERVAL_HOURS]
         * because the meta endpoint is `update-channel.json` on raw GitHub --
         * a few hundred bytes, no API rate limit (raw.githubusercontent.com
         * is throttled per-IP but not per-hour like the v3 API). At 5 min
         * the launcher hits 12 reqs/hour against raw, well below any
         * realistic ceiling, while still surfacing mandatory rollouts
         * to long-running launcher sessions in near-real-time.
         */
        private const val META_CHECK_INTERVAL_MINUTES = 5L
        private const val MANIFEST_ASSET_NAME  = "release-manifest.json"

        // ── Out-of-band channel metadata ──────────────────────────────────────
        // Lives on the `stable` branch so it can be edited via a single PR
        // without cutting a new release. Any launcher hitting this URL bypasses
        // the SMARTYcraft proxy (direct channel) so the update flow remains
        // operational even when the upstream proxy is dead.
        private const val UPDATE_CHANNEL_META_URL =
            "https://raw.githubusercontent.com/$GITHUB_REPO/stable/meta/update-channel.json"

        // How many releases to fetch when the user opts into prereleases --
        // GitHub returns 30 per page by default; we never need that many to
        // find the most recently published non-draft entry.
        private const val PRERELEASE_PAGE_SIZE = 20
    }

    init {
        if (!Files.exists(updateDir)) {
            Files.createDirectories(updateDir)
        }
    }

    /**
     * Checks availability of updates with caching.
     *
     * The selected channel (stable vs prerelease) and whether mandatory updates
     * are honoured both come from [ISettingsService] -- see the experimental
     * fields on `SettingsData`. The master `experimentalFeaturesEnabled` toggle
     * gates both children: if it's off, both sub-toggles are forced to false
     * regardless of their stored values.
     */
    suspend fun checkForUpdate(force: Boolean = false): LauncherUpdate? = withContext(Dispatchers.IO) {
        try {
            if (!force && !shouldCheck()) {
                logger.debug("Update check skipped (cooldown)")
                return@withContext null
            }

            val settings = settingsService.getSettings()
            val experimentalOn = settings.experimentalFeaturesEnabled
            val prereleaseChannel = experimentalOn && settings.prereleaseChannelEnabled
            val mandatoryEnabled  = experimentalOn && settings.mandatoryUpdatesEnabled

            logger.info(
                "Checking for launcher updates (channel: {}, mandatory: {})",
                if (prereleaseChannel) "prerelease" else "stable",
                if (mandatoryEnabled) "enforced" else "advisory"
            )

            val release = fetchLatestRelease(includePrereleases = prereleaseChannel)
                ?: return@withContext null
            updateLastCheck()

            val currentVersion = Branding.VERSION.removePrefix("v")
            val latestVersion = release.tagName.removePrefix("v")

            // Out-of-band channel meta -- fetched even when "up to date" so a
            // mandatory floor can still trigger on the same version (e.g. user
            // is on 2.2.7, latest is 2.2.7, but mandatory_min got bumped to
            // 2.2.7 -- no-op; mandatory_min = 2.2.8 -- needs latest, which IS
            // newer, so the path below catches it).
            val channelMeta = tryFetchChannelMeta()
            val belowMandatoryFloor = mandatoryEnabled &&
                    channelMeta?.mandatoryMinVersion?.let { floor ->
                        compareVersions(currentVersion, floor.removePrefix("v")) < 0
                    } == true

            if (compareVersions(latestVersion, currentVersion) <= 0) {
                logger.info("Launcher is up to date ($currentVersion)")
                return@withContext null
            }

            val asset = findAssetForCurrentOS(release.assets) ?: run {
                logger.warn("No compatible asset found for current OS")
                return@withContext null
            }

            // release-manifest.json is mandatory: it pins the SHA-256
            // the auto-updater verifies before launching the installer.
            // Without a manifest (or without an entry for this asset)
            // we refuse to construct an update -- auto-install of
            // unverified bytes is a remote-code-execution path if the
            // release page were ever tampered with. Older releases
            // that ship no manifest require manual reinstall.
            val manifest = tryFetchManifest(release) ?: run {
                logger.warn(
                    "Refusing auto-update: release {} ships no release-manifest.json " +
                        "for {}. User must reinstall manually.",
                    release.tagName, asset.name,
                )
                return@withContext null
            }
            val checksum = manifest.assets.find { it.name == asset.name }?.sha256
            if (checksum.isNullOrBlank()) {
                logger.warn(
                    "Refusing auto-update: release {} manifest does not pin SHA-256 for {}. " +
                        "User must reinstall manually.",
                    release.tagName, asset.name,
                )
                return@withContext null
            }
            val highlights = manifest.highlights?.takeIf { it.isNotBlank() }

            val isCritical = release.name.contains("[CRITICAL]", ignoreCase = true) ||
                             release.body?.contains("CRITICAL", ignoreCase = true) == true

            logger.info(
                "Update available: {} -> {} (mandatory: {})",
                currentVersion, latestVersion, belowMandatoryFloor,
            )

            return@withContext LauncherUpdate(
                version = release.tagName,
                downloadUrl = asset.browserDownloadUrl,
                checksum = checksum,
                changelog = fetchChangelogBetween(currentVersion, latestVersion),
                highlights = highlights,
                releasePageUrl = "$GITHUB_RELEASE_PAGE/${release.tagName}",
                isCritical = isCritical,
                isMandatory = belowMandatoryFloor,
                mandatoryReason = if (belowMandatoryFloor) channelMeta.reason else null
            )

        } catch (e: Exception) {
            logger.error("Failed to check for updates", e)
            null
        }
    }

    /**
     * Picks the update target.
     *
     * - [includePrereleases] = false -> hits `/releases/latest`, which by
     *   GitHub's contract excludes drafts and prereleases.
     * - [includePrereleases] = true -> lists the most recent
     *   [PRERELEASE_PAGE_SIZE] releases and returns the first non-draft one.
     *   GitHub returns them by `published_at` descending, so this matches
     *   "newest published thing of either kind".
     */
    internal suspend fun fetchLatestRelease(includePrereleases: Boolean): GitHubRelease? {
        return if (includePrereleases) {
            val response = httpClient.get(GITHUB_API_RELEASES) {
                header("Accept", "application/vnd.github.v3+json")
                parameter("per_page", PRERELEASE_PAGE_SIZE)
            }
            if (response.status.value != 200) {
                logger.warn("GitHub /releases returned {}", response.status)
                return null
            }
            json.decodeFromString<List<GitHubRelease>>(response.bodyAsText())
                .firstOrNull { !it.draft }
                ?: run {
                    logger.warn("No non-draft releases found in /releases response")
                    null
                }
        } else {
            val response = httpClient.get(GITHUB_API_LATEST) {
                header("Accept", "application/vnd.github.v3+json")
            }
            if (response.status.value != 200) {
                logger.warn("GitHub /releases/latest returned {}", response.status)
                return null
            }
            json.decodeFromString<GitHubRelease>(response.bodyAsText())
        }
    }

    /**
     * Fetches the out-of-band update-channel metadata. Returns null on any
     * error (missing file, parse failure, network issue) -- callers must
     * treat null as "no mandatory floor".
     */
    internal suspend fun tryFetchChannelMeta(): UpdateChannelMeta? = try {
        val response = httpClient.get(UPDATE_CHANNEL_META_URL) {
            header("Accept", "application/json")
        }
        if (response.status.value != 200) {
            logger.debug("update-channel.json fetch returned {}", response.status)
            null
        } else {
            json.decodeFromString<UpdateChannelMeta>(response.bodyAsText())
        }
    } catch (e: Exception) {
        logger.debug("Failed to fetch update-channel.json", e)
        null
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
                    // .exe = Inno Setup (Windows installer), .zip = Windows portable
                    // distribution, .dmg = macOS, .AppImage = Linux.
                    .filter { it.fileName.toString().matches(Regex(".*\\.(exe|zip|dmg|AppImage)$")) }
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

    internal fun shouldCheckMeta(): Boolean {
        if (!Files.exists(lastMetaCheckFile)) return true

        return runCatching {
            val last = Files.readString(lastMetaCheckFile).toLongOrNull() ?: return true
            val minsSince = ChronoUnit.MINUTES.between(
                Instant.ofEpochMilli(last),
                Instant.now()
            )
            minsSince >= META_CHECK_INTERVAL_MINUTES
        }.getOrDefault(true)
    }

    private fun updateLastMetaCheck() {
        runCatching {
            Files.writeString(lastMetaCheckFile, System.currentTimeMillis().toString())
        }
    }

    /**
     * Lightweight near-real-time mandatory probe.
     *
     * Polls only `meta/update-channel.json` (a few hundred bytes on raw
     * GitHub, no v3 API rate limit) at the [META_CHECK_INTERVAL_MINUTES]
     * cadence. When the published `mandatory_min_version` rises above the
     * installed version it bypasses [shouldCheck]'s 12 h cooldown to fetch
     * the actual release immediately -- by definition, a mandatory rollout
     * needs to reach the user *now*, not on the next routine check.
     *
     * Returns null when:
     *   - the meta cooldown hasn't elapsed yet,
     *   - mandatory updates are disabled in settings (master OFF or child OFF),
     *   - the channel meta file is missing / unreadable / has no floor,
     *   - the floor is at-or-below the installed version,
     *   - the subsequent release fetch fails for any reason.
     *
     * Designed to be called in a polling loop from the UI layer; failures
     * are logged at warn level and the caller should simply retry on the
     * next tick.
     */
    suspend fun checkForMandatoryUpdate(): LauncherUpdate? = withContext(Dispatchers.IO) {
        try {
            if (!shouldCheckMeta()) return@withContext null

            val settings = settingsService.getSettings()
            val mandatoryEnabled = settings.experimentalFeaturesEnabled &&
                                   settings.mandatoryUpdatesEnabled
            if (!mandatoryEnabled) {
                updateLastMetaCheck()
                return@withContext null
            }

            updateLastMetaCheck()
            val meta = tryFetchChannelMeta() ?: return@withContext null
            val floor = meta.mandatoryMinVersion?.removePrefix("v")
                ?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            val current = Branding.VERSION.removePrefix("v")
            if (compareVersions(current, floor) >= 0) return@withContext null

            logger.warn(
                "Mandatory floor {} > installed {}; forcing release check (reason: {})",
                floor, current, meta.reason ?: "<none>"
            )
            // force=true skips the 12h release-check cooldown
            checkForUpdate(force = true)?.takeIf { it.isMandatory }
        } catch (e: Exception) {
            logger.warn("Mandatory poll failed", e)
            null
        }
    }

    /**
     * Selects the correct installer asset for the current OS.
     *
     * Windows: `.exe`  (Inno Setup -- see setup.iss / build_release.yml)
     * macOS:   `-aarch64.dmg` on Apple Silicon, `-x86_64.dmg` on Intel.
     *          Falls back to any `.dmg` for legacy pre-dual-arch
     *          releases that shipped a single ARM64-only DMG.
     * Linux:   `.AppImage`
     */
    internal fun findAssetForCurrentOS(assets: List<GitHubAsset>): GitHubAsset? {
        val osName = System.getProperty("os.name").lowercase()

        return when {
            // Windows installer is Inno Setup (`.exe`), not MSI -- see
            // `setup.iss` + `build_release.yml`.
            osName.contains("windows") -> assets.find {
                it.name.endsWith(".exe") && it.name.contains("Setup", ignoreCase = true)
            }
            osName.contains("mac") -> {
                // Match on arch first. Picking the first `.dmg` blindly
                // breaks dual-arch releases: aarch64 + x86_64 both ship
                // and the alphabetically-first one would leave Intel
                // users with an ARM64 DMG that fails with "not
                // supported on this Mac" before Gatekeeper fires.
                val arch = System.getProperty("os.arch", "").lowercase()
                val archSuffix = when {
                    arch.contains("aarch64") || arch.contains("arm64") -> "aarch64.dmg"
                    else -> "x86_64.dmg"
                }
                assets.find { it.name.endsWith(archSuffix) }
                    // Legacy fallback for releases predating dual-arch
                    // (single .dmg with no arch suffix). Will return wrong
                    // arch in degraded cases but better than no update at all.
                    ?: assets.find { it.name.endsWith(".dmg") }
            }
            osName.contains("linux") -> assets.find { it.name.endsWith(".AppImage") }
            else -> null
        }
    }

    /**
     * Looks for `release-manifest.json` among the release assets and parses it.
     * Returns null if the asset is absent (legacy release) or the body is not
     * a valid manifest document -- callers must handle null and fall back.
     */
    internal suspend fun tryFetchManifest(release: GitHubRelease): ReleaseManifest? {
        val asset = release.assets.find { it.name == MANIFEST_ASSET_NAME } ?: return null
        return try {
            val response = httpClient.get(asset.browserDownloadUrl) {
                header("Accept", "application/json")
            }
            if (response.status.value != 200) {
                logger.warn("release-manifest.json fetch returned {}", response.status)
                return null
            }
            json.decodeFromString<ReleaseManifest>(response.bodyAsText())
        } catch (e: Exception) {
            logger.warn("Failed to fetch or parse release-manifest.json", e)
            null
        }
    }

    /**
     * Extracts SHA256 checksum for [fileName] from a GitHub release
     * body. Not on the active update path -- release-manifest.json is
     * the single source of truth for verifiable hashes. Kept (and
     * tested) for a future out-of-band recovery flow (e.g. signed
     * manifest fetch from a secondary mirror).
     *
     * Supports two formats commonly found in release notes:
     *   1. Markdown table:  `| \`filename\` | \`hash\` |`
     *   2. Plain text:      `SHA256: filename - hash`
     */
    internal fun extractChecksum(releaseBody: String?, fileName: String): String {
        if (releaseBody == null) return ""

        // Format 1: Markdown table row  --  | `AuraLauncher-1.3.0-Setup.exe` | `abcdef...` |
        val tablePattern = """\|\s*`${Regex.escape(fileName)}`\s*\|\s*`([a-fA-F0-9]{64})`\s*\|""".toRegex()
        tablePattern.find(releaseBody)?.groupValues?.get(1)?.let { return it }

        // Format 2: Plain text  --  SHA256: filename - hash
        val plainPattern = """SHA256:\s*${Regex.escape(fileName)}\s*-\s*([a-fA-F0-9]{64})""".toRegex()
        return plainPattern.find(releaseBody)?.groupValues?.get(1) ?: ""
    }

    /**
     * Defense-in-depth gate at the install boundary: an empty
     * [expectedChecksum] is a verification failure, not "skip".
     * [checkForUpdate] already refuses to construct an update without
     * a manifest-pinned hash, so empty here means a bug elsewhere
     * (stale `LauncherUpdate`, malformed deserialization) -- fail
     * closed instead of trusting unverified bytes.
     */
    internal fun verifyChecksum(file: Path, expectedChecksum: String): Boolean {
        if (expectedChecksum.isBlank()) {
            logger.error("Refusing to install: no checksum to verify against ({})", file.fileName)
            return false
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

    /**
     * SemVer-ish comparison.
     *
     * 1. Numeric base (`X.Y.Z`) compared element-wise; missing
     *    segments = 0.
     * 2. If bases tie, the prerelease suffix decides:
     *    - no suffix on either side -> equal
     *    - one side has no suffix -> it wins (final > rc / beta / alpha)
     *    - both have suffixes -> natural-order compare token-by-token
     *      (digit runs as numbers, non-digit runs as text), giving
     *      `alpha < beta < rc1 < rc2 < rc10`. Pure lex compare would
     *      incorrectly rank `rc10 < rc2`; the failure mode is silent
     *      so the natural-order path is the load-bearing one.
     */
    internal fun compareVersions(v1: String, v2: String): Int {
        val base1 = v1.substringBefore('-')
        val base2 = v2.substringBefore('-')
        val parts1 = base1.split('.').map { it.toIntOrNull() ?: 0 }
        val parts2 = base2.split('.').map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0
            if (p1 != p2) return p1.compareTo(p2)
        }

        // Bases equal -- break the tie on prerelease suffix.
        val suffix1 = v1.substringAfter('-', missingDelimiterValue = "")
        val suffix2 = v2.substringAfter('-', missingDelimiterValue = "")
        return when {
            suffix1.isEmpty() && suffix2.isEmpty() -> 0
            suffix1.isEmpty() -> 1   // v1 = final, v2 = prerelease -> v1 wins
            suffix2.isEmpty() -> -1  // v1 = prerelease, v2 = final -> v2 wins
            else -> compareSuffixNatural(suffix1, suffix2)
        }
    }

    /**
     * Natural-order comparison: tokenises into alternating text / digit runs
     * and compares token-by-token. Digit tokens compare numerically (so 10 > 2),
     * text tokens compare lexicographically (so beta < rc). When one side runs
     * out of tokens first, the shorter side sorts first ("alpha" < "alpha1").
     * On token-type mismatch at the same index, numeric tokens sort before
     * text tokens -- arbitrary but deterministic; we don't expect to hit this
     * case for any real Aura version string.
     */
    private fun compareSuffixNatural(s1: String, s2: String): Int {
        val tokens1 = tokenizeSuffix(s1)
        val tokens2 = tokenizeSuffix(s2)
        val limit = maxOf(tokens1.size, tokens2.size)
        for (i in 0 until limit) {
            val t1 = tokens1.getOrNull(i) ?: return -1
            val t2 = tokens2.getOrNull(i) ?: return 1
            val cmp = compareTokens(t1, t2)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private sealed class SuffixToken {
        data class Num(val value: Long) : SuffixToken()
        data class Text(val value: String) : SuffixToken()
    }

    private fun tokenizeSuffix(s: String): List<SuffixToken> {
        val tokens = mutableListOf<SuffixToken>()
        var i = 0
        while (i < s.length) {
            val start = i
            if (s[i].isDigit()) {
                while (i < s.length && s[i].isDigit()) i++
                tokens.add(SuffixToken.Num(s.substring(start, i).toLong()))
            } else {
                while (i < s.length && !s[i].isDigit()) i++
                tokens.add(SuffixToken.Text(s.substring(start, i)))
            }
        }
        return tokens
    }

    private fun compareTokens(a: SuffixToken, b: SuffixToken): Int = when (a) {
        is SuffixToken.Num if b is SuffixToken.Num -> a.value.compareTo(b.value)
        is SuffixToken.Text if b is SuffixToken.Text -> a.value.compareTo(b.value)
        is SuffixToken.Num if b is SuffixToken.Text -> -1
        is SuffixToken.Text if b is SuffixToken.Num -> 1
        else -> 0
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
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long
)
