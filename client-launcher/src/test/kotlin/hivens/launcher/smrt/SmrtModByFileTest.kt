package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The registry's page for a jar, read by the jar's hash. */
class SmrtModByFileTest {

    private fun mirror(status: HttpStatusCode, body: String): Pair<SmrtPackClient, MutableList<String>> {
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    asked += req.url.encodedPath
                    respond(ByteReadChannel(body.toByteArray()), status, headersOf("Content-Type", "application/json"))
                }
            }
        }
        return SmrtPackClient(HttpClientProvider { client }, "https://mirror.test") to asked
    }

    // Trimmed from what the mirror answers for a CurseForge-only jar.
    private val advancedMachines = """
        {"mod_id":2,"name":"Advanced Machines","slug":null,"author":"immibis, Chocohead","modid":"advanced_machines",
         "loaders":["forge"],"mc_versions":["1.12.2"],
         "releases":[{"release_id":2,"version_number":"61.0.0","channel":"unknown","source":"harvested","files":[
           {"version":"61.0.0","targets":["forge"],"mc_versions":["1.12.2"],"sha1":"a7fb","size_bytes":190539,
            "filename":"AdvancedMachines.jar","source":"harvested","cached":true,"modrinth_project_id":null,
            "curseforge":{"fingerprint":1737788037,"project_id":251205,"file_id":2667002,
              "display_name":"Advanced Machines-61.0.1","file_name":"Advanced Machines-61.0.1.jar","distributable":true}}]}],
         "edges":[{"dir":"out","other_mod_id":13,"other_name":"IndustrialCraft 2","kind":"requires","source":"inferred"}],
         "used_by":[{"pack_id":"Industrial-Cleanroom","pack_version":"0.1.37","version":"61.0.0","filename":"AdvancedMachines.jar"}]}
    """.trimIndent()

    @Test
    fun `a jar the mirror knows comes back as its mod`() = runBlocking {
        val (client, asked) = mirror(HttpStatusCode.OK, advancedMachines)
        val mod = client.modByFile("a7fb")!!
        assertEquals(listOf("/v1/mods/sha1:a7fb"), asked)
        assertEquals("Advanced Machines", mod.name)
        assertEquals("immibis, Chocohead", mod.author)
        assertEquals(251205L, mod.releases.single().files.single().curseforge?.projectId)
        assertEquals("IndustrialCraft 2", mod.edges.single().otherName)
        assertEquals("Industrial-Cleanroom", mod.usedBy.single().packId)
    }

    @Test
    fun `a jar it has never seen is no answer rather than a failure`() = runBlocking {
        val (client, _) = mirror(HttpStatusCode.NotFound, "{}")
        assertNull(client.modByFile("ffff"))
    }
}
