package hivens.launcher.component

import hivens.config.Branding
import hivens.config.Protocol
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import hivens.core.logging.Redactor
import hivens.core.platform.OS
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.security.JvmArgPolicy
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

internal class GameCommandBuilder(
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
    // Injected rather than read at the call site so the decision can be exercised
    // without a compositor.
    private val waylandSession: Boolean = OS.isLinux && !System.getenv("WAYLAND_DISPLAY").isNullOrBlank(),
) {
    private val logger = LoggerFactory.getLogger(GameCommandBuilder::class.java)
    private val neoForgeDetector = NeoForgeVersionDetector()


    /**
     * FML draws its own window while mods load and hands it over when Minecraft
     * takes the display. It allows one second for that handoff.
     *
     * On Wayland a surface nobody is looking at stops receiving frame callbacks,
     * so the early window's loop stalls the moment the user switches workspace.
     * The handoff then misses its second and takes the launch down with it --
     * "trouble handing off the window, tried for 1 second", then exit 1. Not a
     * corner case: a large pack loads for a minute, and nobody watches a progress
     * bar for a minute.
     *
     * Skipping the early window removes the handoff rather than racing it: the
     * game opens its own window when it is ready. What is lost is FML's loading
     * bar, which the launcher is already showing on its own surface.
     */
    private fun addEarlyWindowGuard(args: MutableList<String>) {
        if (waylandSession) args.add("-Dfml.earlyprogresswindow=false")
    }

    private data class VersionConfig(
        val mainClass: String,
        val tweakClass: String?,
        val assetIndex: String,
        val jvmArgs: List<String>,
        val nativesDir: String,
        val programArgs: List<String> = emptyList()
    )

    // Registry of version configurations
    private val configs = mapOf(
        "1.7.10" to VersionConfig(
            mainClass = "net.minecraft.launchwrapper.Launch",
            tweakClass = "cpw.mods.fml.common.launcher.FMLTweaker",
            assetIndex = "1.7.10",
            jvmArgs = listOf("-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true", "-Dfml.ignoreInvalidMinecraftCertificates=true"),
            nativesDir = "bin/natives-1.7.10"
        ),
        "1.12.2" to VersionConfig(
            mainClass = "net.minecraft.launchwrapper.Launch",
            tweakClass = "net.minecraftforge.fml.common.launcher.FMLTweaker",
            assetIndex = "1.12.2",
            jvmArgs = listOf("-Dfml.ignoreInvalidMinecraftCertificates=true"),
            nativesDir = "bin/natives-1.12.2"
        ),
        "1.21.1" to VersionConfig(
            mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
            tweakClass = null,
            assetIndex = "1.21.1",
            jvmArgs = listOf(
                // Java 9+ Modules Export (Mandatory for Java 21)
                "--add-modules=ALL-MODULE-PATH",
                "--add-modules=jdk.naming.dns", "--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming",
                "--add-opens=java.base/java.util.jar=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/java.time=ALL-UNNAMED",
                // SecureJarHandler Specifics
                "--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler", "--add-opens=java.base/java.lang.invoke=cpw.mods.securejarhandler",
                "--add-exports=java.base/sun.security.util=cpw.mods.securejarhandler",
                "-Djava.awt.headless=false", "-Djava.net.preferIPv6Addresses=system"
            ),
            nativesDir = "bin/natives-1.21.1",
            programArgs = listOf("--launchTarget", "forgeclient")
        )
    )

    /**
     * Returns the path to the native libraries directory for the specified version.
     */
    fun getNativesDir(version: String): String {
        return getConfig(version).nativesDir
    }

    /**
     * Per-instance natives directory for a PACK launch. Unlike [getNativesDir]
     * (the SC server path, limited to the hardcoded [VersionConfig] map), this
     * works for any Minecraft version: the pack runtime is resolved generically,
     * so the natives folder is just the conventional `bin/natives-<version>`.
     * Using getNativesDir here threw "Unsupported client version" for every MC
     * version outside the SC map (1.7.10 / 1.12.2 / 1.21.1).
     */
    fun packNativesDir(mcVersion: String): String = "bin/natives-$mcVersion"

    /**
     * Legacy SC server-centric entry point. Projects [serverProfile] +
     * [userProfile] onto a [LaunchTarget] and delegates to the
     * domain-agnostic [build] overload below.
     */
    fun build(
        javaExec: String,
        memoryMB: Int,
        clientRoot: Path,
        serverProfile: ServerProfile,
        session: SessionData,
        userProfile: InstanceProfile,
        classpath: String,
        agentJarPath: Path? = null,
        metricsOutPath: Path? = null,
    ): List<String> = build(
        javaExec   = javaExec,
        memoryMB   = memoryMB,
        clientRoot = clientRoot,
        target     = LaunchTarget(
            mcVersion         = serverProfile.version,
            neoForgeArgs      = serverProfile.neoForgeArgs,
            ignoreModulesList = serverProfile.ignoreModulesList,
            jvmArgsOverride   = userProfile.jvmArgs,
            displayName       = serverProfile.name,
        ),
        session    = session,
        classpath  = classpath,
        agentJarPath   = agentJarPath,
        metricsOutPath = metricsOutPath,
    )

    /**
     * Closes the JVM's attach listener for a launch carrying a session token.
     *
     * Without it, any process running as the same user loads an agent into the
     * live game through the attach socket -- `jattach`, `jcmd`, the Attach API
     * -- which needs no cooperation from the launcher at all, since it happens
     * after the command line stopped mattering. Placed after the user's own
     * arguments so it wins on order as well as by policy, HotSpot taking the
     * last occurrence of a flag.
     *
     * The cost is real and worth naming: a thread dump of the game (`jstack`,
     * `jcmd`) stops working, so a hang in someone's bug report is harder to
     * read. It buys the convenient half of runtime injection; ptrace and
     * `/proc/pid/mem` are not addressable from in here and are not pretended to
     * be.
     */
    private fun addAttachGuard(args: MutableList<String>, restrict: Boolean) {
        if (restrict) args.add("-XX:+DisableAttachMechanism")
    }

    /**
     * Splits and, for a launch that will carry a token, filters the user's own
     * JVM arguments through [JvmArgPolicy]. What is refused is logged rather
     * than dropped in silence -- a flag that quietly stops applying reads as the
     * launcher being broken.
     */
    private fun userJvmArgs(raw: String?, restrict: Boolean): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        if (!restrict) return raw.trim().split(Regex("\\s+"))
        val result = JvmArgPolicy.filter(raw)
        if (result.refused.isNotEmpty()) {
            logger.warn(
                "Refused {} JVM argument(s) on a server-bound launch: {}",
                result.refused.size, result.refused,
            )
        }
        return result.kept
    }

    /**
     * Collects a list of arguments for [ProcessBuilder].
     *
     * @return An ordered list of strings, ready to be passed to the OS process.
     */
    fun build(
        javaExec: String,
        memoryMB: Int,
        clientRoot: Path,
        target: LaunchTarget,
        session: SessionData,
        classpath: String,
        agentJarPath: Path? = null,
        metricsOutPath: Path? = null,
    ): List<String> {
        val version = target.mcVersion
        val config = getConfig(version)
        val isModernEnvironment = config.mainClass.contains("BootstrapLauncher")
        val args = ArrayList<String>()

        // 1. JVM Binary
        args.add(javaExec)
        // -noverify was deprecated in Java 13 and prints a warning on every
        // launch under Java 17+. Legacy MC (1.7.10 / 1.12.2 on Java 8) still
        // needs it for the broken bytecode some Forge mods ship; modern
        // (1.21.1+, Java 21+) doesn't tolerate the warning gracefully.
        if (!isModernEnvironment) args.add("-noverify")

        // 2. OS Specific Flags
        addMacOsStartupFlags(args)

        // 3. System Properties (Launcher Identity & Custom Authlib)
        // Two eras, two bases. Legacy auth/account flows live under the SC
        // launcher API (/launcher/). The SESSION service -- the join -- lives at
        // the BARE host over plain http: SC's own patched authlib hardcodes
        // http://<host>, and both https and /launcher/ variants 404. Modern
        // authlib (1.16.4+) additionally IGNORES the redirect unless session AND
        // services are both set, so the pair is emitted together.
        args.add("-Dminecraft.api.auth.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.account.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.session.host=http://${protocolConfig.sslBypassHost}")
        args.add("-Dminecraft.api.services.host=http://${protocolConfig.sslBypassHost}")
        args.add("-Dminecraft.launcher.brand=${Branding.UPSTREAM_NAME}")
        args.add("-Dminecraft.launcher.version=${Protocol.MIMIC_LAUNCHER_VERSION}")

        // 4. Natives Configuration
        val nativesPath = clientRoot.resolve(config.nativesDir)
        args.add("-Djava.library.path=" + nativesPath.toAbsolutePath())

        // 5. NeoForge / Modern Environment
        if (isModernEnvironment) {
            val libDirStandard = clientRoot.resolve("libraries")
            // Dynamically resolve library directory based on asset index
            val libDirCustom = clientRoot.resolve("libraries-${config.assetIndex}")
            val libDir = if (libDirCustom.resolve("cpw").toFile().exists()) libDirCustom else libDirStandard

            args.add("-Djna.tmpdir=" + nativesPath.toAbsolutePath())
            args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesPath.toAbsolutePath())
            args.add("-Dio.netty.native.workdir=" + nativesPath.toAbsolutePath())
            args.add("-DlibraryDirectory=" + libDir.toAbsolutePath())

            val defaultIgnore = "client,securejarhandler,asm,bootstraplauncher,JarJarFileSystems,client-extra,neoforge-"
            val ignoreList = target.ignoreModulesList?.takeIf { it.isNotBlank() } ?: defaultIgnore
            args.add("-DignoreList=$ignoreList")
            args.add("-DmergeModules=jna-5.14.0.jar,jna-platform-5.14.0.jar")
        }
        addEarlyWindowGuard(args)

        // 6. Memory Allocation & Custom JVM Args
        val (gcArgs, systemArgs) = config.jvmArgs.partition { it.startsWith("-XX:") }
        args.addAll(systemArgs)

        // Java 21+ Vector API optimization for faster data structures in mods (e.g., JEI, Ars Nouveau)
        if (isModernEnvironment) {
            args.add("--add-modules=jdk.incubator.vector")
        }

        if (!target.jvmArgsOverride.isNullOrBlank()) {
            args.addAll(userJvmArgs(target.jvmArgsOverride, restrict = true))
        } else {
            args.addAll(gcArgs)
        }
        addAttachGuard(args, restrict = true)

        args.add("-Xms${minOf(memoryMB, 512)}M")
        args.add("-Xmx${memoryMB}M")
        addProfilerArgs(args, agentJarPath, metricsOutPath)

        // 7. Java 9+ Module Path (NeoForge / Modern Forge) dynamically resolved
        var validModules = emptyList<String>()
        if (isModernEnvironment) {
            // Strictly ONLY Bootstraplauncher, SecureJarHandler, ASM, and JarJar belong in the Module Path (-p).
            val jvmModuleKeywords = listOf(
                "securejarhandler",
                "bootstraplauncher",
                "ow2/asm",
                "jarjar"
            )

            // Dynamically extract boot modules directly from the resolved classpath
            validModules = classpath.split(File.pathSeparator).filter { path ->
                val lowerPath = path.lowercase().replace("\\", "/")
                jvmModuleKeywords.any { lowerPath.contains(it) }
            }

            if (validModules.isEmpty()) {
                // Fail loud at command-build time rather than let the
                // JVM limp into BootstrapLauncher without `-p`. The
                // downstream failure ("module not found:
                // cpw.mods.bootstraplauncher") surfaces in the game
                // console long after the user committed to a launch.
                // Exception propagates through LauncherService into
                // LauncherController's error dialog so the user sees
                // a launcher-side message instead.
                throw IllegalStateException(
                    "Cannot launch ${config.mainClass}: no NeoForge boot modules " +
                        "(securejarhandler, bootstraplauncher, ow2/asm, jarjar) found " +
                        "in the synced classpath. The pack's libraries directory is " +
                        "missing the module-path entries -- re-sync the server or " +
                        "delete clients/<server>/manifest-cache to force a full re-download.",
                )
            }
            args.add("-p")
            args.add(validModules.joinToString(File.pathSeparator))
        }

        // 8. Classpath & Entry Point
        args.add("-cp")
        if (isModernEnvironment) {
            // Remove ONLY the strict boot modules from Classpath. All other libraries stay.
            val cleanClasspath = classpath.split(File.pathSeparator)
                .filter { path -> !validModules.contains(path) }
                .joinToString(File.pathSeparator)

            args.add(cleanClasspath.ifBlank { classpath })
        } else {
            args.add(classpath)
        }

        args.add(config.mainClass)
        args.addAll(config.programArgs)

        // 9. Game Arguments
        args.addAll(buildMinecraftArgs(session, target, clientRoot, config.assetIndex, isModernEnvironment))

        if (config.tweakClass != null) {
            args.add("--tweakClass")
            args.add(config.tweakClass)
        }

        return args
    }

    /**
     * Profile-driven command for a pack-centric launch. Everything that varies
     * by loader -- main class, classpath, jvm/game args (e.g. the FML tweak) --
     * comes from the resolved [runtime], NOT the hardcoded [VersionConfig] map
     * (which stays the SC server path's domain). Assets + libraries come from
     * the SHARED roots; natives stay per-instance.
     *
     * Handles all three launch eras:
     * - launchwrapper / Knot (vanilla, Forge <=1.12.2, Fabric, Quilt): plain
     *   `-cp` + main class + game args; no arg templating.
     * - modlauncher Forge 1.13-1.16: the modern `arguments` template (inherited
     *   from vanilla) with `${...}` substitution, but launched off the classpath
     *   with no JPMS module path.
     * - BootstrapLauncher (Forge 1.17+, all NeoForge): same templating plus the
     *   module path (`-p`) carried in the version json's jvm args.
     *
     * [javaMajor] decides `-noverify` (legitimate on Java 8, warns on 17+).
     * [sharedLibrariesDir] backs the `${library_directory}` placeholder.
     */
    fun buildPackCommand(
        javaExec: String,
        memoryMB: Int,
        gameDir: Path,
        sharedAssetsDir: Path,
        sharedLibrariesDir: Path,
        nativesDirName: String,
        versionLabel: String,
        javaMajor: Int,
        runtime: ResolvedRuntime,
        session: SessionData,
        jvmArgsOverride: String?,
        redirectAuthHost: Boolean = true,
        // Hold the user's own JVM arguments to what a launch carrying a session
        // token may pass on. Same partition as the roster sweep and the
        // environment seal: a pack with no server binding is its owner's game.
        restrictJvmArgs: Boolean = true,
        agentJarPath: Path? = null,
        metricsOutPath: Path? = null,
        authlibAgentJarPath: Path? = null,
        windowWidth: Int? = null,
        windowHeight: Int? = null,
        fullScreen: Boolean = false,
    ): List<String> {
        val args = ArrayList<String>()
        args.add(javaExec)

        // BootstrapLauncher (1.17+/NeoForge) carries a JPMS module path; the
        // modern `arguments` template (anything with a ${placeholder}) needs
        // substitution and a self-built classpath -- modlauncher 1.13-1.16 has
        // the template but no module path.
        val usesModulePath = runtime.mainClass.contains("bootstraplauncher", ignoreCase = true)
        val usesModernArgs = usesModulePath || runtime.jvmArgs.any { it.contains($$"${") }
        if (javaMajor <= 8) args.add("-noverify")
        addMacOsStartupFlags(args)

        // authlib redirect: point every era's host set at the SC backend so
        // joining an SC/mirror-derived server authenticates there (same as the SC
        // path). Legacy auth/account flows live under /launcher/; the SESSION
        // service -- the join -- lives at the BARE host over plain http (SC's own
        // patched authlib hardcodes http://<host>; https and /launcher/ both
        // 404). Modern authlib (1.16.4+) additionally IGNORES the redirect
        // unless session AND services are both set -- it then joins against PROD
        // Mojang and the SC server kicks the session as invalid. Mirror-derived
        // packs ONLY -- a Modrinth / local / own pack keeps the default Mojang
        // hosts so its own auth provider (e.g. real Yggdrasil) is never
        // redirected to the mirror.
        if (redirectAuthHost) {
            args.add("-Dminecraft.api.auth.host=${protocolConfig.baseUrl}/launcher/")
            args.add("-Dminecraft.api.account.host=${protocolConfig.baseUrl}/launcher/")
            args.add("-Dminecraft.api.session.host=http://${protocolConfig.sslBypassHost}")
            args.add("-Dminecraft.api.services.host=http://${protocolConfig.sslBypassHost}")
        }
        args.add("-Dminecraft.launcher.brand=${Branding.UPSTREAM_NAME}")
        args.add("-Dminecraft.launcher.version=${Protocol.MIMIC_LAUNCHER_VERSION}")

        val nativesPath = gameDir.resolve(nativesDirName).toAbsolutePath()
        args.add("-Djava.library.path=$nativesPath")
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true")
        addEarlyWindowGuard(args)

        args.addAll(userJvmArgs(jvmArgsOverride, restrictJvmArgs))
        addAttachGuard(args, restrictJvmArgs)
        if (usesModernArgs) {
            args.addAll(modernJvmArgs(runtime, gameDir, sharedAssetsDir, sharedLibrariesDir, nativesPath, versionLabel))
            // Java 9+ Vector API speeds up some mods (JEI, Ars Nouveau); only
            // meaningful where the module path is in play.
            if (usesModulePath) args.add("--add-modules=jdk.incubator.vector")
        } else {
            args.addAll(runtime.jvmArgs)
        }
        args.add("-Xms${minOf(memoryMB, 512)}M")
        args.add("-Xmx${memoryMB}M")
        addProfilerArgs(args, agentJarPath, metricsOutPath)
        addAuthlibAgentArg(args, authlibAgentJarPath)

        args.add("-cp")
        args.add(if (usesModernArgs) modernClasspath(runtime) else packClasspath(runtime))
        args.add(runtime.mainClass)

        args.add("--username"); args.add(session.playerName)
        args.add("--version"); args.add(versionLabel)
        args.add("--gameDir"); args.add(gameDir.toAbsolutePath().toString())
        args.add("--assetsDir"); args.add(sharedAssetsDir.toAbsolutePath().toString())
        args.add("--assetIndex"); args.add(runtime.assetIndexId)
        addSessionAuthArgs(args, session)
        addWindowArgs(args, windowWidth, windowHeight, fullScreen)
        args.addAll(runtime.gameArgs)

        return args
    }

    /**
     * Optional game-window geometry. Fullscreen wins (the client ignores an
     * explicit size in that mode); otherwise a non-null width/height emits
     * `--width`/`--height`. A null size means "keep the client's own remembered
     * size" -- the pack path only passes a value when the instance opted into a
     * window-size override, so an untouched instance launches unchanged.
     */
    private fun addWindowArgs(args: MutableList<String>, width: Int?, height: Int?, fullScreen: Boolean) {
        if (fullScreen) {
            args.add("--fullscreen")
            return
        }
        if (width != null && width > 0) { args.add("--width"); args.add(width.toString()) }
        if (height != null && height > 0) { args.add("--height"); args.add(height.toString()) }
    }

    /**
     * Ordered `-cp` for a pack: bootstrap jars (launchwrapper / asm /
     * bootstraplauncher / foundation) first, then the client jar, then the rest
     * -- mirrors the proven legacy Forge classpath ordering. `foundation` is
     * Cleanroom's launchwrapper replacement (its `Foundation` bootstrap starts
     * FMLTweaker), so it takes launchwrapper's boot-first slot. Mods are NOT
     * here; the loader scans the per-instance mods/ dir.
     */
    private fun packClasspath(runtime: ResolvedRuntime): String {
        val libPaths = runtime.libraries.map { it.path }
        val (boot, rest) = libPaths.partition { p ->
            val n = p.fileName.toString().lowercase()
            n.contains("launchwrapper") || n.contains("asm") ||
                n.contains("bootstraplauncher") || n.contains("foundation")
        }
        // listOf(clientJar), NOT `+ clientJar`: a Path is Iterable<Path> over its
        // name segments, so `List<Path> + Path` would spread the client jar into
        // its path components instead of appending it as one classpath entry.
        return (boot + listOf(runtime.clientJar) + rest)
            .joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
    }

    /**
     * Full `-cp` for a modern (templated) launch: the resolved libraries only,
     * NOT the class-bearing client jar. Modern Forge/NeoForge load minecraft from
     * the installer's processor output (the slim/srg client) through FML's own path
     * locator; putting the class-bearing client on `-cp` too yields a second module
     * named `minecraft` and "reads more than one module named minecraft". Boot
     * modules stay on `-cp` -- the version json's `-DignoreList` tells
     * BootstrapLauncher which entries to keep flat versus promote to modules,
     * mirroring the official launcher.
     *
     * The resources-only client jar ([ResolvedRuntime.clientResourcesJar], the
     * installer's `-extra` output) IS appended: it carries `version.json` but no
     * classes, so it restores that resource on `-cp` (the official launcher ships
     * it there) without creating a second `minecraft` module. Without it, mods that
     * read the MC version from `version.json` as a resource -- CustomSkinLoader --
     * detect "version 0" and mis-patch.
     */
    private fun modernClasspath(runtime: ResolvedRuntime): String =
        (runtime.libraries.map { it.path } + listOfNotNull(runtime.clientResourcesJar))
            .joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }

    /**
     * Resolves the modern `arguments.jvm` template to concrete tokens. The
     * version json's `${...}` placeholders are substituted from the known
     * paths; the `-cp ${classpath}` pair and any `-Djava.library.path` are
     * dropped because the builder emits its own, while `-p <module path>` is
     * kept (its value substituted) so BootstrapLauncher gets the exact boot
     * module set the installer chose.
     */
    private fun modernJvmArgs(
        runtime: ResolvedRuntime,
        gameDir: Path,
        sharedAssetsDir: Path,
        sharedLibrariesDir: Path,
        nativesPath: Path,
        versionLabel: String,
    ): List<String> {
        val substitutions = mapOf(
            $$"${library_directory}" to sharedLibrariesDir.toAbsolutePath().toString(),
            $$"${classpath_separator}" to File.pathSeparator,
            $$"${version_name}" to versionLabel,
            $$"${natives_directory}" to nativesPath.toString(),
            $$"${assets_root}" to sharedAssetsDir.toAbsolutePath().toString(),
            $$"${game_directory}" to gameDir.toAbsolutePath().toString(),
            $$"${primary_jar}" to runtime.clientJar.toAbsolutePath().toString(),
            $$"${launcher_name}" to Branding.UPSTREAM_NAME,
            $$"${launcher_version}" to Protocol.MIMIC_LAUNCHER_VERSION,
        )
        fun substitute(token: String): String {
            var result = token
            for ((placeholder, value) in substitutions) result = result.replace(placeholder, value)
            return result
        }

        val jvm = runtime.jvmArgs
        val out = ArrayList<String>(jvm.size)
        var i = 0
        while (i < jvm.size) {
            val token = jvm[i]
            when {
                token == "-cp" || token == "-classpath" || token == "--class-path" ->
                    i += if (i + 1 < jvm.size) 2 else 1
                token == "-p" || token == "--module-path" -> {
                    if (i + 1 < jvm.size) {
                        out.add(token)
                        out.add(substitute(jvm[i + 1]))
                        i += 2
                    } else i += 1
                }
                token == $$"${classpath}" || token.startsWith("-Djava.library.path") -> i += 1
                else -> {
                    out.add(substitute(token))
                    i += 1
                }
            }
        }
        return out
    }

    /** -XstartOnFirstThread is mandatory for LWJGL on macOS; no-op on other OSes. */
    private fun addMacOsStartupFlags(args: MutableList<String>) {
        if (OS.isMacOS) {
            args.add("-XstartOnFirstThread")
            args.add("-Djava.awt.headless=false")
        }
    }

    /**
     * Attaches the heap-profiler agent for an adaptive launch. Both flags go in
     * as discrete argv elements (ProcessBuilder's list form passes each verbatim,
     * so a path with spaces is safe). The metrics out-path rides a `-D` property,
     * NOT the `-javaagent:jar=opts` suffix -- that suffix splits on its first `=`
     * and mangles Windows drive/paths. No-op unless both paths are present.
     */
    private fun addProfilerArgs(args: MutableList<String>, agentJarPath: Path?, metricsOutPath: Path?) {
        if (agentJarPath == null || metricsOutPath == null) return
        args.add("-Dnexira.profiler.out=${metricsOutPath.toAbsolutePath()}")
        args.add("-javaagent:${agentJarPath.toAbsolutePath()}")
    }

    /**
     * Attaches the authlib-redirect agent for an SC-bound join. The SC host
     * rides the `=host=<host>` agent-option suffix: the JVM splits the jar path
     * from options on the FIRST `=`, and the host is a bare hostname (no `=`, no
     * path), so the split is unambiguous -- unlike the profiler's metrics path
     * (a user-data path that could carry an `=`), which goes via `-D`. The host
     * matches the `-Dminecraft.api.*.host` redirect above so legacy and modern
     * authlib aim at the same SC backend. No-op unless an agent path is present.
     */
    private fun addAuthlibAgentArg(args: MutableList<String>, authlibAgentJarPath: Path?) {
        if (authlibAgentJarPath == null) return
        args.add("-javaagent:${authlibAgentJarPath.toAbsolutePath()}=host=${protocolConfig.sslBypassHost}")
    }

    private fun addSessionAuthArgs(args: MutableList<String>, session: SessionData) {
        // The game process echoes this token back in ways no log pattern
        // predicts -- authlib logs it verbatim when it fails to read it as a
        // JWT. Registering the value here, at the one point where a token
        // crosses into the process, masks every such echo.
        Redactor.registerSecret(session.accessToken)

        // Never emit a blank uuid/token. An offline relaunch of a server whose
        // per-server SmartyCraft token was never cached leaves accessToken empty,
        // which puts an empty element in argv ("--accessToken" then "") -- the
        // client crashes parsing it before it can even report a bad session.
        // A "0" placeholder degrades to a clean in-game "invalid session" instead.
        args.add("--uuid"); args.add(session.uuid.ifBlank { "0" })
        args.add("--accessToken"); args.add(session.accessToken.ifBlank { "0" })
        args.add("--userProperties"); args.add("{}")
        // Offline play has no Mojang/SC session: userType "legacy" tells the client
        // not to expect one. The uuid is the vanilla OfflinePlayer:<name> value, so
        // singleplayer world data lines up with other launchers' offline mode.
        args.add("--userType"); args.add(if (session.offline) "legacy" else "mojang")
    }

    private fun getConfig(version: String): VersionConfig {
        return configs[version]
            ?: configs.entries.find { version.startsWith(it.key) }?.value
            ?: throw IllegalArgumentException("Unsupported client version: $version")
    }

    private fun buildMinecraftArgs(
        session: SessionData,
        target: LaunchTarget,
        root: Path,
        assetIndex: String,
        isModernEnvironment: Boolean
    ): List<String> {
        val args = ArrayList<String>()
        args.add("--username"); args.add(session.playerName)
        args.add("--version"); args.add("Forge ${target.mcVersion}")
        args.add("--gameDir"); args.add(root.toAbsolutePath().toString())
        args.add("--assetsDir"); args.add(root.resolve("assets").toAbsolutePath().toString())
        args.add("--assetIndex"); args.add(assetIndex)
        addSessionAuthArgs(args, session)

        if (isModernEnvironment) {
            // NeoForge needs `--fml.{neoForgeVersion,fmlVersion,mcVersion,neoFormVersion}`.
            // Auto-detect from `libraries-{mcVersion}/` first -- the
            // values live in directory names and the universal jar's
            // MANIFEST.MF, so a manifest sync always brings matching
            // versions and we never drift. Fall back to baked-in
            // defaults (mirror smrt-deco) only if the layout is
            // unexpected.
            val detected = neoForgeDetector.detect(root, assetIndex)?.toMap()
            val defaultFmlArgs = detected ?: run {
                logger.warn("NeoForge auto-detect failed; using baked-in defaults")
                mapOf(
                    "neoForgeVersion" to "21.1.506",
                    "fmlVersion" to "4.0.42",
                    "mcVersion" to assetIndex,
                    "neoFormVersion" to "20240808.144430"
                )
            }

            // Backend arguments still win -- server can override what was detected.
            val backendArgs = target.neoForgeArgs ?: emptyMap()
            val finalFmlArgs = defaultFmlArgs + backendArgs

            finalFmlArgs.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    args.add("--fml.$key")
                    args.add(value)
                }
            }
        }
        return args
    }
}
