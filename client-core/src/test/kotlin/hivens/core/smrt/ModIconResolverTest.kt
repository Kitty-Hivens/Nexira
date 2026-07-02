package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModIconResolverTest {

    @Test
    fun `direct iconUrl wins -- no API call`() = runBlocking {
        var apiCalls = 0
        val r = ModIconResolver { apiCalls++; "should-not-be-called" }
        val url = r.resolve(modWith(
            display = SmrtDisplay(iconUrl = "https://cdn/direct.png"),
            source = SmrtSource.Modrinth(projectId = "abc", versionId = "v"),
        ))
        assertEquals("https://cdn/direct.png", url)
        assertEquals(0, apiCalls, "direct URL should short-circuit the API")
    }

    @Test
    fun `Modrinth source -- API lookup returns the icon`() = runBlocking {
        val r = ModIconResolver { pid -> if (pid == "EsAfCjCV") "https://cdn.modrinth/EsAfCjCV/icon.png" else null }
        val url = r.resolve(modWith(
            display = null,
            source = SmrtSource.Modrinth(projectId = "EsAfCjCV", versionId = "v"),
        ))
        assertEquals("https://cdn.modrinth/EsAfCjCV/icon.png", url)
    }

    @Test
    fun `Modrinth source -- second call for same project_id hits cache`() = runBlocking {
        var apiCalls = 0
        val r = ModIconResolver { _ -> apiCalls++; "https://cdn/icon.png" }
        val mod = modWith(display = null, source = SmrtSource.Modrinth(projectId = "abc", versionId = "v"))

        r.resolve(mod)
        r.resolve(mod)
        r.resolve(mod)

        assertEquals(1, apiCalls, "second + third call should hit the cache")
    }

    @Test
    fun `Modrinth source -- API failure returns null and is cached`() = runBlocking {
        var apiCalls = 0
        val r = ModIconResolver { _ -> apiCalls++; throw RuntimeException("boom") }
        val mod = modWith(display = null, source = SmrtSource.Modrinth(projectId = "abc", versionId = "v"))

        assertNull(r.resolve(mod))
        assertNull(r.resolve(mod))
        assertEquals(1, apiCalls, "failed lookup should still be cached so we don't keep hammering the API")
    }

    @Test
    fun `Modrinth source -- API returns null is cached too`() = runBlocking {
        var apiCalls = 0
        val r = ModIconResolver { _ -> apiCalls++; null }
        val mod = modWith(display = null, source = SmrtSource.Modrinth(projectId = "noicon", versionId = "v"))

        assertNull(r.resolve(mod))
        assertNull(r.resolve(mod))
        assertEquals(1, apiCalls)
    }

    @Test
    fun `SmrtCache source without iconUrl returns null with no API call`() = runBlocking {
        var apiCalls = 0
        val r = ModIconResolver { _ -> apiCalls++; "x" }
        val url = r.resolve(modWith(
            display = null,
            source = SmrtSource.SmrtCache(url = "https://smrt/cache/x.jar"),
        ))
        assertNull(url)
        assertEquals(0, apiCalls)
    }

    @Test
    fun `SmrtStatic source without iconUrl returns null`() = runBlocking {
        val r = ModIconResolver { _ -> "ignored" }
        val url = r.resolve(modWith(
            display = null,
            source = SmrtSource.SmrtStatic(url = "https://smrt/static/x.zip"),
        ))
        assertNull(url)
    }

    @Test
    fun `blank iconUrl falls through to Modrinth lookup`() = runBlocking {
        val r = ModIconResolver { pid -> "https://cdn.modrinth/$pid/icon.png" }
        val url = r.resolve(modWith(
            display = SmrtDisplay(iconUrl = "   "),
            source = SmrtSource.Modrinth(projectId = "abc", versionId = "v"),
        ))
        assertEquals("https://cdn.modrinth/abc/icon.png", url, "blank URL should not be treated as resolved")
    }

    @Test
    fun `resolveByFile -- hashes the file and resolves via Modrinth, cached per hash`() = runBlocking {
        var calls = 0
        val r = ModIconResolver(
            resolveProjectIcon = { "unused-by-this-path" },
            resolveIconByHash  = { sha1 -> calls++; "https://cdn.modrinth/$sha1/icon.png" },
        )
        val file = Files.createTempFile("mod", ".jar").also { Files.write(it, byteArrayOf(1, 2, 3, 4)) }
        try {
            val first = r.resolveByFile(file)
            val second = r.resolveByFile(file)
            assertNotNull(first)
            assertEquals(first, second)
            assertEquals(1, calls, "same file hashes to the same key -- one lookup")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `resolveByFile -- unknown hash returns null and is cached`() = runBlocking {
        var calls = 0
        val r = ModIconResolver(resolveProjectIcon = { "unused" }, resolveIconByHash = { calls++; null })
        val file = Files.createTempFile("mod", ".jar").also { Files.write(it, byteArrayOf(9)) }
        try {
            assertNull(r.resolveByFile(file))
            assertNull(r.resolveByFile(file))
            assertEquals(1, calls, "an unknown (404) hash is cached so we don't keep hitting Modrinth")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `resolveByFile -- unreadable file returns null without a lookup`() = runBlocking {
        var calls = 0
        val r = ModIconResolver(resolveProjectIcon = { "unused" }, resolveIconByHash = { calls++; "x" })
        assertNull(r.resolveByFile(Path.of("/no/such/file/here.jar")))
        assertEquals(0, calls, "a file that can't be hashed should not trigger a network lookup")
    }

    private fun modWith(display: SmrtDisplay?, source: SmrtSource) = SmrtModEntry(
        filename = "test.jar",
        sha1 = "0".repeat(40),
        sizeBytes = 1L,
        source = source,
        display = display,
    )
}
