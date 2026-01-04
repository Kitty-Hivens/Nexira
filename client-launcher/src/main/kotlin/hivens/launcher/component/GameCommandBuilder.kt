package hivens.launcher.component

import hivens.config.AppConfig
import hivens.core.api.model.ServerProfile
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Фабрика командной строки процесса (Process Command Factory).
 */
internal class GameCommandBuilder {
    private val logger = LoggerFactory.getLogger(GameCommandBuilder::class.java)

    /**
     * Immutable-конфигурация версии.
     */
    private data class VersionConfig(
        val mainClass: String,
        val tweakClass: String?,
        val assetIndex: String,
        val jvmArgs: List<String>,
        val nativesDir: String,
        val programArgs: List<String> = emptyList()
    )

    // Registry конфигураций версий
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
            jvmArgs = listOf("-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=20", "-XX:MaxGCPauseMillis=50", "-Dfml.ignoreInvalidMinecraftCertificates=true"),
            nativesDir = "bin/natives-1.12.2"
        ),
        "1.21.1" to VersionConfig(
            mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
            tweakClass = null,
            assetIndex = "1.21.1",
            jvmArgs = listOf(
                // GC Optimization
                "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:G1NewSizePercent=20", "-XX:MaxGCPauseMillis=50",
                // Java 9+ Modules Export
                "--add-modules=ALL-MODULE-PATH", "--add-reads=org.openjdk.nashorn=ALL-UNNAMED",
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
     * Возвращает путь к директории нативных библиотек для указанной версии.
     */
    fun getNativesDir(version: String): String {
        return getConfig(version).nativesDir
    }

    /**
     * Собирает список аргументов для [ProcessBuilder].
     *
     * @return Упорядоченный список строк, готовый к передаче в процесс ОС.
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

        // 3. System Properties (Launcher Identity)
        args.add("-Dminecraft.api.auth.host=${AppConfig.BASE_URL}/launcher/")
        args.add("-Dminecraft.api.account.host=${AppConfig.BASE_URL}/launcher/")
        args.add("-Dminecraft.api.session.host=${AppConfig.BASE_URL}/launcher/")
        args.add("-Dminecraft.launcher.brand=${AppConfig.BRANDING_NAME}")
        args.add("-Dminecraft.launcher.version=${AppConfig.LAUNCHER_VERSION}")

        // 4. Natives Configuration
        val nativesPath = clientRoot.resolve(config.nativesDir)
        args.add("-Djava.library.path=" + nativesPath.toAbsolutePath())

        // 5. NeoForge Environment (1.21+)
        if (config.assetIndex == "1.21.1") {
            val libDirStandard = clientRoot.resolve("libraries")
            val libDirCustom = clientRoot.resolve("libraries-1.21.1")
            val libDir = if (libDirCustom.resolve("cpw").toFile().exists()) libDirCustom else libDirStandard

            args.add("-Djna.tmpdir=" + nativesPath.toAbsolutePath())
            args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesPath.toAbsolutePath())
            args.add("-Dio.netty.native.workdir=" + nativesPath.toAbsolutePath())
            args.add("-DlibraryDirectory=" + libDir.toAbsolutePath())

            // FIX: Добавил "client" в начало списка игнорируемых модулей
            val ignoreList = "client,securejarhandler-3.0.8.jar,asm-9.7.jar,asm-commons-9.7.jar,asm-tree-9.7.jar,asm-util-9.7.jar,asm-analysis-9.7.jar,bootstraplauncher-2.0.2.jar,JarJarFileSystems-0.4.1.jar,client-extra,neoforge-,neoforge-21.1.504.jar"
            args.add("-DignoreList=$ignoreList")
            args.add("-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar")
        }

        // 6. Memory Allocation
        args.addAll(config.jvmArgs)
        if (!userProfile.jvmArgs.isNullOrEmpty()) {
            args.addAll(userProfile.jvmArgs!!.split(" "))
        }
        args.add("-Xms512M")
        args.add("-Xmx${memoryMB}M")

        // 7. Java 9+ Module Path (NeoForge)
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

        // 8. Classpath & Entry Point
        args.add("-cp")
        args.add(classpath)
        args.add(config.mainClass)
        args.addAll(config.programArgs)

        // 9. Game Arguments
        args.addAll(buildMinecraftArgs(session, serverProfile, clientRoot, config.assetIndex))

        if (config.tweakClass != null) {
            args.add("--tweakClass")
            args.add(config.tweakClass)
        }

        return args
    }

    private fun getNeoForgeModules(): List<String> = listOf(
        "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
        "org/ow2/asm/asm/9.7/asm-9.7.jar",
        "org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar",
        "org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar",
        "org/ow2/asm/asm-util/9.7/asm-util-9.7.jar",
        "org/ow2/asm/asm-analysis/9.7/asm-analysis-9.7.jar",
        "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
        "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar"
    )

    private fun getConfig(version: String): VersionConfig {
        return configs[version]
            ?: configs.entries.find { version.startsWith(it.key) }?.value
            ?: throw IllegalArgumentException("Неподдерживаемая версия клиента: $version")
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
            args.add("--fml.neoForgeVersion"); args.add("21.1.504")
            args.add("--fml.fmlVersion"); args.add("4.0.34")
            args.add("--fml.mcVersion"); args.add("1.21.1")
            args.add("--fml.neoFormVersion"); args.add("20240808.144430")
        }
        return args
    }
}
