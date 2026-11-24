package hivens.source.modrinth

import hivens.core.api.HttpClientProvider
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.sources.EngineSources
import hivens.launcher.sources.PackArt
import hivens.launcher.sources.PackArtMemory
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.SourceWorks
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PackArtResolverTest {

    private val projectJson =
        """{"id":"AABBCCDD","slug":"p","title":"P","description":"","icon_url":"https://cdn/icon.png","gallery":[]}"""

    private val instance = PackInstance(
        id = "inst",
        packRef = PackReference(EngineSources.Modrinth, "AABBCCDD"),
        displayName = "P",
        instanceDirName = "P",
        createdAtEpoch = 1_700_000_000L,
        runtime = InstanceRuntime(),
    )

    /** Answers the project, the first request only after [first] has run. Every address asked lands in [asked]. */
    private fun resolver(
        calls: AtomicInteger,
        asked: MutableList<String> = mutableListOf(),
        memory: PackArtMemory = PackArtMemory.None,
        first: suspend () -> Unit,
    ): PackArtResolver {
        val engine = MockEngine { request ->
            asked += request.url.toString()
            if (calls.incrementAndGet() == 1) first()
            respond(ByteReadChannel(projectJson.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val provider = HttpClientProvider { HttpClient(engine) }
        val modrinth = ModrinthClient(provider, testTransferEngine(provider))
        // Modrinth's own work, archive installs and updates aside: the cover is all this asks of it.
        return PackArtResolver(SourceWorks.of(EngineSources.Modrinth to ModrinthPackWork(modrinth, mockk(), mockk())), memory)
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

    @Test
    fun `art captured at install is shown first and replaced by what the source answers`() = runBlocking {
        val installed = instance.copy(iconUrl = "https://cdn/old-icon.png", bannerUrl = "https://cdn/banner.png")
        val art = resolver(AtomicInteger()) {}

        assertEquals(PackArt("https://cdn/old-icon.png", "https://cdn/banner.png"), art.cached(installed))
        assertEquals(false, art.answered(installed), "captured art alone does not count as the source answering")

        val fresh = art.resolve(installed)
        assertEquals("https://cdn/icon.png", fresh.iconUrl, "the source's icon wins")
        assertEquals("https://cdn/banner.png", fresh.bannerUrl, "a field the source leaves empty keeps the captured one")
        assertEquals(true, art.answered(installed))
        assertEquals(fresh, art.cached(installed))
    }

    @Test
    fun `a failed lookup keeps the art captured at install`() = runBlocking {
        val installed = instance.copy(bannerUrl = "https://cdn/banner.png")
        val art = resolver(AtomicInteger()) { throw java.io.IOException("offline") }

        assertEquals(PackArt(null, "https://cdn/banner.png"), art.resolve(installed))
        assertEquals(false, art.answered(installed))
    }

    /**
     * Asked by its own reference, a detached pack named a Local pack, which no source
     * answers for: its card fell back to the pixel mosaic while its source still
     * published a cover.
     */
    @Test
    fun `a detached pack is drawn with the art of the source it was detached from`() = runBlocking {
        val asked = mutableListOf<String>()
        val art = resolver(AtomicInteger(), asked) {}
        val detached = instance.copy(packRef = instance.packRef.copy(origin = PackOrigin.Local), forkedFrom = instance.packRef)

        assertEquals(null, art.cached(detached), "nothing captured and nothing asked yet")
        assertEquals("https://cdn/icon.png", art.resolve(detached).iconUrl)
        assertEquals(listOf("https://api.modrinth.com/v2/project/AABBCCDD"), asked, "asked at its source")
        assertEquals(true, art.answered(detached))
    }

    /**
     * The source's answer is the source's, so a detached pack and a fresh install of
     * the same source pack share it, and neither asks twice. What each record
     * captured stays its own.
     */
    @Test
    fun `a detached pack and a fresh install of its source share the source's answer and keep their own captured art`() = runBlocking {
        val asked = mutableListOf<String>()
        val art = resolver(AtomicInteger(), asked) {}
        val fresh = instance.copy(id = "fresh")
        val detached = instance.copy(
            id = "detached",
            packRef = instance.packRef.copy(origin = PackOrigin.Local),
            forkedFrom = instance.packRef,
            bannerUrl = "https://cdn/detached-banner.png",
        )

        art.resolve(fresh)
        assertEquals(PackArt("https://cdn/icon.png", "https://cdn/detached-banner.png"), art.resolve(detached))
        assertEquals(PackArt("https://cdn/icon.png", null), art.cached(fresh), "the detached pack's banner is not the fresh one's")
        assertEquals(1, asked.size, "one source, asked once")
    }

    private class HeldMemory(var held: Map<String, PackArt> = emptyMap()) : PackArtMemory {
        val saves = AtomicInteger()
        override fun load(): Map<String, PackArt> = held
        override fun save(art: Map<String, PackArt>) {
            saves.incrementAndGet()
            held = art.toMap()
        }
    }

    @Test
    fun `what the source answered is drawn at once on the next run, and the source is asked again`() = runBlocking {
        val memory = HeldMemory()
        resolver(AtomicInteger(), memory = memory) {}.resolve(instance)

        val asked = mutableListOf<String>()
        val next = resolver(AtomicInteger(), asked, memory) {}
        assertEquals("https://cdn/icon.png", next.cached(instance)?.iconUrl, "known before anything is asked")
        assertEquals(false, next.answered(instance), "an answer from an earlier run is not this run's")
        assertEquals(emptyList(), asked)

        next.resolve(instance)
        assertEquals(1, asked.size)
        assertEquals(1, memory.saves.get(), "an answer that has not changed is not written again")
    }

    /** The address written down at install can be one the source has since moved the picture from. */
    @Test
    fun `an answer from an earlier run stands over the art captured at install`() = runBlocking {
        val memory = HeldMemory()
        val installed = instance.copy(iconUrl = "https://cdn/moved-away.png", bannerUrl = "https://cdn/banner.png")
        resolver(AtomicInteger(), memory = memory) {}.resolve(installed)

        val next = resolver(AtomicInteger(), memory = memory) { throw java.io.IOException("offline") }
        val expected = PackArt("https://cdn/icon.png", "https://cdn/banner.png")
        assertEquals(expected, next.cached(installed))
        assertEquals(expected, next.resolve(installed), "and a failed lookup keeps it")
        assertEquals(false, next.answered(installed))
    }

    @Test
    fun `a pack with no source to ask is not written down`() = runBlocking {
        val memory = HeldMemory()
        resolver(AtomicInteger(), memory = memory) {}.resolve(instance.copy(packRef = PackReference(PackOrigin.Local, "AABBCCDD")))
        assertEquals(0, memory.saves.get())
    }

    @Test
    fun `a local pack that came from nowhere asks no source`() = runBlocking {
        val asked = mutableListOf<String>()
        val art = resolver(AtomicInteger(), asked) {}
        val local = instance.copy(packRef = PackReference(PackOrigin.Local, "AABBCCDD"))

        assertEquals(PackArt.NONE, art.resolve(local))
        assertEquals(emptyList(), asked)
    }
}
