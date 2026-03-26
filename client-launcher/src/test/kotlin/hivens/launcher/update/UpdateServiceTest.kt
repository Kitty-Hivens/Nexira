package hivens.launcher.update

import hivens.test.MockResponse
import hivens.test.buildMockClient
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class UpdateServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun createService(
        vararg responses: MockResponse
    ): UpdateService {
        val tempDir = Files.createTempDirectory("update-test")
        tempDir.toFile().deleteOnExit()
        return UpdateService(
            clientProvider = buildMockClient(*responses),
            json = json,
            dataDirectory = tempDir
        )
    }

    private fun createService(body: String, status: HttpStatusCode = HttpStatusCode.OK): UpdateService {
        val tempDir = Files.createTempDirectory("update-test")
        tempDir.toFile().deleteOnExit()
        return UpdateService(
            clientProvider = buildMockClient(
                MockResponse(urlContains = "releases/latest", body = body,      status = status),
                MockResponse(urlContains = "releases",        body = "[$body]", status = status)
            ),
            json = json,
            dataDirectory = tempDir
        )
    }

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private fun githubReleaseJson(
        tagName: String = "v2.0.0",
        name: String = "Aura Launcher v2.0.0",
        body: String? = "## What's Changed\nBug fixes",
        prerelease: Boolean = false,
        assets: List<String> = listOf(
            assetJson("AuraLauncher-2.0.0-Setup.exe", "https://example.com/AuraLauncher-2.0.0-Setup.exe", 50_000_000),
            assetJson("AuraLauncher-2.0.0-Windows-Portable.zip", "https://example.com/AuraLauncher-2.0.0-Windows-Portable.zip", 60_000_000),
            assetJson("AuraLauncher-2.0.0-x86_64.AppImage", "https://example.com/AuraLauncher-2.0.0-x86_64.AppImage", 70_000_000),
            assetJson("AuraLauncher-2.0.0.dmg", "https://example.com/AuraLauncher-2.0.0.dmg", 80_000_000),
            assetJson("SHA256SUMS.txt", "https://example.com/SHA256SUMS.txt", 512)
        )
    ) = """
        {
            "tag_name": "$tagName",
            "name": "$name",
            "body": ${if (body != null) "\"${body.replace("\"", "\\\"").replace("\n", "\\n")}\"" else "null"},
            "assets": [${assets.joinToString(",")}],
            "prerelease": $prerelease,
            "published_at": "2026-03-10T12:00:00Z"
        }
    """.trimIndent()

    private fun assetJson(name: String, url: String, size: Long) = """
        {"name":"$name","browser_download_url":"$url","size":$size}
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════════
    // compareVersions
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `compareVersions returns positive when v1 is newer`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("2.0.0", "1.3.0") > 0)
    }

    @Test
    fun `compareVersions returns negative when v1 is older`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("1.2.0", "1.3.0") < 0)
    }

    @Test
    fun `compareVersions returns zero for equal versions`() {
        val svc = createService("{}")
        assertEquals(0, svc.compareVersions("1.3.0", "1.3.0"))
    }

    @Test
    fun `compareVersions handles patch level differences`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("1.3.1", "1.3.0") > 0)
        assertTrue(svc.compareVersions("1.3.0", "1.3.1") < 0)
    }

    @Test
    fun `compareVersions handles major version jumps`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("3.0.0", "1.99.99") > 0)
    }

    @Test
    fun `compareVersions strips prerelease suffix before comparing`() {
        val svc = createService("{}")
        // "2.0.0-beta" should compare as 2.0.0 which is greater than 1.3.0
        assertTrue(svc.compareVersions("2.0.0-beta", "1.3.0") > 0)
    }

    @Test
    fun `compareVersions treats prerelease versions with same base as equal`() {
        val svc = createService("{}")
        // Both strip to 1.3.0
        assertEquals(0, svc.compareVersions("1.3.0-rc1", "1.3.0-beta2"))
    }

    @Test
    fun `compareVersions handles versions with different segment counts`() {
        val svc = createService("{}")
        // 1.3 vs 1.3.0 — missing segments treated as 0
        assertEquals(0, svc.compareVersions("1.3", "1.3.0"))
        assertTrue(svc.compareVersions("1.3.1", "1.3") > 0)
    }

    @Test
    fun `compareVersions handles single-segment versions`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("2", "1") > 0)
        assertEquals(0, svc.compareVersions("1", "1"))
    }

    @Test
    fun `compareVersions handles non-numeric segments gracefully`() {
        val svc = createService("{}")
        // "abc" parsed as 0
        assertEquals(0, svc.compareVersions("abc", "0"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // findAssetForCurrentOS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `findAssetForCurrentOS picks correct asset for current platform`() {
        val svc = createService("{}")
        val assets = listOf(
            GitHubAsset("AuraLauncher-2.0.0-Setup.exe", "https://example.com/setup.exe", 50_000_000),
            GitHubAsset("AuraLauncher-2.0.0-Windows-Portable.zip", "https://example.com/portable.zip", 60_000_000),
            GitHubAsset("AuraLauncher-2.0.0-x86_64.AppImage", "https://example.com/appimage", 70_000_000),
            GitHubAsset("AuraLauncher-2.0.0.dmg", "https://example.com/dmg", 80_000_000),
            GitHubAsset("SHA256SUMS.txt", "https://example.com/checksums", 512),
        )

        val result = svc.findAssetForCurrentOS(assets)
        assertNotNull(result, "Should find an asset for current OS")

        val os = System.getProperty("os.name").lowercase()
        when {
            os.contains("windows") -> {
                assertTrue(result.name.endsWith(".exe"), "Windows should select .exe, got: ${result.name}")
                assertTrue(result.name.contains("Setup"), "Windows should select Setup installer")
                // Must NOT pick the Portable ZIP
                assertFalse(result.name.contains("Portable"))
            }
            os.contains("mac") -> assertTrue(result.name.endsWith(".dmg"), "macOS should select .dmg")
            os.contains("linux") -> assertTrue(result.name.endsWith(".AppImage"), "Linux should select .AppImage")
        }
    }

    @Test
    fun `findAssetForCurrentOS returns null when no matching asset exists`() {
        val svc = createService("{}")
        val assets = listOf(
            GitHubAsset("README.md", "https://example.com/readme", 1024),
            GitHubAsset("SHA256SUMS.txt", "https://example.com/checksums", 512),
        )
        assertNull(svc.findAssetForCurrentOS(assets))
    }

    @Test
    fun `findAssetForCurrentOS returns null for empty asset list`() {
        val svc = createService("{}")
        assertNull(svc.findAssetForCurrentOS(emptyList()))
    }

    @Test
    fun `findAssetForCurrentOS does not select portable ZIP on Windows`() {
        val svc = createService("{}")
        // Only a portable ZIP, no Setup.exe
        val assets = listOf(
            GitHubAsset("AuraLauncher-2.0.0-Windows-Portable.zip", "https://example.com/portable.zip", 60_000_000),
        )
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("windows")) {
            // Portable ZIP doesn't end with .exe — should return null
            assertNull(svc.findAssetForCurrentOS(assets))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // extractChecksum
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `extractChecksum finds hash from markdown table format`() {
        val svc = createService("{}")
        val body = """
            ## SHA256 Checksums
            
            | File | SHA256 |
            |---|---|
            | `AuraLauncher-2.0.0-Setup.exe` | `abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890` |
            | `AuraLauncher-2.0.0.dmg` | `1111111111111111111111111111111111111111111111111111111111111111` |
        """.trimIndent()

        assertEquals(
            "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            svc.extractChecksum(body, "AuraLauncher-2.0.0-Setup.exe")
        )
    }

    @Test
    fun `extractChecksum finds hash from plain text format`() {
        val svc = createService("{}")
        val body = "SHA256: AuraLauncher-2.0.0-Setup.exe - abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"

        assertEquals(
            "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
            svc.extractChecksum(body, "AuraLauncher-2.0.0-Setup.exe")
        )
    }

    @Test
    fun `extractChecksum returns empty string when file not mentioned`() {
        val svc = createService("{}")
        val body = "No checksums here, just changelog text."
        assertEquals("", svc.extractChecksum(body, "AuraLauncher-2.0.0-Setup.exe"))
    }

    @Test
    fun `extractChecksum returns empty string for null body`() {
        val svc = createService("{}")
        assertEquals("", svc.extractChecksum(null, "AuraLauncher-2.0.0-Setup.exe"))
    }

    @Test
    fun `extractChecksum finds correct file among multiple entries`() {
        val svc = createService("{}")
        val body = """
            | File | SHA256 |
            |---|---|
            | `AuraLauncher-2.0.0-Setup.exe` | `aaaa000000000000000000000000000000000000000000000000000000000000` |
            | `AuraLauncher-2.0.0.dmg` | `bbbb000000000000000000000000000000000000000000000000000000000000` |
            | `AuraLauncher-2.0.0-x86_64.AppImage` | `cccc000000000000000000000000000000000000000000000000000000000000` |
        """.trimIndent()

        assertEquals(
            "bbbb000000000000000000000000000000000000000000000000000000000000",
            svc.extractChecksum(body, "AuraLauncher-2.0.0.dmg")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // verifyChecksum
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `verifyChecksum returns true for correct hash`() {
        val svc = createService("{}")
        val tempFile = Files.createTempFile("checksum-test", ".bin")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "hello world")

        // SHA-256 of "hello world"
        val expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"
        assertTrue(svc.verifyChecksum(tempFile, expected))
    }

    @Test
    fun `verifyChecksum returns false for wrong hash`() {
        val svc = createService("{}")
        val tempFile = Files.createTempFile("checksum-test", ".bin")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "hello world")

        assertFalse(svc.verifyChecksum(tempFile, "0000000000000000000000000000000000000000000000000000000000000000"))
    }

    @Test
    fun `verifyChecksum returns true when expected is empty (skip mode)`() {
        val svc = createService("{}")
        val tempFile = Files.createTempFile("checksum-test", ".bin")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "anything")

        assertTrue(svc.verifyChecksum(tempFile, ""))
    }

    @Test
    fun `verifyChecksum is case-insensitive`() {
        val svc = createService("{}")
        val tempFile = Files.createTempFile("checksum-test", ".bin")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "hello world")

        val upper = "B94D27B9934D3E08A52E52D7DA7DABFAC484EFE37A5380EE9088F7ACE2EFCDE9"
        assertTrue(svc.verifyChecksum(tempFile, upper))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // checkForUpdate (integration with mock HTTP)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `checkForUpdate returns LauncherUpdate when newer version exists`() = runTest {
        val svc = createService(githubReleaseJson(tagName = "v99.0.0"))
        val update = svc.checkForUpdate(force = true)

        assertNotNull(update, "Should detect v99.0.0 as an update")
        assertEquals("v99.0.0", update.version)
        assertFalse(update.isCritical)
    }

    @Test
    fun `checkForUpdate returns null when current version is up to date`() = runTest {
        val svc = createService(githubReleaseJson(tagName = "v0.0.0"))
        val update = svc.checkForUpdate(force = true)

        assertNull(update, "Should not offer downgrade")
    }

    @Test
    fun `checkForUpdate returns null when versions are equal`() = runTest {
        // Use the actual client version from config
        val currentVersion = hivens.config.AppConfig.CLIENT_VERSION.removePrefix("v")
        val svc = createService(githubReleaseJson(tagName = "v$currentVersion"))
        val update = svc.checkForUpdate(force = true)

        assertNull(update, "Same version should not trigger update")
    }

    @Test
    fun `checkForUpdate detects CRITICAL flag in release name`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                name = "[CRITICAL] Aura Launcher v99.0.0"
            )
        )
        val update = svc.checkForUpdate(force = true)

        assertNotNull(update)
        assertTrue(update.isCritical, "Should flag as critical")
    }

    @Test
    fun `checkForUpdate detects CRITICAL flag in release body`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                body = "This update contains CRITICAL security patches."
            )
        )
        val update = svc.checkForUpdate(force = true)

        assertNotNull(update)
        assertTrue(update.isCritical)
    }

    @Test
    fun `checkForUpdate returns null on HTTP error`() = runTest {
        val svc = createService(body = "Rate limited", status = HttpStatusCode.Forbidden)
        val update = svc.checkForUpdate(force = true)
        assertNull(update)
    }

    @Test
    fun `checkForUpdate returns null on malformed JSON`() = runTest {
        val svc = createService(body = "not json {{{")
        val update = svc.checkForUpdate(force = true)
        assertNull(update)
    }

    @Test
    fun `checkForUpdate returns null when no assets match current OS`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                assets = listOf(
                    assetJson("SHA256SUMS.txt", "https://example.com/checksums", 512)
                )
            )
        )
        val update = svc.checkForUpdate(force = true)
        assertNull(update, "No matching installer asset should return null")
    }

    @Test
    fun `checkForUpdate uses fallback changelog when body is null`() = runTest {
        val svc = createService(githubReleaseJson(tagName = "v99.0.0", body = null))
        val update = svc.checkForUpdate(force = true)

        assertNotNull(update)
        assertEquals("No changelog available", update.changelog)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // shouldCheck (cooldown logic)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `shouldCheck returns true on fresh install (no last-check file)`() {
        val svc = createService("{}")
        assertTrue(svc.shouldCheck())
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cleanupOldUpdates
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `cleanupOldUpdates removes exe dmg and AppImage files`() {
        val tempDir = Files.createTempDirectory("cleanup-test")
        tempDir.toFile().deleteOnExit()
        val updatesDir = tempDir.resolve("updates")
        Files.createDirectories(updatesDir)

        // Create test files
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0-Setup.exe"), "fake")
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0.dmg"), "fake")
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0-x86_64.AppImage"), "fake")
        Files.writeString(updatesDir.resolve(".last_check"), "123456")   // should survive
        Files.writeString(updatesDir.resolve("notes.txt"), "keep me")   // should survive

        val svc = UpdateService(
            clientProvider = buildMockClient(body = "{}"),
            json = json,
            dataDirectory = tempDir
        )
        svc.cleanupOldUpdates()

        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0-Setup.exe")))
        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0.dmg")))
        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0-x86_64.AppImage")))
        assertTrue(Files.exists(updatesDir.resolve(".last_check")), ".last_check should survive")
        assertTrue(Files.exists(updatesDir.resolve("notes.txt")), "notes.txt should survive")
    }
}
