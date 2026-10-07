package hivens.launcher.runtime.loader

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeLegacyResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sha1(b: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun installerZip(versionJson: String, bundledPath: String, bundledBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("version.json")); zos.write(versionJson.toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("maven/$bundledPath")); zos.write(bundledBytes); zos.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `resolve parses version json, downloads url libs, bundles the forge jar`() = runTest {
        val forgePath = "net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar"
        val forgeBytes = "FORGE-UNIVERSAL".toByteArray()
        val versionJson = """
            {
              "id": "1.12.2-forge-14.23.5.2860",
              "mainClass": "net.minecraft.launchwrapper.Launch",
              "inheritsFrom": "1.12.2",
              "minecraftArguments": "--username x --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker --versionType Forge",
              "libraries": [
                {"name":"net.minecraftforge:forge:1.12.2-14.23.5.2860","downloads":{"artifact":{"path":"$forgePath","url":"","sha1":"${sha1(forgeBytes)}","size":${forgeBytes.size}}}},
                {"name":"org.ow2.asm:asm-debug-all:5.2","downloads":{"artifact":{"path":"org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar","url":"https://maven.example/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar","sha1":"abc","size":100}}}
              ]
            }
        """.trimIndent()
        val zipBytes = installerZip(versionJson, forgePath, forgeBytes)

        val engine = MockEngine { req ->
            when (req.url.toString()) {
                META_URL -> respond("<metadata><versioning><versions><version>1.12.2-14.23.5.2860</version></versions></versioning></metadata>", HttpStatusCode.OK)
                INSTALLER_URL -> respond(ByteReadChannel(zipBytes), HttpStatusCode.OK)
                else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
        val resolver = ForgeLegacyResolver(HttpClientProvider { HttpClient(engine) }, testTransferEngine(HttpClientProvider { HttpClient(engine) }), json, forgeMavenBase = MAVEN_BASE)

        val profile = resolver.resolve("1.12.2", "14.23.5.2860")

        assertEquals("net.minecraft.launchwrapper.Launch", profile.mainClass)
        assertEquals(
            listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"),
            profile.gameArgs,
        )
        assertEquals(2, profile.libraries.size)

        val forge = profile.libraries.first { it.coord.groupArtifact == "net.minecraftforge:forge" }
        assertNull(forge.url, "forge universal has no url -- must be bundled")
        assertEquals("FORGE-UNIVERSAL", forge.bundled?.decodeToString())

        val asm = profile.libraries.first { it.coord.groupArtifact == "org.ow2.asm:asm-debug-all" }
        assertEquals("https://maven.example/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar", asm.url)
        assertNull(asm.bundled, "url-based lib must not carry bundled bytes")
    }

    /**
     * Every launch of a 1.12.2 pack read the maven's metadata and downloaded the
     * installer into a temp file it then deleted, so none could start offline.
     */
    @Test
    fun `a named build relaunches from the kept installer with the network gone`() = runTest {
        val forgePath = "net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar"
        val forgeBytes = "FORGE-UNIVERSAL".toByteArray()
        val versionJson = """{"mainClass":"net.minecraft.launchwrapper.Launch","libraries":[
            {"name":"net.minecraftforge:forge:1.12.2-14.23.5.2860","downloads":{"artifact":{"path":"$forgePath","url":"","sha1":"${sha1(forgeBytes)}","size":${forgeBytes.size}}}}]}"""
        val zipBytes = installerZip(versionJson, forgePath, forgeBytes)
        var online = true
        val engine = MockEngine { req ->
            when {
                !online -> respond("offline", HttpStatusCode.ServiceUnavailable)
                req.url.toString() == META_URL -> respond("<metadata><versioning><versions><version>1.12.2-14.23.5.2860</version></versions></versioning></metadata>", HttpStatusCode.OK)
                req.url.toString() == INSTALLER_URL -> respond(ByteReadChannel(zipBytes), HttpStatusCode.OK)
                else -> respond("missing", HttpStatusCode.NotFound)
            }
        }
        val cacheDir = Files.createTempDirectory("forge-cache")
        val provider = HttpClientProvider { HttpClient(engine) }
        val resolver = ForgeLegacyResolver(provider, testTransferEngine(provider), json, forgeMavenBase = MAVEN_BASE, cacheDir = cacheDir)
        resolver.resolve("1.12.2", "14.23.5.2860")

        online = false
        val again = resolver.resolve("1.12.2", "14.23.5.2860")

        assertEquals("net.minecraft.launchwrapper.Launch", again.mainClass)
        assertEquals("FORGE-UNIVERSAL", again.libraries.single().bundled?.decodeToString())
        cacheDir.toFile().deleteRecursively()
    }

    @Test
    fun `extractTweakArgs falls back to the canonical FML tweaker when absent`() {
        val resolver = ForgeLegacyResolver(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }, testTransferEngine(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }), json)
        assertEquals(ForgeLegacyResolver.DEFAULT_TWEAK_ARGS, resolver.extractTweakArgs(null))
        assertEquals(ForgeLegacyResolver.DEFAULT_TWEAK_ARGS, resolver.extractTweakArgs("--username x --gameDir y"))
    }

    @Test
    fun `resolveForgeBuild keeps a published build`() = runTest {
        val r = ForgeLegacyResolver(HttpClientProvider { HttpClient(metadataEngine()) }, testTransferEngine(HttpClientProvider { HttpClient(metadataEngine()) }), json, forgeMavenBase = MAVEN_BASE)
        assertEquals("14.23.5.2864", r.resolveForgeBuild("1.12.2", "14.23.5.2864"))
    }

    /** Written the way other launchers write it, with the Minecraft version in front. */
    @Test
    fun `resolveForgeBuild keeps a published build named with its Minecraft prefix`() = runTest {
        val r = ForgeLegacyResolver(HttpClientProvider { HttpClient(metadataEngine()) }, testTransferEngine(HttpClientProvider { HttpClient(metadataEngine()) }), json, forgeMavenBase = MAVEN_BASE)
        assertEquals("14.23.5.2860", r.resolveForgeBuild("1.12.2", "1.12.2-14.23.5.2860"))
    }

    @Test
    fun `resolveForgeBuild maps a non-published SC-custom build to latest official`() = runTest {
        val r = ForgeLegacyResolver(HttpClientProvider { HttpClient(metadataEngine()) }, testTransferEngine(HttpClientProvider { HttpClient(metadataEngine()) }), json, forgeMavenBase = MAVEN_BASE)
        assertEquals("14.23.5.2864", r.resolveForgeBuild("1.12.2", "14.23.5.2922"))
    }

    @Test
    fun `compareForgeBuilds orders by numeric tuple`() {
        val r = ForgeLegacyResolver(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }, testTransferEngine(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }), json)
        assertTrue(r.compareForgeBuilds("14.23.5.2860", "14.23.5.2864") < 0)
        assertTrue(r.compareForgeBuilds("14.23.5.2922", "14.23.5.2864") > 0)
    }

    private fun metadataEngine() = MockEngine { req ->
        if (req.url.toString() == META_URL) {
            respond(
                """<metadata><versioning><versions>
                   <version>1.12.2-14.23.5.2860</version>
                   <version>1.12.2-14.23.5.2864</version>
                   <version>1.16.5-36.2.39</version>
                   </versions></versioning></metadata>""",
                HttpStatusCode.OK,
            )
        } else {
            respond("not found", HttpStatusCode.NotFound)
        }
    }

    private companion object {
        const val MAVEN_BASE = "https://forge.example"
        const val META_URL = "https://forge.example/net/minecraftforge/forge/maven-metadata.xml"
        const val INSTALLER_URL =
            "https://forge.example/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-installer.jar"
    }
}
