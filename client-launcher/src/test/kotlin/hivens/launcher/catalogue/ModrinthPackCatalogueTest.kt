package hivens.launcher.catalogue

import hivens.launcher.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.data.PackOrigin
import hivens.launcher.modrinth.ModrinthClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModrinthPackCatalogueTest {

    private val searchJson =
        """{"hits":[{"project_id":"AABBCCDD","slug":"cobblemon","title":"Cobblemon",""" +
            """"description":"Catch and battle","icon_url":"https://cdn/icon.png","categories":["adventure","mobs"]}],""" +
            """"total_hits":1}"""
    private val projectJson =
        """{"id":"AABBCCDD","slug":"cobblemon","title":"Cobblemon","description":"Catch and battle",""" +
            """"body":"# Cobblemon\n\nA long body.","categories":["adventure"],"icon_url":"https://cdn/icon.png",""" +
            """"gallery":[{"url":"https://cdn/g1.png","featured":false},{"url":"https://cdn/hero.png","featured":true}]}"""
    private val versionsJson =
        """[{"id":"ver1","project_id":"AABBCCDD","name":"Cobblemon 1.5","version_number":"1.5.0",""" +
            """"version_type":"release","game_versions":["1.21.1"],"loaders":["fabric"],"date_published":"2024-01-01",""" +
            """"files":[{"hashes":{"sha1":"deadbeef"},"url":"https://cdn/cobblemon-1.5.mrpack",""" +
            """"filename":"cobblemon-1.5.mrpack","primary":true,"size":42}]}]"""

    private fun catalogue(): ModrinthPackCatalogue {
        val engine = MockEngine { req ->
            val body = when (req.url.encodedPath) {
                "/v2/search" -> searchJson
                "/v2/project/AABBCCDD" -> projectJson
                "/v2/project/AABBCCDD/version" -> versionsJson
                else -> null
            }
            if (body == null) {
                respond("not found ${req.url}", HttpStatusCode.NotFound)
            } else {
                respond(ByteReadChannel(body.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val provider = HttpClientProvider { HttpClient(engine) }
        return ModrinthPackCatalogue(ModrinthClient(provider, testTransferEngine(provider)))
    }

    @Test
    fun `search maps modpack hits to catalogue packs`() = runTest {
        val packs = catalogue().search("cobblemon")
        assertEquals(1, packs.size)
        val p = packs.first()
        assertEquals(PackOrigin.Modrinth, p.origin)
        assertEquals("AABBCCDD", p.id)
        assertEquals("Cobblemon", p.title)
        assertEquals("Catch and battle", p.tagline)
        assertEquals(listOf("adventure", "mobs"), p.tags)
    }

    @Test
    fun `details carries body markdown, a featured-first banner and versions`() = runTest {
        val d = catalogue().details("AABBCCDD")
        assertEquals("Cobblemon", d.title)
        assertTrue(d.bodyMarkdown!!.contains("long body"))
        assertEquals("https://cdn/hero.png", d.bannerUrl, "featured gallery image is the hero")
        assertEquals(listOf("https://cdn/g1.png", "https://cdn/hero.png"), d.galleryUrls)
        assertEquals(1, d.versions.size)
    }

    @Test
    fun `versions expose the primary mrpack as the download url`() = runTest {
        val v = catalogue().versions("AABBCCDD").single()
        assertEquals("ver1", v.id)
        assertEquals("1.5.0", v.versionNumber)
        assertEquals(listOf("fabric"), v.loaders)
        assertEquals("https://cdn/cobblemon-1.5.mrpack", v.downloadUrl)
    }
}
