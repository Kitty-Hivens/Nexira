package hivens.ui.identity

import hivens.core.api.HttpClientProvider
import hivens.core.time.Clock
import hivens.launcher.platform.PlatformPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkinManagerTest {

    private lateinit var home: Path

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("skin-manager-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun `isExpired flips once the injected clock passes the TTL`() {
        var now = 1_000_000L
        val paths = PlatformPaths("Linux", home, { null }, { null })
        // The http client is never touched by isExpired, so a lazily-erroring
        // provider is enough and keeps this off the network.
        val provider = HttpClientProvider { error("SkinManager http client not needed here") }
        val manager = SkinManager(provider, paths, Clock { now })

        val cached = File(home.toFile(), "front_test.png").apply {
            writeBytes(ByteArray(4))
            setLastModified(1_000_000L)
        }

        assertFalse(manager.isExpired(cached), "a just-written file is fresh")
        now += 31 * 60 * 1000L // TTL is 30 minutes
        assertTrue(manager.isExpired(cached), "past the TTL it must read as expired")
    }
}
