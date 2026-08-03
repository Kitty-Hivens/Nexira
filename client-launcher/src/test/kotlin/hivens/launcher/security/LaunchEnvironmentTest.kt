package hivens.launcher.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchEnvironmentTest {

    private fun env(vararg pairs: Pair<String, String>) = mutableMapOf(*pairs)

    @Test
    fun `a bound launch loses the alternative argument lists`() {
        val e = env(
            "JAVA_TOOL_OPTIONS" to "-javaagent:/tmp/x.jar",
            "_JAVA_OPTIONS" to "-javaagent:/tmp/y.jar",
            "JDK_JAVA_OPTIONS" to "@/tmp/args",
            "PATH" to "/usr/bin",
        )
        val sealed = LaunchEnvironment.seal(e, sealLoaderVars = true)

        assertEquals(listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"), sealed)
        assertEquals(mapOf("PATH" to "/usr/bin"), e)
    }

    @Test
    fun `a bound launch loses the loader hooks`() {
        val e = env("LD_PRELOAD" to "/tmp/cheat.so", "LD_AUDIT" to "/tmp/a.so", "HOME" to "/home/u")

        LaunchEnvironment.seal(e, sealLoaderVars = true)

        assertEquals(mapOf("HOME" to "/home/u"), e)
    }

    /**
     * The reason the flag exists: LD_PRELOAD is how MangoHud and gamemode attach,
     * and a pack with no server binding receives no token, so there is nothing for
     * an inherited hook to ride beside.
     */
    @Test
    fun `an unbound launch keeps the loader hooks but never the java options`() {
        val e = env(
            "LD_PRELOAD" to "libmangohud.so",
            "LD_LIBRARY_PATH" to "/usr/lib32",
            "JAVA_TOOL_OPTIONS" to "-javaagent:/tmp/x.jar",
        )

        val sealed = LaunchEnvironment.seal(e, sealLoaderVars = false)

        assertEquals(listOf("JAVA_TOOL_OPTIONS"), sealed)
        assertEquals("libmangohud.so", e["LD_PRELOAD"])
        assertEquals("/usr/lib32", e["LD_LIBRARY_PATH"])
    }

    @Test
    fun `only names that were present are reported`() {
        val e = env("PATH" to "/usr/bin")

        assertTrue(LaunchEnvironment.seal(e, sealLoaderVars = true).isEmpty())
        assertEquals(mapOf("PATH" to "/usr/bin"), e)
    }

    /**
     * CLASSPATH is honoured by the `java` launcher when no `-cp` is given, and the
     * builder's own `-cp` lands after the user's args -- so this one is sealed for
     * the same reason as the rest, not as an afterthought.
     */
    @Test
    fun `classpath is treated as an argument list, not an ordinary variable`() {
        assertTrue("CLASSPATH" in LaunchEnvironment.JVM_OPTION_VARS)
        assertFalse("CLASSPATH" in LaunchEnvironment.LOADER_VARS)
    }
}
