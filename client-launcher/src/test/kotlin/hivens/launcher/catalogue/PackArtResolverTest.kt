package hivens.launcher.catalogue

import hivens.core.api.HttpClientProvider
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.SmrtPackClient
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class PackArtResolverTest {

    private val projectJson =
        """{"id":"AABBCCDD","slug":"p","title":"P","description":"","icon_url":"https://cdn/icon.png","gallery":[]}"""

    private val instance = PackInstance(
        id = "inst",
        packRef = PackReference(PackOrigin.Modrinth, "AABBCCDD"),
        displayName = "P",
        instanceDirName = "P",
        createdAtEpoch = 1_700_000_000L,
        runtime = InstanceRuntime(),
    )

    /** Answers the project, the first request only after [first] has run. */
    private fun resolver(calls: AtomicInteger, first: suspend () -> Unit): PackArtResolver {
        val engine = MockEngine {
            if (calls.incrementAndGet() == 1) first()
            respond(ByteReadChannel(projectJson.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val provider = HttpClientProvider { HttpClient(engine) }
        return PackArtResolver(ModrinthClient(provider, testTransferEngine(provider)), SmrtPackClient(provider))
    }

    @Test
    fun `a failed lookup is not remembered as no art`() = runBlocking {
        val calls = AtomicInteger()
        val art = resolver(calls) { throw java.io.IOException("offline") }

        assertEquals(PackArt.NONE, art.resolve(instance), "the failure itself still reads as no art")
        assertEquals("https://cdn/icon.png", art.resolve(instance).iconUrl, "and the next look asks again")
    }

    @Test
    fun `a cancelled lookup is not remembered as no art`() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val art = resolver(calls) { entered.complete(Unit); awaitCancellation() }

        val scrolledAway = launch(Dispatchers.IO) { art.resolve(instance) }
        entered.await()
        scrolledAway.cancelAndJoin()

        assertEquals("https://cdn/icon.png", art.resolve(instance).iconUrl)
    }
}
