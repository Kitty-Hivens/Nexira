package hivens.launcher.component

import hivens.config.Branding
import hivens.config.Protocol
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.runtime.loader.ResolvedRuntime
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

internal class GameCommandBuilder(
    private val protocolConfig: ServerProtocolConfig = ServerProtocolConfig(),
) {
    private val logger = LoggerFactory.getLogger(GameCommandBuilder::class.java)
    private val neoForgeDetector = NeoForgeVersionDetector()

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
        classpath: String
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
    )

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
        classpath: String
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
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            args.add("-XstartOnFirstThread") // Critical for LWJGL on macOS
            args.add("-Djava.awt.headless=false")
        }

        // 3. System Properties (Launcher Identity & Custom Authlib)
        args.add("-Dminecraft.api.auth.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.account.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.session.host=${protocolConfig.baseUrl}/launcher/")
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

        // 6. Memory Allocation & Custom JVM Args
        val (gcArgs, systemArgs) = config.jvmArgs.partition { it.startsWith("-XX:") }
        args.addAll(systemArgs)

        // Java 21+ Vector API optimization for faster data structures in mods (e.g., JEI, Ars Nouveau)
        if (isModernEnvironment) {
            args.add("--add-modules=jdk.incubator.vector")
        }

        if (!target.jvmArgsOverride.isNullOrBlank()) {
            args.addAll(target.jvmArgsOverride.trim().split(Regex("\\s+")))
        } else {
            args.addAll(gcArgs)
        }

        args.add("-Xms512M")
        args.add("-Xmx${memoryMB}M")

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
    ): List<String> {
        val args = ArrayList<String>()
        args.add(javaExec)

        // BootstrapLauncher (1.17+/NeoForge) carries a JPMS module path; the
        // modern `arguments` template (anything with a ${placeholder}) needs
        // substitution and a self-built classpath -- modlauncher 1.13-1.16 has
        // the template but no module path.
        val usesModulePath = runtime.mainClass.contains("bootstraplauncher", ignoreCase = true)
        val usesModernArgs = usesModulePath || runtime.jvmArgs.any { it.contains("\${") }
        if (javaMajor <= 8) args.add("-noverify")
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            args.add("-XstartOnFirstThread")
            args.add("-Djava.awt.headless=false")
        }

        // Launcher identity + authlib redirect. Reaching the menu does not need
        // it; joining an SC-derived server does (the redirect points auth at the
        // configured host, same as the SC path).
        args.add("-Dminecraft.api.auth.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.account.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.api.session.host=${protocolConfig.baseUrl}/launcher/")
        args.add("-Dminecraft.launcher.brand=${Branding.UPSTREAM_NAME}")
        args.add("-Dminecraft.launcher.version=${Protocol.MIMIC_LAUNCHER_VERSION}")

        val nativesPath = gameDir.resolve(nativesDirName).toAbsolutePath()
        args.add("-Djava.library.path=$nativesPath")
        args.add("-Dfml.ignoreInvalidMinecraftCertificates=true")

        if (!jvmArgsOverride.isNullOrBlank()) {
            args.addAll(jvmArgsOverride.trim().split(Regex("\\s+")))
        }
        if (usesModernArgs) {
            args.addAll(modernJvmArgs(runtime, gameDir, sharedAssetsDir, sharedLibrariesDir, nativesPath, versionLabel))
            // Java 9+ Vector API speeds up some mods (JEI, Ars Nouveau); only
            // meaningful where the module path is in play.
            if (usesModulePath) args.add("--add-modules=jdk.incubator.vector")
        } else {
            args.addAll(runtime.jvmArgs)
        }
        args.add("-Xms512M")
        args.add("-Xmx${memoryMB}M")

        args.add("-cp")
        args.add(if (usesModernArgs) modernClasspath(runtime) else packClasspath(runtime))
        args.add(runtime.mainClass)

        args.add("--username"); args.add(session.playerName)
        args.add("--version"); args.add(versionLabel)
        args.add("--gameDir"); args.add(gameDir.toAbsolutePath().toString())
        args.add("--assetsDir"); args.add(sharedAssetsDir.toAbsolutePath().toString())
        args.add("--assetIndex"); args.add(runtime.assetIndexId)
        args.add("--uuid"); args.add(session.uuid)
        args.add("--accessToken"); args.add(session.accessToken)
        args.add("--userProperties"); args.add("{}")
        args.add("--userType"); args.add("mojang")
        args.addAll(runtime.gameArgs)

        return args
    }

    /**
     * Ordered `-cp` for a pack: bootstrap jars (launchwrapper / asm /
     * bootstraplauncher) first, then the client jar, then the rest -- mirrors
     * the proven legacy Forge classpath ordering. Mods are NOT here; the loader
     * scans the per-instance mods/ dir.
     */
    private fun packClasspath(runtime: ResolvedRuntime): String {
        val libPaths = runtime.libraries.map { it.path }
        val (boot, rest) = libPaths.partition { p ->
            val n = p.fileName.toString().lowercase()
            n.contains("launchwrapper") || n.contains("asm") || n.contains("bootstraplauncher")
        }
        // listOf(clientJar), NOT `+ clientJar`: a Path is Iterable<Path> over its
        // name segments, so `List<Path> + Path` would spread the client jar into
        // its path components instead of appending it as one classpath entry.
        return (boot + listOf(runtime.clientJar) + rest)
            .joinToString(File.pathSeparator) { it.toAbsolutePath().toString() }
    }

    /**
     * Full `-cp` for a modern (templated) launch: the resolved libraries only,
     * NOT the vanilla client jar. Modern Forge/NeoForge load minecraft from the
     * installer's processor output (the slim/srg client) through FML's own path
     * locator; putting the vanilla client on `-cp` too yields a second module
     * named `minecraft` and "reads more than one module named minecraft". Boot
     * modules stay on `-cp` -- the version json's `-DignoreList` tells
     * BootstrapLauncher which entries to keep flat versus promote to modules,
     * mirroring the official launcher.
     */
    private fun modernClasspath(runtime: ResolvedRuntime): String =
        runtime.libraries.joinToString(File.pathSeparator) { it.path.toAbsolutePath().toString() }

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
        args.add("--uuid"); args.add(session.uuid)
        args.add("--accessToken"); args.add(session.accessToken)
        args.add("--userProperties"); args.add("{}")
        args.add("--userType"); args.add("mojang")

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
