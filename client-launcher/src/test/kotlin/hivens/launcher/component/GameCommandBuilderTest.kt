package hivens.launcher.component

import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class GameCommandBuilderTest {

    private val builder = GameCommandBuilder()

    // ─── Fixtures ─────────────────────────────────────────────────────────────

    private fun session(
        playerName: String = "TestPlayer",
        uuid: String = "abcdef1234567890abcdef1234567890",
        accessToken: String = "token_abc123"
    ) = SessionData(
        playerName = playerName,
        uuid = uuid,
        accessToken = accessToken,
        uid = "42"
    )

    private fun server(
        version: String = "1.7.10",
        name: String = "TestServer",
        ip: String = "play.example.com",
        port: Int = 25565
    ) = ServerProfile(
        name = name,
        version = version,
        ip = ip,
        port = port,
        assetDir = name
    )

    private fun profile(
        memoryMb: Int = 4096,
        javaPath: String? = null,
        jvmArgs: String? = null
    ) = InstanceProfile(
        serverId = "TestServer",
        memoryMb = memoryMb,
        javaPath = javaPath,
        jvmArgs = jvmArgs
    )

    private val clientRoot: Path = Path.of("/tmp/test-client")
    private val sep = File.pathSeparator

    /** Minimal classpath that includes NeoForge boot modules for 1.21.1 tests */
    private val neoForgeClasspath = listOf(
        "/tmp/test-client/libraries/cpw/mods/securejarhandler/1.0/securejarhandler-1.0.jar",
        "/tmp/test-client/libraries/cpw/mods/bootstraplauncher/1.0/bootstraplauncher-1.0.jar",
        "/tmp/test-client/libraries/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar",
        "/tmp/test-client/libraries/net/minecraftforge/JarJarFileSystems/0.3/JarJarFileSystems-0.3.jar",
        "/tmp/test-client/libraries/com/google/guava/guava-31.1.jar",
        "/tmp/test-client/libraries/net/minecraft/client-1.21.1.jar"
    ).joinToString(sep)

    /** Simple classpath for legacy versions */
    private val legacyClasspath = listOf(
        "/tmp/test-client/libraries/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
        "/tmp/test-client/libraries/com/google/guava/guava-17.0.jar",
        "/tmp/test-client/libraries/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar"
    ).joinToString(sep)

    private fun build(
        version: String,
        classpath: String = if (version.startsWith("1.21")) neoForgeClasspath else legacyClasspath,
        memoryMb: Int = 4096,
        jvmArgs: String? = null
    ): List<String> = builder.build(
        javaExec = "/usr/bin/java",
        memoryMB = memoryMb,
        clientRoot = clientRoot,
        serverProfile = server(version = version),
        session = session(),
        userProfile = profile(memoryMb = memoryMb, jvmArgs = jvmArgs),
        classpath = classpath
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // getNativesDir
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `getNativesDir returns correct path for 1_7_10`() {
        assertEquals("bin/natives-1.7.10", builder.getNativesDir("1.7.10"))
    }

    @Test
    fun `getNativesDir returns correct path for 1_12_2`() {
        assertEquals("bin/natives-1.12.2", builder.getNativesDir("1.12.2"))
    }

    @Test
    fun `getNativesDir returns correct path for 1_21_1`() {
        assertEquals("bin/natives-1.21.1", builder.getNativesDir("1.21.1"))
    }

    @Test
    fun `getNativesDir throws on unsupported version`() {
        assertFailsWith<IllegalArgumentException> {
            builder.getNativesDir("1.99.0")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Common structure -- applies to all versions
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build starts with java executable`() {
        val cmd = build("1.7.10")
        assertEquals("/usr/bin/java", cmd[0])
    }

    @Test
    fun `build includes -noverify flag`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.contains("-noverify"), "Should include -noverify")
    }

    @Test
    fun `build includes memory flags`() {
        val cmd = build("1.7.10", memoryMb = 8192)
        assertTrue(cmd.contains("-Xms512M"), "Should include -Xms512M")
        assertTrue(cmd.contains("-Xmx8192M"), "Should include -Xmx8192M")
    }

    @Test
    fun `build includes launcher branding properties`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.any { it.startsWith("-Dminecraft.launcher.brand=") })
        assertTrue(cmd.any { it.startsWith("-Dminecraft.launcher.version=") })
    }

    @Test
    fun `build includes auth host overrides`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.auth.host=") })
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.session.host=") })
    }

    @Test
    fun `build includes java library path pointing to natives`() {
        val cmd = build("1.7.10")
        val nativesArg = cmd.find { it.startsWith("-Djava.library.path=") }
        assertNotNull(nativesArg, "Should set java.library.path")
        assertTrue(nativesArg.contains("natives-1.7.10"), "Should point to version-specific natives dir")
    }

    @Test
    fun `build includes classpath flag`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.contains("-cp"), "Should include -cp flag")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Minecraft game arguments
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build includes player name in game args`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--username")
        assertTrue(idx >= 0, "Should include --username")
        assertEquals("TestPlayer", cmd[idx + 1])
    }

    @Test
    fun `build includes uuid in game args`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--uuid")
        assertTrue(idx >= 0, "Should include --uuid")
        assertEquals("abcdef1234567890abcdef1234567890", cmd[idx + 1])
    }

    @Test
    fun `build includes access token in game args`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--accessToken")
        assertTrue(idx >= 0, "Should include --accessToken")
        assertEquals("token_abc123", cmd[idx + 1])
    }

    @Test
    fun `build includes gameDir pointing to client root`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--gameDir")
        assertTrue(idx >= 0, "Should include --gameDir")
        assertTrue(cmd[idx + 1].contains("test-client"))
    }

    @Test
    fun `build includes assetsDir`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--assetsDir")
        assertTrue(idx >= 0, "Should include --assetsDir")
        assertTrue(cmd[idx + 1].contains("assets"))
    }

    @Test
    fun `build includes assetIndex matching version`() {
        val cmd = build("1.12.2")
        val idx = cmd.indexOf("--assetIndex")
        assertTrue(idx >= 0)
        assertEquals("1.12.2", cmd[idx + 1])
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1.7.10 -- Legacy LaunchWrapper + FML
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `1_7_10 uses LaunchWrapper as main class`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.contains("net.minecraft.launchwrapper.Launch"))
    }

    @Test
    fun `1_7_10 includes FMLTweaker from cpw package`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--tweakClass")
        assertTrue(idx >= 0, "Should include --tweakClass")
        assertEquals("cpw.mods.fml.common.launcher.FMLTweaker", cmd[idx + 1])
    }

    @Test
    fun `1_7_10 includes software OpenGL flag`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.any { it.contains("allowSoftwareOpenGL=true") })
    }

    @Test
    fun `1_7_10 includes ignoreInvalidMinecraftCertificates`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.any { it.contains("ignoreInvalidMinecraftCertificates=true") })
    }

    @Test
    fun `1_7_10 does not include module path`() {
        val cmd = build("1.7.10")
        assertFalse(cmd.contains("-p"), "Legacy should not have module path")
        assertFalse(cmd.contains("--add-modules=ALL-MODULE-PATH"))
    }

    @Test
    fun `1_7_10 does not include launchTarget arg`() {
        val cmd = build("1.7.10")
        assertFalse(cmd.contains("--launchTarget"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1.12.2 -- LaunchWrapper + Forge FML (different package)
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `1_12_2 uses LaunchWrapper as main class`() {
        val cmd = build("1.12.2")
        assertTrue(cmd.contains("net.minecraft.launchwrapper.Launch"))
    }

    @Test
    fun `1_12_2 includes FMLTweaker from net_minecraftforge package`() {
        val cmd = build("1.12.2")
        val idx = cmd.indexOf("--tweakClass")
        assertTrue(idx >= 0)
        assertEquals("net.minecraftforge.fml.common.launcher.FMLTweaker", cmd[idx + 1])
    }

    @Test
    fun `1_12_2 does not include module path`() {
        val cmd = build("1.12.2")
        assertFalse(cmd.contains("-p"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1.21.1 -- NeoForge / BootstrapLauncher
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `1_21_1 uses BootstrapLauncher as main class`() {
        val cmd = build("1.21.1")
        assertTrue(cmd.contains("cpw.mods.bootstraplauncher.BootstrapLauncher"))
    }

    @Test
    fun `1_21_1 does not include tweakClass`() {
        val cmd = build("1.21.1")
        assertFalse(cmd.contains("--tweakClass"), "NeoForge does not use tweakClass")
    }

    @Test
    fun `1_21_1 includes launchTarget forgeclient`() {
        val cmd = build("1.21.1")
        val idx = cmd.indexOf("--launchTarget")
        assertTrue(idx >= 0, "Should include --launchTarget")
        assertEquals("forgeclient", cmd[idx + 1])
    }

    @Test
    fun `1_21_1 includes ALL-MODULE-PATH`() {
        val cmd = build("1.21.1")
        assertTrue(cmd.contains("--add-modules=ALL-MODULE-PATH"))
    }

    @Test
    fun `1_21_1 includes module path with boot modules`() {
        val cmd = build("1.21.1")
        val pIdx = cmd.indexOf("-p")
        assertTrue(pIdx >= 0, "Should include -p flag for module path")

        val modulePath = cmd[pIdx + 1]
        assertTrue(modulePath.contains("securejarhandler"), "Module path should include securejarhandler")
        assertTrue(modulePath.contains("bootstraplauncher"), "Module path should include bootstraplauncher")
        assertTrue(modulePath.contains("asm"), "Module path should include asm")
    }

    @Test
    fun `1_21_1 removes boot modules from classpath`() {
        val cmd = build("1.21.1")
        val cpIdx = cmd.indexOf("-cp")
        assertTrue(cpIdx >= 0)

        val classpath = cmd[cpIdx + 1]
        // Boot modules should be in -p, NOT in -cp
        assertFalse(classpath.contains("securejarhandler"), "securejarhandler should not be in classpath")
        assertFalse(classpath.contains("bootstraplauncher"), "bootstraplauncher should not be in classpath")

        // Non-boot libraries should remain in classpath
        assertTrue(classpath.contains("guava"), "Regular libraries should stay in classpath")
    }

    @Test
    fun `1_21_1 includes ignoreList property`() {
        val cmd = build("1.21.1")
        val ignoreArg = cmd.find { it.startsWith("-DignoreList=") }
        assertNotNull(ignoreArg, "Should include -DignoreList")
    }

    @Test
    fun `1_21_1 includes DlibraryDirectory property`() {
        val cmd = build("1.21.1")
        val libArg = cmd.find { it.startsWith("-DlibraryDirectory=") }
        assertNotNull(libArg, "Should include -DlibraryDirectory")
    }

    @Test
    fun `1_21_1 includes incubator vector module`() {
        val cmd = build("1.21.1")
        assertTrue(cmd.contains("--add-modules=jdk.incubator.vector"))
    }

    @Test
    fun `1_21_1 includes java opens for modern Forge`() {
        val cmd = build("1.21.1")
        assertTrue(cmd.any { it.startsWith("--add-opens=java.base/") }, "Should include --add-opens for Java modules")
    }

    @Test
    fun `1_21_1 includes default FML args`() {
        val cmd = build("1.21.1")
        val fmlIdx = cmd.indexOf("--fml.mcVersion")
        assertTrue(fmlIdx >= 0, "Should include --fml.mcVersion")
        assertEquals("1.21.1", cmd[fmlIdx + 1])
    }

    @Test
    fun `1_21_1 includes neoForgeVersion in FML args`() {
        val cmd = build("1.21.1")
        val idx = cmd.indexOf("--fml.neoForgeVersion")
        assertTrue(idx >= 0, "Should include --fml.neoForgeVersion")
        assertTrue(cmd[idx + 1].isNotBlank())
    }

    @Test
    fun `1_21_1 backend neoForgeArgs override defaults`() {
        val srv = server(version = "1.21.1").copy(
            neoForgeArgs = mapOf("neoForgeVersion" to "99.0.0")
        )
        val cmd = builder.build(
            javaExec = "/usr/bin/java",
            memoryMB = 4096,
            clientRoot = clientRoot,
            serverProfile = srv,
            session = session(),
            userProfile = profile(),
            classpath = neoForgeClasspath
        )
        val idx = cmd.indexOf("--fml.neoForgeVersion")
        assertTrue(idx >= 0)
        assertEquals("99.0.0", cmd[idx + 1], "Backend arg should override default")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom JVM args from user profile
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `custom jvmArgs from profile replace default GC args`() {
        val cmd = build("1.7.10", jvmArgs = "-XX:+UseZGC -XX:+ZGenerational")
        assertTrue(cmd.contains("-XX:+UseZGC"))
        assertTrue(cmd.contains("-XX:+ZGenerational"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unsupported version
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `build throws on unsupported version`() {
        assertFailsWith<IllegalArgumentException> {
            build("1.99.0")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Version prefix matching
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `version starting with known prefix is resolved correctly`() {
        // "1.7.10-forge" should match the "1.7.10" config
        val cmd = builder.build(
            javaExec = "/usr/bin/java",
            memoryMB = 4096,
            clientRoot = clientRoot,
            serverProfile = server(version = "1.7.10-forge"),
            session = session(),
            userProfile = profile(),
            classpath = legacyClasspath
        )
        assertTrue(cmd.contains("net.minecraft.launchwrapper.Launch"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Argument ordering sanity
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `main class appears after -cp and before game args`() {
        val cmd = build("1.7.10")
        val cpIdx = cmd.indexOf("-cp")
        val mainClassIdx = cmd.indexOf("net.minecraft.launchwrapper.Launch")
        val usernameIdx = cmd.indexOf("--username")

        assertTrue(cpIdx >= 0)
        assertTrue(mainClassIdx >= 0)
        assertTrue(usernameIdx >= 0)
        assertTrue(cpIdx < mainClassIdx, "-cp should come before main class")
        assertTrue(mainClassIdx < usernameIdx, "Main class should come before game args")
    }

    @Test
    fun `memory flags appear before -cp`() {
        val cmd = build("1.7.10")
        val xmxIdx = cmd.indexOfFirst { it.startsWith("-Xmx") }
        val cpIdx = cmd.indexOf("-cp")
        assertTrue(xmxIdx < cpIdx, "Memory flags should come before classpath")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom ignoreModulesList from server profile
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `1_21_1 uses custom ignoreModulesList when provided`() {
        val srv = server(version = "1.21.1").copy(
            ignoreModulesList = "client,custom-module,another"
        )
        val cmd = builder.build(
            javaExec = "/usr/bin/java",
            memoryMB = 4096,
            clientRoot = clientRoot,
            serverProfile = srv,
            session = session(),
            userProfile = profile(),
            classpath = neoForgeClasspath
        )
        val ignoreArg = cmd.find { it.startsWith("-DignoreList=") }
        assertNotNull(ignoreArg)
        assertTrue(ignoreArg.contains("custom-module"))
    }
}
