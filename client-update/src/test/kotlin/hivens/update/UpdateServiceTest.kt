package hivens.update

import hivens.test.testTransferEngine
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.ReleaseChannel
import hivens.core.data.SettingsData
import hivens.test.MockResponse
import hivens.test.buildMockClient
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class UpdateServiceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Stub settings service backed by a mutable [SettingsData] reference.
     * Tests preload whatever toggles they want via [fakeSettings] and pass
     * the result to [createService].
     */
    private class FakeSettingsService(initial: SettingsData) : ISettingsService {
        private var current: SettingsData = initial
        override fun getSettings(): SettingsData = current
        override fun saveSettings(settings: SettingsData) { current = settings }
    }

    private fun fakeSettings(
        experimentalFeaturesEnabled: Boolean = true,
        mandatoryUpdatesEnabled: Boolean = true,
        updateChannel: ReleaseChannel = ReleaseChannel.Release,  // Release so existing /releases/latest mocks work
        nightlyChannel: Boolean = false
    ): ISettingsService = FakeSettingsService(
        SettingsData(
            experimentalFeaturesEnabled = experimentalFeaturesEnabled,
            mandatoryUpdatesEnabled = mandatoryUpdatesEnabled,
            updateChannel = updateChannel,
            nightlyChannel = nightlyChannel
        )
    )

    private fun createService(
        vararg responses: MockResponse,
        settings: ISettingsService = fakeSettings(),
        currentVersion: String = "2.0.0"
    ): UpdateService {
        val tempDir = Files.createTempDirectory("update-test")
        tempDir.toFile().deleteOnExit()
        // Auto-prepend a default manifest response unless the test stages one
        // explicitly (e.g. to assert the no-manifest refusal path). Manifest
        // matching is more specific than "releases" so it MUST come first in
        // the queue, otherwise the broader rule would shadow it.
        val withManifest = if (responses.any { it.urlContains?.contains("release-manifest") == true }) {
            responses.toList()
        } else {
            listOf(MockResponse(urlContains = "release-manifest", body = releaseManifestJson())) + responses
        }
        // Auto-append a 404 for update-channel.json unless staged: checkForUpdate
        // probes the channel meta on every run, and an unmatched request falls
        // back to the queue's LAST entry -- usually a releases list that only
        // passes for null because UpdateChannelMeta fails to decode a JSON array.
        val withMeta = if (withManifest.any { it.urlContains?.contains("update-channel") == true }) {
            withManifest
        } else {
            withManifest + MockResponse(urlContains = "update-channel.json", body = "Not Found", status = HttpStatusCode.NotFound)
        }
        // One provider for both: the mock queue is stateful, so a second client
        // over its own queue would answer the engine's requests out of step with
        // the service's.
        val provider = buildMockClient(*withMeta.toTypedArray())
        return UpdateService(
            clientProvider = provider,
            transfers = testTransferEngine(provider),
            json = json,
            dataDirectory = tempDir,
            settingsService = settings,
            currentVersion = currentVersion
        )
    }

    private fun createService(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        settings: ISettingsService = fakeSettings(),
        currentVersion: String = "2.0.0"
    ): UpdateService {
        val tempDir = Files.createTempDirectory("update-test")
        tempDir.toFile().deleteOnExit()
        val provider = buildMockClient(
            // Manifest first -- substring "release-manifest" is more specific
            // than "releases/latest" and must win the match.
            MockResponse(urlContains = "release-manifest", body = releaseManifestJson()),
            MockResponse(urlContains = "releases/latest",  body = body,      status = status),
            MockResponse(urlContains = "releases",         body = "[$body]", status = status),
            // checkForUpdate always probes the channel meta; without this it
            // falls back to the "[$body]" entry above and relies on
            // UpdateChannelMeta failing to decode it.
            MockResponse(urlContains = "update-channel.json", body = "Not Found", status = HttpStatusCode.NotFound)
        )
        return UpdateService(
            clientProvider = provider,
            transfers = testTransferEngine(provider),
            json = json,
            dataDirectory = tempDir,
            settingsService = settings,
            currentVersion = currentVersion
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
            // Required since #186 -- without a manifest entry the cold path
            // refuses to construct a LauncherUpdate. Default fixture bundles
            // it so existing tests don't have to wire it explicitly.
            assetJson("release-manifest.json", "https://example.com/release-manifest.json", 1024),
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

    /**
     * Default release-manifest.json fixture. Hashes are placeholder zeros --
     * tests that exercise the integrity gate (`downloadUpdate`) supply real
     * hashes via [releaseManifestJson] with explicit asset list.
     *
     * platform/kind/size fields are required by [hivens.core.data.ReleaseAsset];
     * deserialization fails (silently -> manifest becomes null) without them.
     */
    private fun manifestAssetJson(name: String, sha256: String, platform: String, kind: String) =
        """{"name":"$name","platform":"$platform","kind":"$kind","sha256":"$sha256","size":1000}"""

    private fun releaseManifestJson(
        version: String = "2.0.0",
        highlights: String? = null,
        assets: List<String> = listOf(
            manifestAssetJson("AuraLauncher-2.0.0-Setup.exe",            "0".repeat(64), "windows", "installer"),
            manifestAssetJson("AuraLauncher-2.0.0-Windows-Portable.zip", "0".repeat(64), "windows", "portable"),
            manifestAssetJson("AuraLauncher-2.0.0-x86_64.AppImage",      "0".repeat(64), "linux",   "appimage"),
            manifestAssetJson("AuraLauncher-2.0.0.dmg",                  "0".repeat(64), "macos",   "dmg"),
        )
    ) = """
        {
            "schemaVersion": 1,
            "version": "$version",
            "highlights": ${if (highlights != null) "\"$highlights\"" else "null"},
            "assets": [${assets.joinToString(",")}]
        }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // extractWhatsChanged
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `extractWhatsChanged returns the section up to the next heading`() {
        val svc = createService("{}")
        val body = "## What's New\n\nfeatures\n\n## What's Changed\n\nthe details\n\n## Downloads\n\ntable"
        assertEquals("the details", svc.extractWhatsChanged(body))
    }

    @Test
    fun `extractWhatsChanged returns empty when there is no section`() {
        val svc = createService("{}")
        // A body with no "## What's Changed" (e.g. a manually-cut release) must NOT
        // fall back to the whole body / download table.
        val body = "> [!NOTE]\n> blah\n\n## Downloads\n\n| a | b |\n|---|---|"
        assertEquals("", svc.extractWhatsChanged(body))
    }

    @Test
    fun `extractWhatsChanged is empty for a null body`() {
        val svc = createService("{}")
        assertEquals("", svc.extractWhatsChanged(null))
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
    fun `compareVersions still compares numeric base across suffixes`() {
        val svc = createService("{}")
        // "2.0.0-beta" base = 2.0.0, greater than 1.3.0 regardless of suffix
        assertTrue(svc.compareVersions("2.0.0-beta", "1.3.0") > 0)
    }

    @Test
    fun `compareVersions ranks final above any prerelease at same base`() {
        val svc = createService("{}")
        // Final 1.3.0 > 1.3.0-rc3 -- without this the prerelease channel would
        // accidentally consider users on a final build "behind" the latest RC.
        assertTrue(svc.compareVersions("1.3.0", "1.3.0-rc3") > 0)
        assertTrue(svc.compareVersions("1.3.0-rc3", "1.3.0") < 0)
    }

    @Test
    fun `compareVersions orders prerelease tiers by the ladder, not lexically`() {
        val svc = createService("{}")
        // The ladder a tester walks, least to most stable: preview -> alpha ->
        // beta -> rc -> release. Within one base that is also the order they get
        // cut in, so it doubles as "which is newer".
        assertTrue(svc.compareVersions("1.3.0-alpha", "1.3.0-preview") > 0)
        assertTrue(svc.compareVersions("1.3.0-beta", "1.3.0-alpha") > 0)
        assertTrue(svc.compareVersions("1.3.0-rc1", "1.3.0-beta3") > 0)
        // Within one tier, natural order still decides.
        assertTrue(svc.compareVersions("1.3.0-rc2", "1.3.0-rc1") > 0)
    }

    @Test
    fun `a nightly outranks every prerelease tier at the same base`() {
        val svc = createService("{}")
        // The reason the ladder exists: lexically "nightly" < "preview", so a
        // preview install opting into nightlies would be offered nothing at all.
        assertTrue(svc.compareVersions("2.4.0-nightly1219", "2.4.0-preview") > 0)
        assertTrue(svc.compareVersions("2.4.0-nightly1219", "2.4.0-alpha2") > 0)
        assertTrue(svc.compareVersions("2.4.0-nightly1219", "2.4.0-beta5") > 0)
        assertTrue(svc.compareVersions("2.4.0-nightly1219", "2.4.0-rc1") > 0)
        // Nightlies still order numerically among themselves.
        assertTrue(svc.compareVersions("2.4.0-nightly1220", "2.4.0-nightly1219") > 0)
    }

    @Test
    fun `a shipped release outranks a nightly on the same base`() {
        val svc = createService("{}")
        // Deliberate: ranking nightly over a release would trap a nightly install
        // on nightlies forever, since "am I on a nightly" is derived from the
        // running version. The release must stay reachable.
        assertTrue(svc.compareVersions("2.4.0", "2.4.0-nightly1219") > 0)
    }

    @Test
    fun `compareVersions returns zero for identical prerelease tags`() {
        val svc = createService("{}")
        assertEquals(0, svc.compareVersions("1.3.0-rc3", "1.3.0-rc3"))
    }

    @Test
    fun `compareVersions orders double-digit RCs naturally`() {
        val svc = createService("{}")
        // The documented lex-compare bug: rc10 < rc2 under string ordering.
        // Natural-order tokenisation ranks them numerically so rc10 > rc2.
        assertTrue(svc.compareVersions("1.3.0-rc10", "1.3.0-rc2") > 0)
        assertTrue(svc.compareVersions("1.3.0-rc2",  "1.3.0-rc10") < 0)
    }

    @Test
    fun `compareVersions natural order ranks alpha less than alpha1`() {
        val svc = createService("{}")
        // Token count differs ("alpha" vs "alpha"+"1"); shorter sorts first.
        assertTrue(svc.compareVersions("1.3.0-alpha", "1.3.0-alpha1") < 0)
    }

    @Test
    fun `compareVersions natural order handles beta20 above beta3`() {
        val svc = createService("{}")
        assertTrue(svc.compareVersions("1.3.0-beta20", "1.3.0-beta3") > 0)
    }

    @Test
    fun `compareVersions handles versions with different segment counts`() {
        val svc = createService("{}")
        // 1.3 vs 1.3.0 -- missing segments treated as 0
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
            // Portable ZIP doesn't end with .exe -- should return null
            assertNull(svc.findAssetForCurrentOS(assets))
        }
    }

    @Test
    fun `findAssetForCurrentOS picks aarch64 DMG on Apple Silicon (dual-arch release)`() {
        val svc = createService("{}")
        val originalOs = System.getProperty("os.name")
        val originalArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.name", "Mac OS X")
            System.setProperty("os.arch", "aarch64")
            val assets = listOf(
                GitHubAsset("AuraLauncher-3.0.0-aarch64.dmg", "https://example.com/arm", 80_000_000),
                GitHubAsset("AuraLauncher-3.0.0-x86_64.dmg",  "https://example.com/intel", 80_000_000),
            )
            assertEquals(
                "AuraLauncher-3.0.0-aarch64.dmg",
                svc.findAssetForCurrentOS(assets)?.name,
                "Apple Silicon must NOT receive the x86_64 DMG (would fail with 'not supported on this Mac')",
            )
        } finally {
            System.setProperty("os.name", originalOs)
            System.setProperty("os.arch", originalArch)
        }
    }

    @Test
    fun `findAssetForCurrentOS picks x86_64 DMG on Intel Mac (dual-arch release)`() {
        val svc = createService("{}")
        val originalOs = System.getProperty("os.name")
        val originalArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.name", "Mac OS X")
            System.setProperty("os.arch", "x86_64")
            val assets = listOf(
                GitHubAsset("AuraLauncher-3.0.0-aarch64.dmg", "https://example.com/arm", 80_000_000),
                GitHubAsset("AuraLauncher-3.0.0-x86_64.dmg",  "https://example.com/intel", 80_000_000),
            )
            assertEquals(
                "AuraLauncher-3.0.0-x86_64.dmg",
                svc.findAssetForCurrentOS(assets)?.name,
                "Intel Mac must NOT receive the aarch64 DMG (Rosetta only goes x86_64 -> arm, not arm -> x86_64)",
            )
        } finally {
            System.setProperty("os.name", originalOs)
            System.setProperty("os.arch", originalArch)
        }
    }

    @Test
    fun `findAssetForCurrentOS falls back to single dmg for legacy pre-dual-arch releases`() {
        val svc = createService("{}")
        val originalOs = System.getProperty("os.name")
        val originalArch = System.getProperty("os.arch")
        try {
            System.setProperty("os.name", "Mac OS X")
            System.setProperty("os.arch", "x86_64")
            // Legacy release (≤ 2.2.12) shipped a single DMG with no arch suffix --
            // updater must still find it for backward compatibility, even if the
            // resulting binary may be wrong-arch on some hosts. Better than no
            // update at all.
            val assets = listOf(
                GitHubAsset("AuraLauncher-2.2.11.dmg", "https://example.com/legacy", 80_000_000),
            )
            assertEquals(
                "AuraLauncher-2.2.11.dmg",
                svc.findAssetForCurrentOS(assets)?.name,
            )
        } finally {
            System.setProperty("os.name", originalOs)
            System.setProperty("os.arch", originalArch)
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
    fun `verifyChecksum refuses empty checksum (no silent skip)`() {
        // Regression for #186: an empty expectedChecksum must be treated as
        // verification failure, not a free pass. The cold path in checkForUpdate
        // already won't construct an update without a manifest hash, but this
        // is the install-boundary defense-in-depth.
        val svc = createService("{}")
        val tempFile = Files.createTempFile("checksum-test", ".bin")
        tempFile.toFile().deleteOnExit()
        Files.writeString(tempFile, "anything")

        assertFalse(svc.verifyChecksum(tempFile, ""))
        assertFalse(svc.verifyChecksum(tempFile, "   "), "blank string must also fail closed")
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
        val update = svc.checkForUpdate()

        assertNotNull(update, "Should detect v99.0.0 as an update")
        assertEquals("v99.0.0", update.version)
        assertFalse(update.isCritical)
    }

    @Test
    fun `checkForUpdate returns null when current version is up to date`() = runTest {
        val svc = createService(githubReleaseJson(tagName = "v0.0.0"))
        val update = svc.checkForUpdate()

        assertNull(update, "Should not offer downgrade")
    }

    @Test
    fun `checkForUpdate returns null when versions are equal`() = runTest {
        // createService injects currentVersion = "2.0.0"; stage the same tag.
        val svc = createService(githubReleaseJson(tagName = "v2.0.0"))
        val update = svc.checkForUpdate()

        assertNull(update, "Same version should not trigger update")
    }

    @Test
    fun `source build is never auto-updated even by a critical mandatory release`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val criticalRelease = githubReleaseJson(tagName = "v999.0.0", name = "[CRITICAL] v999.0.0")
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = criticalRelease),
            MockResponse(urlContains = "releases",            body = "[$criticalRelease]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            currentVersion = "2.3.4-5-gabc1234-dirty",
        )

        assertNull(svc.checkForUpdate(), "A from-source build must not be auto-updated")
        assertNull(svc.checkForMandatoryUpdate(), "A from-source build must not be force-updated")
    }

    @Test
    fun `checkForUpdate detects CRITICAL flag in release name`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                name = "[CRITICAL] Aura Launcher v99.0.0"
            )
        )
        val update = svc.checkForUpdate()

        assertNotNull(update)
        assertTrue(update.isCritical, "Should flag as critical")
    }

    @Test
    fun `checkForUpdate detects the CRITICAL marker in a release body`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                body = "[CRITICAL] This update contains security patches."
            )
        )
        val update = svc.checkForUpdate()

        assertNotNull(update)
        assertTrue(update.isCritical)
    }

    /**
     * The marker is bracketed on purpose. Every release between the installed
     * version and the target is consulted now, so a changelog sentence using the
     * word in passing would raise an undismissable dialog for everyone.
     */
    @Test
    fun `the word critical in prose is not the marker`() = runTest {
        val svc = createService(
            githubReleaseJson(
                tagName = "v99.0.0",
                body = "Fixes a critical crash when opening the console."
            )
        )
        val update = svc.checkForUpdate()

        assertNotNull(update)
        assertFalse(update.isCritical, "prose is not a declaration")
    }

    // --- publication order is not version order -------------------------------

    /**
     * The live case: a beta cut after a nightly leads the page, and the ladder
     * ranks nightly above beta, so the head of the list compares OLDER than the
     * installed build. Taking the head answered "up to date" while a newer
     * nightly sat one row down.
     */
    @Test
    fun `a beta published after a nightly does not hide the newer nightly`() = runTest {
        val svc = createService(
            MockResponse(
                urlContains = "releases",
                body = "[" + listOf(
                    githubReleaseJson(tagName = "v2.4.0-beta4", name = "Nexira v2.4.0-beta4", prerelease = true),
                    githubReleaseJson(tagName = "v2.4.0-nightly1498", name = "Nexira v2.4.0-nightly1498", prerelease = true),
                    githubReleaseJson(tagName = "v2.4.0-nightly1475", name = "Nexira v2.4.0-nightly1475", prerelease = true),
                ).joinToString(",") + "]",
            ),
            currentVersion = "2.4.0-nightly1475",
        )

        val update = svc.checkForUpdate()

        assertNotNull(update, "a newer nightly exists further down the page")
        assertEquals("v2.4.0-nightly1498", update.version)
    }

    /** The ladder still holds: a beta is not an upgrade for a nightly install. */
    @Test
    fun `a beta alone is not offered to a nightly install`() = runTest {
        val svc = createService(
            MockResponse(
                urlContains = "releases",
                body = "[" + listOf(
                    githubReleaseJson(tagName = "v2.4.0-beta4", name = "Nexira v2.4.0-beta4", prerelease = true),
                    githubReleaseJson(tagName = "v2.4.0-nightly1475", name = "Nexira v2.4.0-nightly1475", prerelease = true),
                ).joinToString(",") + "]",
            ),
            currentVersion = "2.4.0-nightly1475",
        )

        assertNull(svc.checkForUpdate(), "nightly outranks beta -- offering it would be a downgrade")
    }

    // --- criticality is a property of the range, not of the newest release ----

    /** v2.0.0 installed, v2.1.0 critical, v2.2.0 ordinary and on top. */
    private fun rangeService(middleName: String, topName: String) = createService(
        MockResponse(urlContains = "releases/latest", body = githubReleaseJson(tagName = "v2.2.0", name = topName)),
        MockResponse(
            urlContains = "releases",
            body = "[" + listOf(
                githubReleaseJson(tagName = "v2.2.0", name = topName),
                githubReleaseJson(tagName = "v2.1.0", name = middleName),
                githubReleaseJson(tagName = "v2.0.0", name = "Nexira v2.0.0"),
            ).joinToString(",") + "]",
        ),
        currentVersion = "2.0.0",
    )

    /**
     * The defect this closes. Consulting only the newest release meant the
     * mechanism was weakest exactly when the marker is used sparingly, which is
     * how it is meant to be used.
     */
    @Test
    fun `a critical release between installed and latest makes the update critical`() = runTest {
        val update = rangeService(
            middleName = "Nexira v2.1.0 [CRITICAL]",
            topName = "Nexira v2.2.0",
        ).checkForUpdate()

        assertNotNull(update)
        assertEquals("v2.2.0", update.version, "the offer is still the newest build")
        assertTrue(update.isCritical, "the fix sits between the two, and the offer carries it")
    }

    @Test
    fun `an ordinary range stays ordinary`() = runTest {
        val update = rangeService(
            middleName = "Nexira v2.1.0",
            topName = "Nexira v2.2.0",
        ).checkForUpdate()

        assertNotNull(update)
        assertFalse(update.isCritical, "nothing in the range declared itself")
    }

    @Test
    fun `a release older than the installed one does not reach forward`() = runTest {
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = githubReleaseJson(tagName = "v2.2.0", name = "Nexira v2.2.0")),
            MockResponse(
                urlContains = "releases",
                body = "[" + listOf(
                    githubReleaseJson(tagName = "v2.2.0", name = "Nexira v2.2.0"),
                    githubReleaseJson(tagName = "v1.9.0", name = "Nexira v1.9.0 [CRITICAL]"),
                ).joinToString(",") + "]",
            ),
            currentVersion = "2.0.0",
        )

        val update = svc.checkForUpdate()

        assertNotNull(update)
        assertFalse(update.isCritical, "already past it -- the installed build contains that fix")
    }

    @Test
    fun `checkForUpdate returns null on HTTP error`() = runTest {
        val svc = createService(body = "Rate limited", status = HttpStatusCode.Forbidden)
        val update = svc.checkForUpdate()
        assertNull(update)
    }

    @Test
    fun `checkForUpdate returns null on malformed JSON`() = runTest {
        val svc = createService(body = "not json {{{")
        val update = svc.checkForUpdate()
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
        val update = svc.checkForUpdate()
        assertNull(update, "No matching installer asset should return null")
    }

    @Test
    fun `checkForUpdate refuses to construct update when manifest is absent`() = runTest {
        // Regression for #186: with no release-manifest.json asset shipped in
        // the release, the launcher must NOT auto-install -- there is no
        // verifiable hash to gate against. Older releases that pre-date the
        // manifest convention require manual reinstallation.
        val release = githubReleaseJson(
            tagName = "v99.0.0",
            assets = listOf(
                assetJson("AuraLauncher-99.0.0-Setup.exe", "https://example.com/AuraLauncher-99.0.0-Setup.exe", 50_000_000),
                assetJson("AuraLauncher-99.0.0-Windows-Portable.zip", "https://example.com/AuraLauncher-99.0.0-Windows-Portable.zip", 60_000_000),
                assetJson("AuraLauncher-99.0.0-x86_64.AppImage", "https://example.com/AuraLauncher-99.0.0-x86_64.AppImage", 70_000_000),
                assetJson("AuraLauncher-99.0.0.dmg", "https://example.com/AuraLauncher-99.0.0.dmg", 80_000_000),
            )
        )
        // Stage manifest URL explicitly returning 404 -- the test's intent
        // is "no manifest available," not "manifest accidentally garbled."
        val svc = createService(
            MockResponse(urlContains = "release-manifest", body = "Not Found", status = HttpStatusCode.NotFound),
            MockResponse(urlContains = "releases/latest",  body = release),
            MockResponse(urlContains = "releases",         body = "[$release]"),
        )
        val update = svc.checkForUpdate()
        assertNull(update, "Auto-update must refuse when manifest is missing -- no silent skip")
    }

    @Test
    fun `checkForUpdate refuses when manifest lacks entry for selected asset`() = runTest {
        // Subtler variant of #186: the manifest is present but doesn't list
        // the asset we'd install (e.g. a partial manifest published by mistake,
        // or a new platform asset added without updating the manifest). Same
        // outcome -- refuse, force manual installation.
        val release = githubReleaseJson(tagName = "v99.0.0")
        val emptyManifest = releaseManifestJson(
            version = "99.0.0",
            assets = listOf(manifestAssetJson("SomeOtherFile.zip", "1".repeat(64), "windows", "installer")),
        )
        val svc = createService(
            MockResponse(urlContains = "release-manifest", body = emptyManifest),
            MockResponse(urlContains = "releases/latest",  body = release),
            MockResponse(urlContains = "releases",         body = "[$release]"),
        )
        val update = svc.checkForUpdate()
        assertNull(update, "Manifest without the selected asset's hash must refuse update")
    }

    @Test
    fun `checkForUpdate uses fallback changelog when body is null`() = runTest {
        val svc = createService(githubReleaseJson(tagName = "v99.0.0", body = null))
        val update = svc.checkForUpdate()

        assertNotNull(update)
        assertEquals("No changelog available", update.changelog)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // cleanupOldUpdates
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `cleanupOldUpdates removes exe zip dmg and AppImage files`() {
        val tempDir = Files.createTempDirectory("cleanup-test")
        tempDir.toFile().deleteOnExit()
        val updatesDir = tempDir.resolve("updates")
        Files.createDirectories(updatesDir)

        // Create test files
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0-Setup.exe"), "fake")
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0-Windows-Portable.zip"), "fake")
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0.dmg"), "fake")
        Files.writeString(updatesDir.resolve("AuraLauncher-1.0.0-x86_64.AppImage"), "fake")
        Files.writeString(updatesDir.resolve("notes.txt"), "keep me")   // should survive

        val svc = UpdateService(
            clientProvider = buildMockClient(body = "{}"),
            transfers = testTransferEngine(buildMockClient(body = "{}")),
            json = json,
            dataDirectory = tempDir,
            settingsService = fakeSettings()
        )
        svc.cleanupOldUpdates()

        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0-Setup.exe")))
        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0-Windows-Portable.zip")))
        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0.dmg")))
        assertFalse(Files.exists(updatesDir.resolve("AuraLauncher-1.0.0-x86_64.AppImage")))
        assertTrue(Files.exists(updatesDir.resolve("notes.txt")), "notes.txt should survive")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Update channel selection (prerelease toggle)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `prerelease channel ON picks first non-draft from releases list`() = runTest {
        // Two releases in the list: a draft (must be skipped) and a published
        // RC (must be picked). The /releases/latest endpoint isn't even hit
        // when prereleases are on -- wire it to fail to prove that.
        val draft = githubReleaseJson(tagName = "v99.9.9", body = "draft")
            .replace("\"prerelease\": false", "\"prerelease\": false,\n            \"draft\": true")
        val rc = githubReleaseJson(tagName = "v99.0.0-rc1", body = "rc body")
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "releases",        body = "[$draft,$rc]"),
            settings = fakeSettings(updateChannel = ReleaseChannel.Beta)
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertEquals("v99.0.0-rc1", update.version)
    }

    @Test
    fun `prerelease check builds the changelog from the already-fetched list`() = runTest {
        // The mock queue consumes a matched entry only when it sits at the
        // head: the first /releases hit eats the good list, so a second hit
        // (the old double-fetch) lands on BOOM and produces an empty
        // changelog. One check must cost one list call -- the unauthenticated
        // API allows 60 req/hour.
        val rc = githubReleaseJson(tagName = "v99.0.0-rc1")
        val svc = createService(
            MockResponse(urlContains = "releases",            body = "[$rc]"),
            MockResponse(urlContains = "releases",            body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "update-channel.json", body = "Not Found", status = HttpStatusCode.NotFound),
            MockResponse(urlContains = "release-manifest",    body = releaseManifestJson()),
            settings = fakeSettings(updateChannel = ReleaseChannel.Beta),
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertEquals("v99.0.0-rc1", update.version)
        assertTrue(
            "Bug fixes" in update.changelog,
            "changelog must reuse the single /releases fetch, got: '${update.changelog}'",
        )
    }

    @Test
    fun `prerelease channel OFF still uses releases-latest endpoint`() = runTest {
        // /releases (the list endpoint) is broken -- if the code accidentally
        // hits it when prereleases are off, the test will fail.
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = githubReleaseJson(tagName = "v99.0.0")),
            MockResponse(urlContains = "releases",        body = "BOOM",            status = HttpStatusCode.InternalServerError),
            settings = fakeSettings(updateChannel = ReleaseChannel.Release)
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertEquals("v99.0.0", update.version)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Nightly channel (separate axis from the pre-release toggle)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `a nightly build self-bootstraps onto nightlies with the pre-release channel off`() = runTest {
        // Running a nightly build IS the opt-in: classify(currentVersion)==Nightly
        // must flip BOTH includePrereleases (so the /releases list is fetched, not
        // /releases/latest which drops prereleases) AND the candidate filter. With
        // updateChannel=Release and only that flip missing, a nightly build would
        // silently stop tracking nightlies. /releases/latest is wired to fail to
        // prove the list path is taken.
        val nightly = githubReleaseJson(tagName = "v2.5.0-nightly42")
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "releases",        body = "[$nightly]"),
            settings = fakeSettings(updateChannel = ReleaseChannel.Release),
            currentVersion = "2.5.0-nightly10",
        )
        val update = svc.checkForUpdate()
        assertNotNull(update, "a nightly build must keep tracking newer nightlies")
        assertEquals("v2.5.0-nightly42", update.version)
    }

    @Test
    fun `nightlyChannel flag opts a stable build into nightlies`() = runTest {
        // The config bit alone (no nightly build, channel still Release) pulls the
        // newest nightly.
        val nightly = githubReleaseJson(tagName = "v2.5.0-nightly42")
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "releases",        body = "[$nightly]"),
            settings = fakeSettings(nightlyChannel = true),
        )
        val update = svc.checkForUpdate()
        assertNotNull(update, "the nightlyChannel flag must fetch the list and accept a nightly")
        assertEquals("v2.5.0-nightly42", update.version)
    }

    @Test
    fun `pre-release channel without nightly opt-in skips a nightly for the older preview`() = runTest {
        // A plain Beta user must NOT be dragged onto a nightly: the newest list
        // entry is a nightly, but the candidate filter skips it and lands on the
        // RC below. Without the filter the Beta user would silently ride nightlies.
        val nightly = githubReleaseJson(tagName = "v2.5.0-nightly42")
        val rc = githubReleaseJson(tagName = "v2.4.0-rc1")
        val svc = createService(
            MockResponse(urlContains = "releases/latest", body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "releases",        body = "[$nightly,$rc]"),
            settings = fakeSettings(updateChannel = ReleaseChannel.Beta),
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertEquals("v2.4.0-rc1", update.version, "Beta skips the nightly and takes the newest non-nightly")
    }

    @Test
    fun `experimental master OFF forces both children OFF`() = runTest {
        // Master off -> prereleases off (so /releases/latest is the path, not
        // /releases) AND mandatory off (so even a high floor doesn't block).
        val release = githubReleaseJson(tagName = "v99.0.0")
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = release),
            MockResponse(urlContains = "releases",            body = "BOOM", status = HttpStatusCode.InternalServerError),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings(experimentalFeaturesEnabled = false)
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertFalse(update.isMandatory, "Master off must force mandatory off even when floor > current")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Mandatory floor (out-of-band update-channel.json)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `mandatory floor above current marks update as mandatory`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream protocol broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()  // mandatory ON, prereleases OFF (default)
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertTrue(update.isMandatory, "Floor 999.0.0 > installed should mandate the update")
        assertEquals("upstream protocol broke", update.mandatoryReason)
    }

    @Test
    fun `mandatory floor at-or-below current does not mandate`() = runTest {
        // Floor "0.0.0" -- strictly below any real installed version.
        val channelMeta = """{"mandatory_min_version":"0.0.0","reason":null}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertFalse(update.isMandatory)
        assertNull(update.mandatoryReason)
    }

    @Test
    fun `missing channel meta degrades to non-mandatory`() = runTest {
        // 404 on the meta endpoint must not break the update flow.
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = "Not Found", status = HttpStatusCode.NotFound),
            settings = fakeSettings()
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertFalse(update.isMandatory, "Missing channel meta must not block startup")
    }

    @Test
    fun `mandatoryUpdatesEnabled OFF disables enforcement even with high floor`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings(mandatoryUpdatesEnabled = false)
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertFalse(update.isMandatory)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // checkForMandatoryUpdate (5-minute meta poll)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `mandatory poll returns update when floor exceeds installed`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()
        )
        val result = svc.checkForMandatoryUpdate()
        assertNotNull(result)
        assertTrue(result.isMandatory)
        assertEquals("upstream broke", result.mandatoryReason)
    }

    @Test
    fun `mandatory poll returns null when floor at or below installed`() = runTest {
        val channelMeta = """{"mandatory_min_version":"0.0.0","reason":null}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()
        )
        assertNull(svc.checkForMandatoryUpdate())
    }

    @Test
    fun `mandatory poll skips when mandatory updates disabled`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings(mandatoryUpdatesEnabled = false)
        )
        assertNull(svc.checkForMandatoryUpdate())
    }

    @Test
    fun `mandatory poll respects meta cooldown on second immediate call`() = runTest {
        val channelMeta = """{"mandatory_min_version":"999.0.0","reason":"upstream broke"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()
        )
        val first = svc.checkForMandatoryUpdate()
        assertNotNull(first, "first call should fire")
        val second = svc.checkForMandatoryUpdate()
        assertNull(second, "second immediate call should be skipped by cooldown (.last_meta_check)")
    }

    @Test
    fun `shouldCheckMeta returns true on fresh install`() {
        val svc = createService("{}")
        assertTrue(svc.shouldCheckMeta())
    }

    @Test
    fun `mandatory floor with v-prefix is normalized`() = runTest {
        // Real-world authors will write "v2.2.8" out of habit -- strip the v.
        val channelMeta = """{"mandatory_min_version":"v999.0.0","reason":"normalized"}"""
        val svc = createService(
            MockResponse(urlContains = "releases/latest",     body = githubReleaseJson(tagName = "v999.0.0")),
            MockResponse(urlContains = "releases",            body = "[${githubReleaseJson(tagName = "v999.0.0")}]"),
            MockResponse(urlContains = "update-channel.json", body = channelMeta),
            settings = fakeSettings()
        )
        val update = svc.checkForUpdate()
        assertNotNull(update)
        assertTrue(update.isMandatory)
    }

    @Test
    fun githubReleaseWithNullNameIsDecodable() {
        // GitHub's REST API returns "name": null for any release published
        // without a title. Production decodes releases with the DI Json, which
        // sets coerceInputValues=true -- replicated here so this proves the DTO
        // shape, not a test-only Json divergence. coerceInputValues only rescues
        // a null when the field has a default; GitHubRelease.name is a non-null
        // String with no default, so the decode throws and checkForUpdate's
        // catch turns it into "no update" for every user until a titled release
        // is cut.
        val prodJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
        val body = """
            {
              "tag_name": "v2.3.5",
              "name": null,
              "body": "notes",
              "assets": [],
              "prerelease": false,
              "draft": false,
              "published_at": "2026-06-08T00:00:00Z"
            }
        """.trimIndent()

        val release = prodJson.decodeFromString<GitHubRelease>(body)
        assertEquals("v2.3.5", release.tagName)
    }
}
