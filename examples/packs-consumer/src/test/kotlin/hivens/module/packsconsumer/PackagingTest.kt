package hivens.module.packsconsumer

import hivens.packs.api.PacksApi
import hivens.widget.api.KeyContribution
import hivens.widget.loader.WidgetModuleLoader
import hivens.widget.model.ApiVersion
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This module's own jar, as the launcher opens it: loaded where the launcher carries a
 * packs API that serves the one it was built against, and refused where it does not.
 */
class PackagingTest {

    private val dir: Path = Files.createTempDirectory("packs-consumer-jar")

    init {
        val jar = Path.of(requireNotNull(System.getProperty("nexira.test.moduleJar")) { "the module jar was not passed to the test JVM" })
        Files.copy(jar, dir.resolve("packs-consumer.jar"), StandardCopyOption.REPLACE_EXISTING)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    // The packs API's classes are shown to the jar as the launcher shows them, from the
    // package its own object lives in.
    private val packsApiPackages = mapOf(PacksApi.ID to setOf(PacksApi::class.java.name.substringBeforeLast('.')))

    private fun scan(offered: Map<String, ApiVersion>) =
        WidgetModuleLoader(dir, parent = WithoutThisModule(javaClass.classLoader), offered = offered, apiPackages = packsApiPackages).scan()

    @Test
    fun `it loads where the launcher carries the packs API it was built against or a newer minor`() {
        val same = scan(mapOf(PacksApi.ID to PacksApi.VERSION))
        try {
            assertEquals(emptyList(), same.rejected)
            assertTrue(same.loaded.single().contributions.single() is KeyContribution)
        } finally {
            same.loaded.forEach { it.loader.close() }
        }
        val newerMinor = scan(mapOf(PacksApi.ID to ApiVersion(PacksApi.VERSION.major, PacksApi.VERSION.minor + 1)))
        try {
            assertEquals(1, newerMinor.loaded.size)
        } finally {
            newerMinor.loaded.forEach { it.loader.close() }
        }
    }

    @Test
    fun `it is refused where the launcher carries another major of the packs API, or none`() {
        val otherMajor = scan(mapOf(PacksApi.ID to ApiVersion(PacksApi.VERSION.major + 1, 0)))
        assertEquals(emptyList(), otherMajor.loaded)
        assertTrue(PacksApi.ID in otherMajor.rejected.single().reason, otherMajor.rejected.single().reason)
        val none = scan(emptyMap())
        assertEquals(emptyList(), none.loaded)
    }
}

/**
 * The test classpath carries this module's own classes, and a loader that delegates to its parent
 * first would find them there and never open the jar. This parent answers for everything but the
 * module, which is what the launcher's loader looks like to a jar that is not on its classpath.
 */
private class WithoutThisModule(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name.startsWith("hivens.module.packsconsumer.")) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }
}
