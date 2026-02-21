package hivens.launcher.component

import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Process Command Factory.
 */
internal class GameCommandBuilder {
    private val logger = LoggerFactory.getLogger(GameCommandBuilder::class.java)

    /**
     * Immutable version configuration.
     */
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
        val args = ArrayList<String>()

        // 1. JVM Binary
        args.add(javaExec)
        args.add("-noverify")

        // 2. OS Specific Flags
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            args.add("-XstartOnFirstThread") // Critical for LWJGL on macOS
            args.add("-Djava.awt.headless=false")
        }

        // 3. Natives Configuration
        val nativesPath = clientRoot.resolve(config.nativesDir)
        args.add("-Djava.library.path=" + nativesPath.toAbsolutePath())

        // 4. NeoForge Environment (1.21+)
        if (config.assetIndex == "1.21.1") {
            val libDirStandard = clientRoot.resolve("libraries")
            val libDirCustom = clientRoot.resolve("libraries-1.21.1")
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

        // 5. Memory Allocation & Custom JVM Args
        val (gcArgs, systemArgs) = config.jvmArgs.partition { it.startsWith("-XX:") }
        args.addAll(systemArgs)

        if (!userProfile.jvmArgs.isNullOrEmpty()) {
            args.addAll(userProfile.jvmArgs!!.split(" "))
        } else {
            args.addAll(gcArgs)
        }

        args.add("-Xms512M")
        args.add("-Xmx${memoryMB}M")

        // 6. Java 9+ Module Path (NeoForge)
        if (config.assetIndex == "1.21.1") {
            val modules = getNeoForgeModules()
            val libDirStandard = clientRoot.resolve("libraries")
            val libDirCustom = clientRoot.resolve("libraries-1.21.1")
            val libDir = if (libDirCustom.resolve("cpw").toFile().exists()) libDirCustom else libDirStandard

            val validModules = modules.map { libDir.resolve(it) }
                .filter {
                    val exists = it.toFile().exists()
                    if (!exists) logger.warn("Module missing: $it")
                    exists
                }
                .map { it.toAbsolutePath().toString() }

            if (validModules.isNotEmpty()) {
                args.add("-p")
                args.add(java.lang.String.join(File.pathSeparator, validModules))
            } else {
                logger.error("CRITICAL: No NeoForge modules found in $libDir!")
            }
        }

        // 7. Classpath & Entry Point
        args.add("-cp")
        args.add(classpath)
        args.add(config.mainClass)
        args.addAll(config.programArgs)

        // 8. Game Arguments
        args.addAll(buildMinecraftArgs(session, serverProfile, clientRoot, config.assetIndex))

        if (config.tweakClass != null) {
            args.add("--tweakClass")
            args.add(config.tweakClass)
        }

        return args
    }

    private fun getNeoForgeModules(): List<String> = listOf(
        // Core Launch Infrastructure
        "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
        "cpw/mods/modlauncher/11.0.5/modlauncher-11.0.5.jar",
        "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
        "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar",
        "net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar",

        // NeoForge System Modules
        "net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar",
        "net/neoforged/fancymodloader/earlydisplay/4.0.42/earlydisplay-4.0.42.jar",
        "net/neoforged/bus/8.0.5/bus-8.0.5.jar",
        "net/neoforged/coremods/7.0.3/coremods-7.0.3.jar",
        "net/neoforged/srgutils/1.0.0/srgutils-1.0.0.jar",

        // Transformers & Parsers
        "net/neoforged/accesstransformers/10.0.1/accesstransformers-10.0.1.jar",
        "net/neoforged/accesstransformers/at-modlauncher/10.0.1/at-modlauncher-10.0.1.jar",
        "org/antlr/antlr4-runtime/4.13.1/antlr4-runtime-4.13.1.jar",

        // Essential Dependencies
        "net/jodah/typetools/0.6.3/typetools-0.6.3.jar",
        "com/electronwill/night-config/core/3.8.3/core-3.8.3.jar",
        "com/electronwill/night-config/toml/3.8.3/toml-3.8.3.jar",
        "com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar",
        "it/unimi/dsi/fastutil/8.5.12/fastutil-8.5.12.jar",
        "net/fabricmc/sponge-mixin/0.15.2+mixin.0.8.7/sponge-mixin-0.15.2+mixin.0.8.7.jar",

        // Utils, Logging & Console
        "org/apache/logging/log4j/log4j-api/2.22.1/log4j-api-2.22.1.jar",
        "org/apache/logging/log4j/log4j-core/2.22.1/log4j-core-2.22.1.jar",
        "org/apache/logging/log4j/log4j-slf4j2-impl/2.22.1/log4j-slf4j2-impl-2.22.1.jar",
        "org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar",
        "com/google/code/gson/gson/2.10.1/gson-2.10.1.jar",
        "net/minecrell/terminalconsoleappender/1.3.0/terminalconsoleappender-1.3.0.jar",
        "org/jline/jline-reader/3.20.0/jline-reader-3.20.0.jar",
        "org/jline/jline-terminal/3.20.0/jline-terminal-3.20.0.jar",

        // ASM
        "org/ow2/asm/asm/9.8/asm-9.8.jar",
        "org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar",
        "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar",
        "org/ow2/asm/asm-util/9.8/asm-util-9.8.jar",
        "org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar"
    )

    private fun getConfig(version: String): VersionConfig {
        return configs[version]
            ?: configs.entries.find { version.startsWith(it.key) }?.value
            ?: throw IllegalArgumentException("Unsupported client version: $version")
    }

    private fun buildMinecraftArgs(
        session: SessionData,
        profile: ServerProfile,
        root: Path,
        assetIndex: String
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

        if (assetIndex == "1.21.1") {
            val forgeVersion = profile.neoForgeArgs?.get("neoForgeVersion")?.takeIf { it.isNotBlank() } ?: "21.1.505"
            val fmlVersion = profile.neoForgeArgs?.get("fmlVersion")?.takeIf { it.isNotBlank() } ?: "4.0.42"
            val mcVersion = profile.neoForgeArgs?.get("mcVersion")?.takeIf { it.isNotBlank() } ?: "1.21.1"
            val neoFormVersion = profile.neoForgeArgs?.get("neoFormVersion")?.takeIf { it.isNotBlank() } ?: "20240808.144430"

            args.add("--fml.neoForgeVersion"); args.add(forgeVersion)
            args.add("--fml.fmlVersion"); args.add(fmlVersion)
            args.add("--fml.mcVersion"); args.add(mcVersion)
            args.add("--fml.neoFormVersion"); args.add(neoFormVersion)
        }
        return args
    }
}
