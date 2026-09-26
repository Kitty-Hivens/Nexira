package hivens.launcher.component

import hivens.core.data.SessionData
import hivens.launcher.runtime.MavenCoord
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
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

    private val sep = File.pathSeparator

    // ═══════════════════════════════════════════════════════════════════════════
    // packNativesDir
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `packNativesDir answers for any version`() {
        assertEquals("bin/natives-1.7.10", builder.packNativesDir("1.7.10"))
        assertEquals("bin/natives-1.12.2", builder.packNativesDir("1.12.2"))
        assertEquals("bin/natives-1.21.1", builder.packNativesDir("1.21.1"))
        // The point of the function: a version nobody enumerated still resolves.
        assertEquals("bin/natives-1.20.4", builder.packNativesDir("1.20.4"))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // buildPackCommand -- profile-driven pack launch (loader-resolved runtime)
    // ═══════════════════════════════════════════════════════════════════════════

    // Mirrors the shape a real 1.12.2 Forge pack merges into: the vanilla base
    // first, then the loader's own additions in the order its version json
    // declares them (universal, asm, launchwrapper). The bootstrap jars are NOT
    // at the head of the list, which is the case that matters for `-cp` ordering.
    private fun forgeRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("com.google.guava:guava:21.0"), Path.of("/libs/com/google/guava/guava/21.0/guava-21.0.jar")),
            ResolvedLibrary(MavenCoord.parse("net.minecraftforge:forge:1.12.2-14.23.5.2860"), Path.of("/libs/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860.jar")),
            ResolvedLibrary(MavenCoord.parse("org.ow2.asm:asm-debug-all:5.2"), Path.of("/libs/org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar")),
            ResolvedLibrary(MavenCoord.parse("net.minecraft:launchwrapper:1.12"), Path.of("/libs/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")),
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


    private fun earlyScreenCommand(earlyLoadingScreen: Boolean?) = builder.buildPackCommand(
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
        earlyLoadingScreen  = earlyLoadingScreen,
    )

    @Test
    fun `a launch with the loading screen off carries the property older Forge reads`() {
        assertTrue(earlyScreenCommand(false).contains("-Dfml.earlyprogresswindow=false"))
    }

    @Test
    fun `a launch that leaves the loading screen alone or wants it adds nothing`() {
        assertFalse(earlyScreenCommand(null).any { it.startsWith("-Dfml.earlyprogresswindow") })
        assertFalse(earlyScreenCommand(true).any { it.startsWith("-Dfml.earlyprogresswindow") })
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

    /**
     * The loader's declared order is what decides which of the duplicate root
     * `log4j2.xml` resources wins (the vanilla client ships one, so does the
     * Forge universal jar). Reordering the libraries to put the bootstrap first
     * handed that choice to the client jar and cost the launch Forge's own
     * logging config.
     */
    @Test
    fun `buildPackCommand keeps the declared library order and slots the client after the bootstrap`() {
        val rt = forgeRuntime()
        val client = rt.clientJar.toAbsolutePath().toString()
        val cmd = packCommand()
        val parts = cmd[cmd.indexOf("-cp") + 1].split(sep)
        assertEquals(
            rt.libraries.map { it.path.toAbsolutePath().toString() },
            parts.filterNot { it == client },
            "libraries must keep the order the loader declared, got: $parts",
        )
        // The full client path must be ONE entry -- guards the Path-is-Iterable
        // `+` gotcha that split the jar into its individual path segments.
        val clientIdx = parts.indexOf(client)
        assertTrue(clientIdx >= 0, "client jar must be a single full-path cp entry, got: $parts")
        assertTrue(
            parts.indexOfFirst { it.contains("launchwrapper") } < clientIdx,
            "the bootstrap must still precede the client jar, got: $parts",
        )
        assertTrue(
            parts.indexOfFirst { it.contains("forge-1.12.2") } < clientIdx,
            "the loader core must precede the client jar so its log4j2.xml wins, got: $parts",
        )
        assertTrue(parts.none { it.contains("${File.separator}mods${File.separator}") }, "mods stay off the classpath")
    }

    // A Cleanroom runtime -- launchwrapper-family like forgeRuntime, but the
    // bootstrap is top.outlands.foundation.boot.Foundation (launchwrapper's
    // replacement) rather than launchwrapper itself. The order follows the
    // installer's version.json, which declares the loader core well ahead of asm
    // and foundation.
    private fun cleanroomRuntime() = ResolvedRuntime(
        libraries = listOf(
            ResolvedLibrary(MavenCoord.parse("com.google.guava:guava:33.6.0-jre"), Path.of("/libs/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar")),
            ResolvedLibrary(MavenCoord.parse("com.cleanroommc:cleanroom:0.6.4-alpha"), Path.of("/libs/com/cleanroommc/cleanroom/0.6.4-alpha/cleanroom-0.6.4-alpha.jar")),
            ResolvedLibrary(MavenCoord.parse("org.lwjgl:lwjgl-glfw:3.4.1"), Path.of("/libs/org/lwjgl/lwjgl-glfw/3.4.1/lwjgl-glfw-3.4.1.jar")),
            ResolvedLibrary(MavenCoord.parse("org.ow2.asm:asm:9.10.1"), Path.of("/libs/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")),
            ResolvedLibrary(MavenCoord.parse("top.outlands:foundation:0.19.8"), Path.of("/libs/top/outlands/foundation/0.19.8/foundation-0.19.8.jar")),
        ),
        clientJar = Path.of("/libs/net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar"),
        mainClass = "top.outlands.foundation.boot.Foundation",
        assetIndexId = "1.12",
        gameArgs = listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"),
        javaMajor = 25,
    )

    /**
     * The Cleanroom core and foundation both ship a root `log4j2.xml`, and
     * foundation's names a `%rgbFormat` converter that exists in no jar of the
     * runtime. Whichever lands first on `-cp` therefore decides whether the game
     * logs its messages or a stream of `748gbFormat` with no newlines in it.
     * Declared order keeps the core in front, which is the config that works.
     */
    @Test
    fun `buildPackCommand leaves the Cleanroom core ahead of foundation and both ahead of the client`() {
        val rt = cleanroomRuntime()
        val cmd = packCommand(rt, javaMajor = 25)
        val parts = cmd[cmd.indexOf("-cp") + 1].split(sep)
        val coreIdx = parts.indexOfFirst { it.contains("cleanroom-") }
        val foundationIdx = parts.indexOfFirst { it.contains("foundation") }
        val clientIdx = parts.indexOf(rt.clientJar.toAbsolutePath().toString())
        assertTrue(coreIdx >= 0, "cleanroom core jar present, got: $parts")
        assertTrue(foundationIdx >= 0, "foundation jar present, got: $parts")
        assertTrue(clientIdx >= 0, "client jar is one entry, got: $parts")
        assertTrue(coreIdx < foundationIdx, "the core must keep its declared slot ahead of foundation, got: $parts")
        assertTrue(foundationIdx < clientIdx, "Foundation must precede the client jar, got: $parts")
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
