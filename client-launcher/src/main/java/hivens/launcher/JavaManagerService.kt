package hivens.launcher

import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.zip.ZipInputStream

class JavaManagerService(
    baseDir: Path,
    private val httpClient: OkHttpClient
) {
    private val log = LoggerFactory.getLogger(JavaManagerService::class.java)
    private val runtimesDir: Path = baseDir.resolve("runtimes")

    @Throws(IOException::class)
    fun getJavaPath(version: String): Path {
        val javaVersion = detectJavaVersion(version)
        // Формируем уникальное имя папки
        val folderName = "java-$javaVersion-${getOsName()}-${getArchName()}"
        val targetDir = runtimesDir.resolve(folderName)

        var executable = findJavaExecutable(targetDir)
        if (executable != null) return executable

        log.info("Java {} ({}/{}) not found locally. Downloading Liberica...", javaVersion, getOsName(), getArchName())
        downloadAndUnpack(javaVersion, targetDir)

        executable = findJavaExecutable(targetDir) 
            ?: throw IOException("Java downloaded but executable not found!")

        if (!SystemUtils.IS_OS_WINDOWS) {
            executable.toFile().setExecutable(true)
        }

        return executable
    }

    private fun detectJavaVersion(mcVersion: String): Int {
        return when {
            mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6") -> 21
            mcVersion.startsWith("1.17") || mcVersion.startsWith("1.18") || mcVersion.startsWith("1.19") || mcVersion.startsWith("1.20") -> 17
            else -> 8 // Default for 1.7.10 - 1.16.5. TODO: fix 1.7.10. lol.
        }
    }

    @Throws(IOException::class)
    private fun downloadAndUnpack(version: Int, targetDir: Path) {
        val url = getDownloadUrl(version)
            ?: throw IOException("No Java build available for this OS/Arch combination (${getOsName()} ${getArchName()})")

        val isZip = url.endsWith(".zip")
        val archive = Files.createTempFile("java_pkg", if (isZip) ".zip" else ".tar.gz").toFile()

        log.info("Downloading from: {}", url)
        val request = Request.Builder().url(url).build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            
            FileOutputStream(archive).use { fos ->
                fos.write(body.bytes())
            }
        }

        log.info("Unpacking to {}", targetDir)
        if (Files.exists(targetDir)) {
            FileUtils.deleteDirectory(targetDir.toFile())
        }
        Files.createDirectories(targetDir)

        if (isZip) {
            unzip(archive, targetDir)
        } else {
            untargz(archive, targetDir)
        }

        archive.delete()
    }

    private fun getDownloadUrl(version: Int): String? {
        val os = getOsName()
        val arch = getArchName()

        return when (version) {
            8 -> when {
                os == "win" && arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-windows-amd64-full.zip"
                os == "win" && arch == "x32" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-windows-i586.zip"
                os == "linux" && arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-linux-amd64-full.tar.gz"
                os == "mac" && arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-macos-amd64-full.tar.gz"
                os == "mac" && arch == "arm64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-macos-aarch64.tar.gz"
                else -> null
            }
            17 -> when {
                os == "win" && arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-windows-amd64-full.zip"
                os == "win" && arch == "x32" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-windows-i586-full.zip"
                os == "linux" && arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64-full.tar.gz"
                os == "mac" && arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-macos-amd64-full.tar.gz"
                os == "mac" && arch == "arm64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            21 -> when {
                os == "win" && arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-windows-amd64-full.zip"
                os == "linux" && arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-linux-amd64-full.tar.gz"
                os == "mac" && arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-macos-amd64-full.tar.gz"
                os == "mac" && arch == "arm64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            else -> null
        }
    }

    private fun getOsName(): String {
        return when {
            SystemUtils.IS_OS_WINDOWS -> "win"
            SystemUtils.IS_OS_LINUX -> "linux"
            SystemUtils.IS_OS_MAC -> "mac"
            else -> "unknown"
        }
    }

    private fun getArchName(): String {
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("64") -> "x64"
            arch.contains("86") || arch.contains("32") -> "x32"
            else -> "x64"
        }
    }

    private fun findJavaExecutable(dir: Path): Path? {
        if (!Files.exists(dir)) return null
        return try {
            Files.walk(dir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    name == "java" || name == "java.exe"
                }.findFirst().orElse(null)
            }
        } catch (e: IOException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun unzip(zip: File, dest: Path) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val p = dest.resolve(entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(p)
                } else {
                    Files.createDirectories(p.parent)
                    Files.copy(zis, p, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zis.nextEntry
            }
        }
    }

    @Throws(IOException::class)
    private fun untargz(tar: File, dest: Path) {
        FileInputStream(tar).use { fi ->
            BufferedInputStream(fi).use { bi ->
                GzipCompressorInputStream(bi).use { gzi ->
                    TarArchiveInputStream(gzi).use { tai ->
                        var entry = tai.nextEntry
                        while (entry != null) {
                            val p = dest.resolve(entry.name)
                            if (entry.isDirectory) {
                                Files.createDirectories(p)
                            } else {
                                Files.createDirectories(p.parent)
                                Files.copy(tai, p, StandardCopyOption.REPLACE_EXISTING)
                                if (!SystemUtils.IS_OS_WINDOWS && (entry.mode and 73) != 0) {
                                    p.toFile().setExecutable(true)
                                }
                            }
                            entry = tai.nextEntry
                        }
                    }
                }
            }
        }
    }
}
