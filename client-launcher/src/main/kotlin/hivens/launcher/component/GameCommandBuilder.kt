package hivens.launcher.component

import hivens.config.Branding
import hivens.config.Protocol
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import hivens.launcher.network.ServerProtocolConfig
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
     * Collects a list of arguments for [ProcessBuilder].
     *
     * @return An ordered list of strings, ready to be passed to the OS process.
     */
    fun build(
        javaExec: String,
        memoryMB: Int,
        clientRoot: Path,
        serverProfile: ServerProfile,
        session: SessionData,
        userProfile: InstanceProfile,
        classpath: String
    ): List<String> {
        val version = serverProfile.version
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
            val ignoreList = serverProfile.ignoreModulesList?.takeIf { it.isNotBlank() } ?: defaultIgnore
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

        if (!userProfile.jvmArgs.isNullOrEmpty()) {
            args.addAll(userProfile.jvmArgs!!.split(" "))
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
        args.addAll(buildMinecraftArgs(session, serverProfile, clientRoot, config.assetIndex, isModernEnvironment))

        if (config.tweakClass != null) {
            args.add("--tweakClass")
            args.add(config.tweakClass)
        }

        return args
    }

    private fun getConfig(version: String): VersionConfig {
        return configs[version]
            ?: configs.entries.find { version.startsWith(it.key) }?.value
            ?: throw IllegalArgumentException("Unsupported client version: $version")
    }

    private fun buildMinecraftArgs(
        session: SessionData,
        profile: ServerProfile,
        root: Path,
        assetIndex: String,
        isModernEnvironment: Boolean
    ): List<String> {
        val args = ArrayList<String>()
        args.add("--username"); args.add(session.playerName)
        args.add("--version"); args.add("Forge ${profile.version}")
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
            val backendArgs = profile.neoForgeArgs ?: emptyMap()
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
