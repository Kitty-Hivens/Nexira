package hivens.launcher.component

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class GameLaunchTest {

    private fun launch(vararg jvm: String) = GameLaunch(jvm.toList(), "Main", listOf("--username", "Steve"))

    /** What the JVM's own library path is split on, which differs between hosts. */
    private val sep = File.pathSeparator

    @Test
    fun `the command line is the java, the options, the class and the game arguments in that order`() {
        assertEquals(
            listOf("/jdk/bin/java", "-Xmx2G", "-cp", "a.jar", "Main", "--username", "Steve"),
            launch("-Xmx2G", "-cp", "a.jar").commandLine("/jdk/bin/java"),
        )
    }

    @Test
    fun `a classpath given as a separate word becomes the property the VM reads`() {
        assertEquals(listOf("-Djava.class.path=a.jar:b.jar"), launch("-cp", "a.jar:b.jar").embeddedVmOptions())
        assertEquals(listOf("-Djava.class.path=x"), launch("-classpath", "x").embeddedVmOptions())
        assertEquals(listOf("-Djava.class.path=x"), launch("--class-path", "x").embeddedVmOptions())
    }

    @Test
    fun `the module options are joined with an equals sign`() {
        assertEquals(
            listOf("--module-path=/m", "--module-path=/n", "--add-modules=jdk.incubator.vector", "--add-opens=java.base/java.lang=ALL-UNNAMED"),
            launch("-p", "/m", "--module-path", "/n", "--add-modules", "jdk.incubator.vector", "--add-opens", "java.base/java.lang=ALL-UNNAMED")
                .embeddedVmOptions(),
        )
    }

    @Test
    fun `options already in the VM's spelling and ordinary flags pass through untouched`() {
        val args = arrayOf("--add-modules=jdk.incubator.vector", "-Dfml.ignoreInvalidMinecraftCertificates=true", "-XX:+DisableAttachMechanism", "-Xms512M", "-javaagent:/a.jar=host=h")
        assertEquals(args.toList(), launch(*args).embeddedVmOptions())
    }

    @Test
    fun `a flag with no value after it is left as it is rather than reading past the end`() {
        assertEquals(listOf("-Xmx1G", "-cp"), launch("-Xmx1G", "-cp").embeddedVmOptions())
        assertEquals(listOf("--add-modules"), launch("--add-modules").embeddedVmOptions())
    }

    @Test
    fun `a value that looks like an option is still the value of the flag before it`() {
        assertEquals(listOf("-Djava.class.path=-weird.jar"), launch("-cp", "-weird.jar").embeddedVmOptions())
    }

    @Test
    fun `the library directories come from both paths in the order they were given`() {
        assertEquals(
            listOf("/lwjgl", "/a", "/b"),
            launch("-Xmx1G", "-Dorg.lwjgl.librarypath=/lwjgl", "-cp", "a.jar", "-Djava.library.path=/a$sep/b").libraryDirectories(),
        )
    }

    @Test
    fun `an empty entry is no library directory and a launch without a path has none`() {
        assertEquals(listOf("/a"), launch("-Djava.library.path=$sep/a$sep").libraryDirectories())
        assertEquals(emptyList(), launch("-Xmx1G", "-Dorg.lwjgl.opengl.libname=libMobileGL.so").libraryDirectories())
    }
}
