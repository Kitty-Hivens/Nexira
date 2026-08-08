package hivens.launcher.component

import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.OfflineIdentity
import hivens.core.data.SessionData
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class GameCommandBuilderTest {

    // Pinned rather than left to the environment: the default reads WAYLAND_DISPLAY,
    // which would make every assertion here depend on the session the tests run in.
    private val builder = GameCommandBuilder(waylandSession = false)

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
    // FML early window
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildUnder(waylandSession: Boolean, version: String = "1.21.1"): List<String> =
        GameCommandBuilder(waylandSession = waylandSession).build(
            javaExec      = "/usr/bin/java",
            memoryMB      = 4096,
            clientRoot    = clientRoot,
            serverProfile = server(version = version),
            session       = session(),
            userProfile   = profile(),
            classpath     = if (version.startsWith("1.21")) neoForgeClasspath else legacyClasspath,
        )

    /**
     * A surface nobody is looking at gets no frame callbacks on Wayland, so FML's
     * early window stalls the moment the user switches workspace and its
     * one-second handoff to Minecraft fails, ending the launch. Skipping the
     * early window removes the handoff rather than racing it.
     */
    @Test
    fun `a wayland session skips FML's early window`() {
        val cmd = buildUnder(waylandSession = true)
        assertTrue(
            cmd.contains("-Dfml.earlyprogresswindow=false"),
            "the early window must be off where its handoff can be starved",
        )
    }

    @Test
    fun `elsewhere the early window is left alone`() {
        val cmd = buildUnder(waylandSession = false)
        assertFalse(
            cmd.any { it.startsWith("-Dfml.earlyprogresswindow") },
            "nothing was wrong with the early window off Wayland, so it keeps its loading bar",
        )
    }

    @Test
    fun `the legacy path gets the same treatment`() {
        val cmd = buildUnder(waylandSession = true, version = "1.7.10")
        assertTrue(
            cmd.contains("-Dfml.earlyprogresswindow=false"),
            "the flag is inert where there is no early window, and correct where there is",
        )
    }

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

    @Test
    fun `packNativesDir works for any version, unlike the SC-map getNativesDir`() {
        // The pack path must not route through the SC VersionConfig map -- doing
        // so threw "Unsupported client version" for every MC outside 1.7/1.12/1.21.
        assertEquals("bin/natives-1.20.1", builder.packNativesDir("1.20.1"))
        assertEquals("bin/natives-1.19.4", builder.packNativesDir("1.19.4"))
        assertEquals("bin/natives-1.21.1", builder.packNativesDir("1.21.1"))
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
    fun `build includes -noverify flag for legacy versions`() {
        val cmd = build("1.7.10")
        assertTrue(cmd.contains("-noverify"), "Should include -noverify on legacy")
    }

    @Test
    fun `build omits -noverify flag for modern environments`() {
        // -noverify was deprecated in Java 13 -- Java 21 (1.21.1) prints a
        // warning per launch. Legacy MC on Java 8 still needs it for Forge.
        val cmd = build("1.21.1")
        assertFalse(cmd.contains("-noverify"), "Should NOT include -noverify on modern")
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
        // The session pair lives at the BARE host over plain http -- SC's own
        // patched authlib hardcodes that environment; /launcher/ and https 404.
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.session.host=http://") && !it.contains("/launcher") })
        // Modern authlib ignores the redirect wholesale without services.host.
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.services.host=http://") && !it.contains("/launcher") })
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
    fun `build emits userType mojang for an online session`() {
        val cmd = build("1.7.10")
        val idx = cmd.indexOf("--userType")
        assertTrue(idx >= 0, "Should include --userType")
        assertEquals("mojang", cmd[idx + 1])
    }

    @Test
    fun `build emits userType legacy and the offline uuid for an offline session`() {
        val offline = SessionData(
            playerName  = "TestPlayer",
            uuid        = OfflineIdentity.dashlessUuidFor("TestPlayer"),
            accessToken = "",
            offline     = true,
        )
        val cmd = builder.build(
            javaExec      = "/usr/bin/java",
            memoryMB      = 4096,
            clientRoot    = clientRoot,
            serverProfile = server(version = "1.7.10"),
            session       = offline,
            userProfile   = profile(),
            classpath     = legacyClasspath,
        )
        assertEquals("legacy", cmd[cmd.indexOf("--userType") + 1])
        assertEquals(OfflineIdentity.dashlessUuidFor("TestPlayer"), cmd[cmd.indexOf("--uuid") + 1])
        assertEquals("0", cmd[cmd.indexOf("--accessToken") + 1], "blank offline token degrades to 0")
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

    // ═══════════════════════════════════════════════════════════════════════════
    // buildPackCommand -- profile-driven pack launch (loader-resolved runtime)
    // ═══════════════════════════════════════════════════════════════════════════

    private fun forgeRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("net.minecraft:launchwrapper:1.12"), Path.of("/libs/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")),
            ResolvedLibrary(MavenCoord.parse("org.ow2.asm:asm-debug-all:5.2"), Path.of("/libs/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar")),
            ResolvedLibrary(MavenCoord.parse("com.google.guava:guava:21.0"), Path.of("/libs/com/google/guava/guava/21.0/guava-21.0.jar")),
            ResolvedLibrary(MavenCoord.parse("net.minecraftforge:forge:1.12.2-14.23.5.2860"), Path.of("/libs/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar")),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar"),
        mainClass = "net.minecraft.launchwrapper.Launch",
        assetIndexId = "1.12",
        gameArgs = listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"),
    )

    private fun packCommand(
        runtime: ResolvedRuntime = forgeRuntime(),
        javaMajor: Int = 8,
        redirectAuthHost: Boolean = true,
        authlibAgentJarPath: Path? = null,
    ) = builder.buildPackCommand(
        javaExec = "/usr/bin/java",
        memoryMB = 4096,
        gameDir = Path.of("/tmp/instances/Industrial"),
        sharedAssetsDir = Path.of("/tmp/shared/assets"),
        sharedLibrariesDir = Path.of("/tmp/shared/libraries"),
        nativesDirName = "bin/natives-1.12.2",
        versionLabel = "Forge 1.12.2",
        javaMajor = javaMajor,
        runtime = runtime,
        session = session(),
        jvmArgsOverride = null,
        redirectAuthHost = redirectAuthHost,
        authlibAgentJarPath = authlibAgentJarPath,
    )


    /**
     * The path that actually broke: a pack launch on Hyprland died at the handoff
     * because the user switched workspace while the pack loaded.
     */
    @Test
    fun `a pack launch on wayland skips the early window too`() {
        val cmd = GameCommandBuilder(waylandSession = true).buildPackCommand(
            javaExec            = "/usr/bin/java",
            memoryMB            = 4096,
            gameDir             = Path.of("/tmp/instances/Industrial"),
            sharedAssetsDir     = Path.of("/tmp/shared/assets"),
            sharedLibrariesDir  = Path.of("/tmp/shared/libraries"),
            nativesDirName      = "bin/natives-1.12.2",
            versionLabel        = "Forge 1.12.2",
            javaMajor           = 8,
            runtime             = forgeRuntime(),
            session             = session(),
            jvmArgsOverride     = null,
        )
        assertTrue(cmd.contains("-Dfml.earlyprogresswindow=false"), "the pack path is the one that broke")
    }

    @Test
    fun `a pack launch elsewhere keeps the early window`() {
        val cmd = packCommand()
        assertFalse(cmd.any { it.startsWith("-Dfml.earlyprogresswindow") })
    }

    @Test
    fun `buildPackCommand redirects the auth hosts for a mirror-derived pack`() {
        val cmd = packCommand(redirectAuthHost = true)
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.auth.host=") })
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.account.host=") })
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.session.host=http://") && !it.contains("/launcher") })
        assertTrue(cmd.any { it.startsWith("-Dminecraft.api.services.host=http://") && !it.contains("/launcher") })
    }

    @Test
    fun `buildPackCommand leaves the default auth hosts for a non-mirror pack`() {
        // Modrinth / local / own packs keep the default Mojang hosts so their own
        // auth provider is not redirected to the mirror.
        val cmd = packCommand(redirectAuthHost = false)
        assertFalse(cmd.any { it.startsWith("-Dminecraft.api.auth.host=") })
        assertFalse(cmd.any { it.startsWith("-Dminecraft.api.account.host=") })
        assertFalse(cmd.any { it.startsWith("-Dminecraft.api.session.host=") })
        assertFalse(cmd.any { it.startsWith("-Dminecraft.api.services.host=") })
    }

    @Test
    fun `buildPackCommand attaches the authlib agent pointed at the SC host when given a jar`() {
        val agent = Path.of("/tmp/runtime/authlib-agent-deadbeef.jar")
        val cmd = packCommand(authlibAgentJarPath = agent)
        // The agent flag carries the jar path plus the host as an option suffix;
        // sslBypassHost of the default config is the production SC host.
        val flag = cmd.firstOrNull { it.startsWith("-javaagent:") && it.contains("authlib-agent") }
        assertNotNull(flag, "authlib agent must be on the command; got: $cmd")
        assertTrue(flag.replace('\\', '/').contains("/tmp/runtime/authlib-agent-deadbeef.jar"),
            "agent jar path must be in the flag; got: $flag")
        assertTrue(flag.endsWith("=host=www.smartycraft.ru"),
            "host option must point at the SC host; got: $flag")
    }

    @Test
    fun `buildPackCommand omits the authlib agent when no jar is given`() {
        val cmd = packCommand(authlibAgentJarPath = null)
        assertFalse(cmd.any { it.startsWith("-javaagent:") && it.contains("authlib-agent") })
    }

    @Test
    fun `buildPackCommand drives mainClass, assetIndex and tweak from the runtime`() {
        val cmd = packCommand()
        assertEquals("/usr/bin/java", cmd[0])
        assertTrue(cmd.contains("-noverify"), "legacy launchwrapper runtime gets -noverify")
        assertTrue(cmd.contains("net.minecraft.launchwrapper.Launch"))
        assertEquals("1.12", cmd[cmd.indexOf("--assetIndex") + 1])
        // Normalize separators -- the resolved path uses '\' on Windows.
        assertTrue(cmd[cmd.indexOf("--assetsDir") + 1].replace('\\', '/').contains("shared/assets"), "assets from the shared root")
        assertEquals("net.minecraftforge.fml.common.launcher.FMLTweaker", cmd[cmd.indexOf("--tweakClass") + 1])
    }

    @Test
    fun `buildPackCommand classpath is bootstrap-first, client is one entry, excludes mods`() {
        val cmd = packCommand()
        val parts = cmd[cmd.indexOf("-cp") + 1].split(sep)
        assertTrue(parts[0].contains("launchwrapper") || parts[0].contains("asm"), "bootstrap jar first, got ${parts[0]}")
        // The full client path must be ONE entry -- guards the Path-is-Iterable
        // `+` gotcha that split the jar into its individual path segments.
        assertTrue(
            parts.contains(forgeRuntime().clientJar.toAbsolutePath().toString()),
            "client jar must be a single full-path cp entry, got: $parts",
        )
        assertTrue(parts.none { it.contains("${File.separator}mods${File.separator}") }, "mods stay off the classpath")
    }

    // A Cleanroom runtime -- launchwrapper-family like forgeRuntime, but the
    // bootstrap is top.outlands.foundation.boot.Foundation (launchwrapper's
    // replacement) rather than launchwrapper itself.
    private fun cleanroomRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("com.google.guava:guava:33.6.0-jre"), Path.of("/libs/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar")),
            ResolvedLibrary(MavenCoord.parse("org.ow2.asm:asm:9.10.1"), Path.of("/libs/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")),
            ResolvedLibrary(MavenCoord.parse("top.outlands:foundation:0.19.8"), Path.of("/libs/top/outlands/foundation/0.19.8/foundation-0.19.8.jar")),
            ResolvedLibrary(MavenCoord.parse("org.lwjgl:lwjgl-glfw:3.4.1"), Path.of("/libs/org/lwjgl/lwjgl-glfw/3.4.1/lwjgl-glfw-3.4.1.jar")),
            ResolvedLibrary(MavenCoord.parse("com.cleanroommc:cleanroom:0.6.4-alpha"), Path.of("/libs/com/cleanroommc/cleanroom/0.6.4-alpha/cleanroom-0.6.4-alpha.jar")),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar"),
        mainClass = "top.outlands.foundation.boot.Foundation",
        assetIndexId = "1.12",
        gameArgs = listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"),
        javaMajor = 25,
    )

    @Test
    fun `buildPackCommand puts the Cleanroom Foundation bootstrap ahead of the client jar`() {
        val rt = cleanroomRuntime()
        val cmd = packCommand(rt, javaMajor = 25)
        val parts = cmd[cmd.indexOf("-cp") + 1].split(sep)
        val foundationIdx = parts.indexOfFirst { it.contains("foundation") }
        val clientIdx = parts.indexOf(rt.clientJar.toAbsolutePath().toString())
        assertTrue(foundationIdx >= 0, "foundation jar present, got: $parts")
        assertTrue(clientIdx >= 0, "client jar is one entry, got: $parts")
        assertTrue(foundationIdx < clientIdx, "Foundation must precede the client jar; got: $parts")
        assertTrue(parts[0].contains("foundation") || parts[0].contains("asm"), "bootstrap jar first, got ${parts[0]}")
    }

    // A modern (BootstrapLauncher) runtime -- drives modernClasspath, unlike the
    // legacy launchwrapper forgeRuntime.
    private fun modernRuntime(clientResources: Path? = null) = forgeRuntime().copy(
        libraries = forgeRuntime().libraries + ResolvedLibrary(
            MavenCoord.parse("cpw.mods:bootstraplauncher:1.1.2"),
            Path.of("/libs/cpw/mods/bootstraplauncher/1.1.2/bootstraplauncher-1.1.2.jar"),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.21.1/minecraft-1.21.1.jar"),
        mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
        clientResourcesJar = clientResources,
    )

    @Test
    fun `modern -cp carries the resources-only client jar for its version json but never the class-bearing client`() {
        val extra = Path.of("/libs/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar")
        val cmd = packCommand(modernRuntime(clientResources = extra), javaMajor = 21)
        val cp = cmd[cmd.indexOf("-cp") + 1].split(sep)
        assertTrue(
            cp.contains(extra.toAbsolutePath().toString()),
            "the -extra (version.json, no classes) jar must be on -cp so CustomSkinLoader reads the real MC version; got: $cp",
        )
        // The class-bearing client stays OFF -cp -- a second `minecraft` module
        // would break BootstrapLauncher ("reads more than one module named minecraft").
        assertFalse(
            cp.contains(modernRuntime().clientJar.toAbsolutePath().toString()),
            "the class-bearing client jar must NOT be on -cp; got: $cp",
        )
    }

    @Test
    fun `modern -cp omits the resources jar when the runtime has none`() {
        val cmd = packCommand(modernRuntime(clientResources = null), javaMajor = 21)
        val cp = cmd[cmd.indexOf("-cp") + 1].split(sep)
        assertFalse(cp.any { it.endsWith("-extra.jar") }, "no resources jar expected on -cp; got: $cp")
    }

    @Test
    fun `buildPackCommand omits -noverify on Java 17+ even though it adds it on Java 8`() {
        // -noverify is legitimate on Java 8 (broken legacy bytecode) but warns
        // on 13+, so the choice is by Java major, not by main class.
        assertTrue(packCommand(javaMajor = 8).contains("-noverify"))
        val modern = forgeRuntime().copy(mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher")
        assertFalse(packCommand(modern, javaMajor = 21).contains("-noverify"))
    }

    private fun vanillaRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("com.mojang:logging:1.1.1"), Path.of("/libs/com/mojang/logging/1.1.1/logging-1.1.1.jar")),
            ResolvedLibrary(MavenCoord.parse("org.lwjgl:lwjgl:3.3.3"), Path.of("/libs/org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar")),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.20.1/minecraft-1.20.1.jar"),
        mainClass = "net.minecraft.client.main.Main",
        assetIndexId = "5",
        // No loader overlay: the vanilla ensureRuntime branch leaves jvmArgs empty.
    )

    @Test
    fun `buildPackCommand keeps the vanilla client on -cp for a loaderless modern pack`() {
        // A Modrinth/vanilla pack on 1.20 resolves to the vanilla main class with
        // empty jvm args -> it must take the legacy (non-templated) path that puts
        // the client jar on -cp. Guards a future vanilla-branch change (e.g. adding
        // jvm args) from silently flipping it onto modernClasspath, which drops the
        // client and would leave a vanilla launch with no minecraft on the classpath.
        val cmd = packCommand(runtime = vanillaRuntime(), javaMajor = 17)
        assertEquals(1, cmd.count { it == "-cp" })
        val cp = cmd[cmd.indexOf("-cp") + 1]
        assertTrue(cp.contains("minecraft-1.20.1.jar"), "vanilla pack must carry the client jar on -cp, got $cp")
        assertTrue(cmd.contains("net.minecraft.client.main.Main"))
        assertFalse(cmd.contains("-p"), "a vanilla launch has no module path")
    }

    private fun neoForgeRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("cpw.mods:bootstraplauncher:2.0.2"), Path.of("/libs/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar")),
            ResolvedLibrary(MavenCoord.parse("cpw.mods:securejarhandler:3.0.8"), Path.of("/libs/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar")),
            ResolvedLibrary(MavenCoord.parse("net.neoforged:neoforge:21.1.66"), Path.of("/libs/net/neoforged/neoforge/21.1.66/neoforge-21.1.66.jar")),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.21.1/minecraft-1.21.1.jar"),
        mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
        assetIndexId = "17",
        // A representative modern arguments.jvm: vanilla's `-cp ${classpath}` +
        // `-Djava.library.path` (both must be dropped), an --add-opens to keep,
        // the loader's -p with placeholders, and -DlibraryDirectory.
        jvmArgs = listOf(
            $$"-Djava.library.path=${natives_directory}",
            "-cp", $$"${classpath}",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-p", $$"${library_directory}/cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar${classpath_separator}${library_directory}/cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
            $$"-DlibraryDirectory=${library_directory}",
            "-DignoreList=client-extra,neoforge-",
        ),
        gameArgs = listOf("--launchTarget", "neoforgeclient", "--fml.neoForgeVersion", "21.1.66"),
    )

    // --- what a bound launch is allowed to carry -------------------------------

    private fun packCmd(jvmArgs: String?, bound: Boolean) = builder.buildPackCommand(
        javaExec = "/usr/bin/java",
        memoryMB = 4096,
        gameDir = Path.of("/tmp/instances/NeoPack"),
        sharedAssetsDir = Path.of("/tmp/shared/assets"),
        sharedLibrariesDir = Path.of("/tmp/shared/libraries"),
        nativesDirName = "bin/natives-1.21.1",
        versionLabel = "NeoForge 1.21.1",
        javaMajor = 21,
        runtime = neoForgeRuntime(),
        session = session(),
        jvmArgsOverride = jvmArgs,
        restrictJvmArgs = bound,
    )

    @Test
    fun `a bound launch carries the user's tuning and not their agent`() {
        val cmd = packCmd("-Xmx6G -XX:+UseZGC -Dmixin.debug=true -javaagent:/tmp/cheat.jar", bound = true)

        assertTrue(cmd.contains("-Xmx6G"), "heap is the user's call")
        assertTrue(cmd.contains("-XX:+UseZGC"), "collector choice is the user's call")
        assertTrue(cmd.contains("-Dmixin.debug=true"), "mod properties pass")
        assertFalse(
            cmd.any { it == "-javaagent:/tmp/cheat.jar" },
            "a user-supplied agent never reaches a launch that carries a token",
        )
    }

    @Test
    fun `an unbound launch is its owner's game`() {
        val cmd = packCmd("-javaagent:/tmp/mine.jar -Xmx6G", bound = false)

        assertTrue(cmd.contains("-javaagent:/tmp/mine.jar"), "no binding, no token, no policy")
        assertTrue(cmd.contains("-Xmx6G"))
    }

    @Test
    fun `the attach mechanism is closed for a bound launch and left alone otherwise`() {
        assertTrue(packCmd(null, bound = true).contains("-XX:+DisableAttachMechanism"))
        assertFalse(packCmd(null, bound = false).contains("-XX:+DisableAttachMechanism"))
    }

    /**
     * Order matters as much as policy here: HotSpot takes the last occurrence of
     * a flag, so the guard has to sit after anything the user contributed.
     */
    @Test
    fun `the attach guard cannot be undone by ordering`() {
        val cmd = packCmd("-XX:-DisableAttachMechanism", bound = true)

        assertEquals(
            listOf("-XX:+DisableAttachMechanism"),
            cmd.filter { it.endsWith("DisableAttachMechanism") },
            "the user's negation is refused outright, and ours is last regardless",
        )
    }

    @Test
    fun `buildPackCommand modern path substitutes placeholders and rebuilds cp`() {
        val cmd = builder.buildPackCommand(
            javaExec = "/usr/bin/java",
            memoryMB = 4096,
            gameDir = Path.of("/tmp/instances/NeoPack"),
            sharedAssetsDir = Path.of("/tmp/shared/assets"),
            sharedLibrariesDir = Path.of("/tmp/shared/libraries"),
            nativesDirName = "bin/natives-1.21.1",
            versionLabel = "NeoForge 1.21.1",
            javaMajor = 21,
            runtime = neoForgeRuntime(),
            session = session(),
            jvmArgsOverride = null,
        )

        // The inherited vanilla -cp ${classpath} pair is dropped; exactly one -cp
        // remains -- ours -- and it carries the client + libs, not ${classpath}.
        assertEquals(1, cmd.count { it == "-cp" }, "only the builder's own -cp survives")
        val cp = cmd[cmd.indexOf("-cp") + 1]
        assertFalse(cp.contains($$"${classpath}"), "the placeholder must be gone, got $cp")
        assertFalse(cp.contains("minecraft-1.21.1.jar"), "vanilla client NOT on a modern cp -- FML loads its processor client")
        assertTrue(cp.contains("neoforge-21.1.66.jar"), "loader libs on the classpath")

        // Module path kept, with ${library_directory}/${classpath_separator} resolved.
        val pValue = cmd[cmd.indexOf("-p") + 1]
        assertTrue(pValue.contains("bootstraplauncher-2.0.2.jar"), "boot module on -p")
        assertTrue(pValue.replace('\\', '/').contains("/tmp/shared/libraries"), "library_directory substituted, got $pValue")
        assertFalse(pValue.contains($$"${"), "no placeholder left in -p, got $pValue")
        assertTrue(pValue.contains(File.pathSeparator), "two boot jars joined by the path separator, got $pValue")

        assertTrue(cmd.any { it.startsWith("-DlibraryDirectory=") && it.replace('\\', '/').contains("/tmp/shared/libraries") }, "libraryDirectory substituted")
        assertTrue(cmd.contains("--add-opens=java.base/java.lang=ALL-UNNAMED"), "kept add-opens")
        assertTrue(cmd.contains("--add-modules=jdk.incubator.vector"), "vector module added on the module path")

        // Inherited -Djava.library.path is dropped; the builder emits its own.
        assertEquals(1, cmd.count { it.startsWith("-Djava.library.path") }, "exactly one java.library.path -- the builder's")
        assertFalse(cmd.any { it.contains($$"${natives_directory}") }, "natives placeholder not left dangling")

        // Loader game args land after the standard set.
        assertEquals("neoforgeclient", cmd[cmd.indexOf("--launchTarget") + 1])
        assertEquals("21.1.66", cmd[cmd.indexOf("--fml.neoForgeVersion") + 1])
    }

    @Test
    fun `profiler args inject as discrete elements in the JVM-arg region`() {
        val cmd = builder.buildPackCommand(
            javaExec = "/usr/bin/java",
            memoryMB = 4096,
            gameDir = Path.of("/tmp/instances/Industrial"),
            sharedAssetsDir = Path.of("/tmp/shared/assets"),
            sharedLibrariesDir = Path.of("/tmp/shared/libraries"),
            nativesDirName = "bin/natives-1.12.2",
            versionLabel = "Forge 1.12.2",
            javaMajor = 8,
            runtime = forgeRuntime(),
            session = session(),
            jvmArgsOverride = null,
            agentJarPath = Path.of("/home/My Games/runtime/profiler-agent.jar"),
            metricsOutPath = Path.of("/home/My Games/instances/Industrial/profiler-metrics.json"),
        )
        assertEquals(1, cmd.count { it.startsWith("-javaagent:") }, "exactly one -javaagent")
        assertEquals(1, cmd.count { it.startsWith("-Dnexira.profiler.out=") }, "exactly one out-property")
        // A path with a space must survive as one argv element (ProcessBuilder list form).
        assertTrue(cmd.single { it.startsWith("-javaagent:") }.contains("My Games"), "agent path kept whole")
        // Both land in the JVM-arg region: after -Xmx, before -cp / main class.
        val xmx = cmd.indexOfFirst { it.startsWith("-Xmx") }
        val cp = cmd.indexOf("-cp")
        val agent = cmd.indexOfFirst { it.startsWith("-javaagent:") }
        val outProp = cmd.indexOfFirst { it.startsWith("-Dnexira.profiler.out=") }
        assertTrue(xmx in 0 until agent && agent < cp, "agent after -Xmx and before -cp (xmx=$xmx agent=$agent cp=$cp)")
        assertTrue(outProp in 0 until cp, "out-property before -cp")
    }

    @Test
    fun `no profiler args when agent paths are null`() {
        val cmd = packCommand()
        assertTrue(cmd.none { it.startsWith("-javaagent:") }, "no -javaagent without paths")
        assertTrue(cmd.none { it.startsWith("-Dnexira.profiler.out=") }, "no out-property without paths")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildPackCommand -- optional game-window geometry
    // ═══════════════════════════════════════════════════════════════════════════

    private fun packCommandWindow(width: Int?, height: Int?, fullScreen: Boolean) = builder.buildPackCommand(
        javaExec = "/usr/bin/java",
        memoryMB = 4096,
        gameDir = Path.of("/tmp/instances/Industrial"),
        sharedAssetsDir = Path.of("/tmp/shared/assets"),
        sharedLibrariesDir = Path.of("/tmp/shared/libraries"),
        nativesDirName = "bin/natives-1.12.2",
        versionLabel = "Forge 1.12.2",
        javaMajor = 8,
        runtime = forgeRuntime(),
        session = session(),
        jvmArgsOverride = null,
        windowWidth = width,
        windowHeight = height,
        fullScreen = fullScreen,
    )

    @Test
    fun `buildPackCommand omits window geometry when no size is given`() {
        val cmd = packCommand()
        assertFalse(cmd.contains("--width"), "no --width by default")
        assertFalse(cmd.contains("--height"), "no --height by default")
        assertFalse(cmd.contains("--fullscreen"), "no --fullscreen by default")
    }

    @Test
    fun `buildPackCommand emits width and height when a size is given`() {
        val cmd = packCommandWindow(width = 1280, height = 720, fullScreen = false)
        assertEquals("1280", cmd[cmd.indexOf("--width") + 1])
        assertEquals("720", cmd[cmd.indexOf("--height") + 1])
        assertFalse(cmd.contains("--fullscreen"))
    }

    @Test
    fun `buildPackCommand emits fullscreen and drops the size in fullscreen mode`() {
        // Fullscreen wins: the client ignores an explicit size, so we do not pass one.
        val cmd = packCommandWindow(width = 1280, height = 720, fullScreen = true)
        assertTrue(cmd.contains("--fullscreen"))
        assertFalse(cmd.contains("--width"))
        assertFalse(cmd.contains("--height"))
    }
}
