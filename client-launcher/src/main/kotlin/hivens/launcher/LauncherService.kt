package hivens.launcher

import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.api.model.ServerProfile
import hivens.core.data.FileManifest
import hivens.core.data.InstanceProfile
import hivens.core.data.SessionData
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.ModManager
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Locale
import java.util.stream.Collectors
import kotlin.concurrent.thread

enum class LauncherLogType { INFO, WARN, ERROR }

class LauncherService(
    private val manifestProcessor: IManifestProcessorService,
    private val profileManager: ProfileManager,
    private val javaManager: JavaManagerService
) : ILauncherService {

    private val log = LoggerFactory.getLogger(LauncherService::class.java)

    private val modManager = ModManager(manifestProcessor)
    private val envPreparer = EnvironmentPreparer()

    private val launchConfigs: Map<String, LaunchConfig> = buildLaunchConfigMap()

    private data class LaunchConfig(
        val mainClass: String,
        val tweakClass: String?,
        val assetIndex: String,
        val jvmArgs: List<String>,
        val nativesDir: String,
        val programArgs: List<String> = emptyList()
    )

    private enum class OS { WINDOWS, LINUX, MACOS, UNKNOWN }

    @Throws(IOException::class)
    fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        onLog: (String, LauncherLogType) -> Unit
    ): Process {
        val profile: InstanceProfile = profileManager.getProfile(serverProfile.assetDir)
        val version = serverProfile.version

        val config = launchConfigs[version]
            ?: launchConfigs.entries.find { version.startsWith(it.key) }?.value
            ?: throw IOException("Unsupported version: $version")

        var memory = if (profile.memoryMb > 0) profile.memoryMb else allocatedMemoryMB
        if (memory < 768) memory = 1024

        val javaExec: String = resolveJavaPath(profile, javaExecutablePath, version)

        onLog("Starting ${serverProfile.name} with Java: $javaExec, RAM: $memory", LauncherLogType.INFO)
        log.info("Starting {} with Java: {}, RAM: {}", serverProfile.name, javaExec, memory)

        val allMods = manifestProcessor.getOptionalModsForClient(serverProfile)
        val manifest = sessionData.fileManifest ?: FileManifest()
        modManager.syncMods(clientRootPath, profile, allMods, manifest, version)

        envPreparer.prepareNatives(clientRootPath, config.nativesDir, version)
        envPreparer.prepareAssets(clientRootPath, "assets-$version.zip")

        cleanUpGarbage(clientRootPath)

        val command = buildProcessCommand(
            javaExec, memory, clientRootPath, config,
            manifest, sessionData, serverProfile, profile
        )

        val pb = ProcessBuilder(command)
        pb.directory(clientRootPath.toFile())
        pb.redirectErrorStream(false)

        onLog("LAUNCH COMMAND: ${java.lang.String.join(" ", command)}", LauncherLogType.INFO)

        val process = pb.start()

        pipeOutput(process.inputStream, LauncherLogType.INFO, onLog)
        pipeOutput(process.errorStream, LauncherLogType.ERROR, onLog)

        return process
    }

    override fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process {
        return launchClientWithLogs(sessionData, serverProfile, clientRootPath, javaExecutablePath, allocatedMemoryMB) { _, _ -> }
    }

    private fun cleanUpGarbage(clientRoot: Path) {
        try {
            Files.list(clientRoot).use { rootStream ->
                rootStream.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("libraries") }
                    .forEach { libDir ->
                        Files.walk(libDir).use { walk ->
                            walk.filter { Files.isRegularFile(it) }
                                .filter {
                                    val name = it.toString().lowercase()
                                    !name.endsWith(".jar") && !name.endsWith(".zip")
                                }
                                .forEach {
                                    try { Files.deleteIfExists(it) } catch (_: Exception) {}
                                }
                        }
                    }
            }
        } catch (_: Exception) {}
    }

    private fun resolveJavaPath(profile: InstanceProfile, defaultPath: Path, version: String): String {
        if (!profile.javaPath.isNullOrEmpty()) return profile.javaPath!!
        try {
            val managedPath = javaManager.getJavaPath(version)
            if (Files.exists(managedPath)) return managedPath.toString()
        } catch (_: Exception) {}
        if (Files.exists(defaultPath)) return defaultPath.toString()
        return "java"
    }

    private fun buildProcessCommand(
        javaExec: String,
        memoryMB: Int,
        clientRoot: Path,
        config: LaunchConfig,
        manifest: FileManifest,
        session: SessionData,
        serverProfile: ServerProfile,
        userProfile: InstanceProfile
    ): List<String> {
        val args = ArrayList<String>()
        args.add(javaExec)
        args.add("-noverify")

        if (getPlatform() == OS.MACOS) {
            args.add("-XstartOnFirstThread")
            args.add("-Djava.awt.headless=false")
        }

        args.add("-Dminecraft.api.auth.host=http://www.smartycraft.ru/launcher/")
        args.add("-Dminecraft.api.account.host=http://www.smartycraft.ru/launcher/")
        args.add("-Dminecraft.api.session.host=http://www.smartycraft.ru/launcher/")
        args.add("-Dminecraft.launcher.brand=smartycraft")
        args.add("-Dminecraft.launcher.version=3.6.2")

        val nativesPath = clientRoot.resolve(config.nativesDir)
        args.add("-Djava.library.path=" + nativesPath.toAbsolutePath())

        val isNeoForge = config.assetIndex == "1.21.1"
        var modulePathString = ""

        val neoForgeModules = listOf(
            "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
            "org/ow2/asm/asm/9.7/asm-9.7.jar",
            "org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar",
            "org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar",
            "org/ow2/asm/asm-util/9.7/asm-util-9.7.jar",
            "org/ow2/asm/asm-analysis/9.7/asm-analysis-9.7.jar",
            "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
            "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar"
        )

        if (isNeoForge) {
            val libDir = clientRoot.resolve("libraries-1.21.1")
            val mpPaths = ArrayList<String>()
            for (subPath in neoForgeModules) {
                val file = libDir.resolve(subPath)
                if (Files.exists(file)) mpPaths.add(file.toAbsolutePath().toString())
            }
            modulePathString = java.lang.String.join(File.pathSeparator, mpPaths)

            args.add("-Djna.tmpdir=" + nativesPath.toAbsolutePath())
            args.add("-Dorg.lwjgl.system.SharedLibraryExtractPath=" + nativesPath.toAbsolutePath())
            args.add("-Dio.netty.native.workdir=" + nativesPath.toAbsolutePath())
            args.add("-DlibraryDirectory=" + libDir.toAbsolutePath())

            // Добавляем client-1.21.1... в игнор лист, на всякий случай, хотя exclusion из CP важнее
            val ignoreList = "securejarhandler-3.0.8.jar,asm-9.7.jar,asm-commons-9.7.jar,asm-tree-9.7.jar,asm-util-9.7.jar,asm-analysis-9.7.jar,bootstraplauncher-2.0.2.jar,JarJarFileSystems-0.4.1.jar,client-extra,neoforge-,neoforge-21.1.504.jar"
            args.add("-DignoreList=$ignoreList")
            args.add("-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar")
        }

        args.addAll(config.jvmArgs)
        if (!userProfile.jvmArgs.isNullOrEmpty()) {
            args.addAll(userProfile.jvmArgs!!.split(" "))
        }

        args.add("-Xms512M")
        args.add("-Xmx${memoryMB}M")

        if (isNeoForge && modulePathString.isNotEmpty()) {
            args.add("-p")
            args.add(modulePathString)
        }

        args.add("-cp")
        args.add(buildClasspath(clientRoot, manifest, if (isNeoForge) neoForgeModules else emptyList()))

        args.add(config.mainClass)
        args.addAll(config.programArgs)
        args.addAll(buildMinecraftArgs(session, serverProfile, clientRoot, config.assetIndex))

        if (config.tweakClass != null) {
            args.add("--tweakClass")
            args.add(config.tweakClass)
        }

        return args
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

    private fun buildClasspath(clientRoot: Path, manifest: FileManifest, excludedModules: List<String>): String {
        val currentOs = getPlatform()
        val allJars = HashSet<Path>()
        val modsDir = clientRoot.resolve("mods").toAbsolutePath()
        val libDir = clientRoot.resolve("libraries-1.21.1").toAbsolutePath()

        manifestProcessor.flattenManifest(manifest).keys.forEach { pathStr ->
            allJars.add(clientRoot.resolve(pathStr))
        }

        if (manifest.files.isEmpty()) {
            try {
                Files.walk(libDir).use { walk ->
                    walk.filter {
                        val name = it.toString().lowercase()
                        Files.isRegularFile(it) && name.endsWith(".jar") && !name.endsWith(".cache")
                    }.forEach { allJars.add(it) }
                }
            } catch (_: Exception) {}
        }

        val absoluteExcluded = excludedModules.map { libDir.resolve(it).toAbsolutePath() }

        return allJars.stream()
            .map { it.toAbsolutePath() }
            .filter { path ->
                val fileName = path.fileName.toString().lowercase()

                if (absoluteExcluded.contains(path)) return@filter false
                if (path.startsWith(modsDir)) return@filter false
                if (fileName.startsWith("neoforge-")) return@filter false
                if (fileName.startsWith("client-1.21.1")) return@filter false
                // -----------------------

                return@filter true
            }
            .map { it.toString() }
            .filter { isLibraryCompatibleWithOs(it, currentOs) }
            .sorted { p1, p2 ->
                val p1Lower = p1.lowercase()
                val p2Lower = p2.lowercase()
                val p1Boots = p1Lower.contains("bootstraplauncher") || p1Lower.contains("launchwrapper")
                val p2Boots = p2Lower.contains("bootstraplauncher") || p2Lower.contains("launchwrapper")
                if (p1Boots && !p2Boots) return@sorted -1
                if (!p1Boots && p2Boots) return@sorted 1
                p1.compareTo(p2)
            }
            .collect(Collectors.joining(File.pathSeparator))
    }

    private fun isLibraryCompatibleWithOs(path: String, os: OS): Boolean {
        val fileName = path.lowercase()
        if (!fileName.contains("natives")) return true
        return when (os) {
            OS.LINUX -> !fileName.contains("natives-windows") && !fileName.contains("natives-macos")
            OS.WINDOWS -> !fileName.contains("natives-linux") && !fileName.contains("natives-macos")
            OS.MACOS -> !fileName.contains("natives-windows") && !fileName.contains("natives-linux")
            else -> true
        }
    }

    private fun pipeOutput(stream: InputStream, type: LauncherLogType, onLog: (String, LauncherLogType) -> Unit) {
        val reader = BufferedReader(InputStreamReader(stream))
        thread(isDaemon = true) {
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line ?: continue
                    val finalType = when {
                        type == LauncherLogType.ERROR -> LauncherLogType.ERROR
                        text.contains("WARN", ignoreCase = true) -> LauncherLogType.WARN
                        text.contains("ERROR", ignoreCase = true) || text.contains("Exception", ignoreCase = true) -> LauncherLogType.ERROR
                        else -> LauncherLogType.INFO
                    }
                    onLog(text, finalType)
                    if (finalType == LauncherLogType.ERROR) System.err.println(text) else println(text)
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildLaunchConfigMap(): Map<String, LaunchConfig> {
        return mapOf(
            "1.7.10" to LaunchConfig(
                mainClass = "net.minecraft.launchwrapper.Launch",
                tweakClass = "cpw.mods.fml.common.launcher.FMLTweaker",
                assetIndex = "1.7.10",
                jvmArgs = listOf(
                    "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
                    "-Dfml.ignoreInvalidMinecraftCertificates=true",
                    "-Dfml.ignorePatchDiscrepancies=true"
                ),
                nativesDir = "bin/natives-1.7.10"
            ),
            "1.12.2" to LaunchConfig(
                mainClass = "net.minecraft.launchwrapper.Launch",
                tweakClass = "net.minecraftforge.fml.common.launcher.FMLTweaker",
                assetIndex = "1.12.2",
                jvmArgs = listOf(
                    "-XX:+UseG1GC",
                    "-XX:+UnlockExperimentalVMOptions",
                    "-XX:G1NewSizePercent=20",
                    "-XX:G1ReservePercent=20",
                    "-XX:MaxGCPauseMillis=50",
                    "-XX:G1HeapRegionSize=32M",
                    "-Dfml.ignoreInvalidMinecraftCertificates=true",
                    "-Dfml.ignorePatchDiscrepancies=true"
                ),
                nativesDir = "bin/natives-1.12.2"
            ),
            "1.21.1" to LaunchConfig(
                mainClass = "cpw.mods.bootstraplauncher.BootstrapLauncher",
                tweakClass = null,
                assetIndex = "1.21.1",
                jvmArgs = listOf(
                    "-XX:+UseG1GC",
                    "-XX:+UnlockExperimentalVMOptions",
                    "-XX:G1NewSizePercent=20",
                    "-XX:G1ReservePercent=20",
                    "-XX:MaxGCPauseMillis=50",
                    "-XX:G1HeapRegionSize=32M",

                    "--add-modules=ALL-MODULE-PATH",
                    "--add-reads=org.openjdk.nashorn=ALL-UNNAMED",

                    "--add-modules=jdk.naming.dns",
                    "--add-exports=jdk.naming.dns/com.sun.jndi.dns=java.naming",
                    "--add-opens=java.base/java.util.jar=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.nio=ALL-UNNAMED",
                    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                    "--add-opens=java.base/java.time=ALL-UNNAMED",

                    "--add-opens=java.base/java.util.jar=cpw.mods.securejarhandler",
                    "--add-opens=java.base/java.lang.invoke=cpw.mods.securejarhandler",
                    "--add-exports=java.base/sun.security.util=cpw.mods.securejarhandler",

                    "-Djava.awt.headless=false",
                    "-Djava.net.preferIPv6Addresses=system"
                ),
                nativesDir = "bin/natives-1.21.1",
                programArgs = listOf("--launchTarget", "forgeclient")
            )
        )
    }

    private fun getPlatform(): OS {
        val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
        return when {
            osName.contains("win") -> OS.WINDOWS
            osName.contains("mac") -> OS.MACOS
            osName.contains("nix") || osName.contains("nux") || osName.contains("aix") -> OS.LINUX
            else -> OS.UNKNOWN
        }
    }
}
