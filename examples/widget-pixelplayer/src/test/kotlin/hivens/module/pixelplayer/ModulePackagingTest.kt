package hivens.module.pixelplayer

import hivens.widget.api.WidgetApi
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Checks that this example is still shaped like a loadable module.
 *
 * Deliberately reads the jar rather than loading from it: inside this module's
 * own test JVM its classes are already on the classpath, so a class-loading test
 * would resolve them through the parent and prove nothing about the jar. That
 * the loader does load a jar is covered where it can be shown honestly, in
 * :widget-loader, against a module built the same way this one is.
 */
class ModulePackagingTest {

    private val jar = Path.of(requireNotNull(System.getProperty("nexira.test.moduleJar")) {
        "this module's jar was not passed to the test JVM"
    })

    @Test
    fun `the manifest declares the API version this build of the kernel speaks`() {
        // When this fails the example has gone stale and the loader is right to
        // refuse it. The fix is the manifest block in this module's build script.
        JarFile(jar.toFile()).use { file ->
            val attributes = file.manifest.mainAttributes
            assertEquals(WidgetApi.VERSION.toString(), attributes.getValue(WidgetApi.MANIFEST_VERSION))
            assertEquals("pixelplayer", attributes.getValue(WidgetApi.MANIFEST_ID))
            assertEquals("Pixel Player", attributes.getValue(WidgetApi.MANIFEST_NAME))
        }
    }

    @Test
    fun `the jar carries the service entry the loader discovers it by`() {
        // Emitted by the KSP processor, not written by hand. A module that lost
        // it still compiles, still passes every other test here, and is simply
        // never found.
        JarFile(jar.toFile()).use { file ->
            val entry = file.getEntry("META-INF/services/hivens.widget.api.WidgetRegistry")
            assertNotNull(entry, "no service entry -- the processor stopped emitting it, or resources were dropped")
            val named = file.getInputStream(entry).bufferedReader().readText().trim()
            assertEquals("hivens.module.pixelplayer.generated.PixelPlayerWidgetRegistryProvider", named)
        }
    }
}
