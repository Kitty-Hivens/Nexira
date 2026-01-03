package hivens.launcher

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipInputStream

class JavaManagerService(
    baseDir: Path,
    private val httpClient: HttpClient
) {
    private val log = LoggerFactory.getLogger(JavaManagerService::class.java)
    private val runtimesDir: Path = baseDir.resolve("runtimes")

    /**
     * Возвращает путь к исполняемому файлу Java.
     * Если нужной версии нет — скачивает её.
     */
    suspend fun getJavaPath(version: String): Path = withContext(Dispatchers.IO) {
        val javaVersion = detectJavaVersion(version)
        val os = getOsName()
        val arch = getArchName()

        val folderName = "java-$javaVersion-$os-$arch"
        val targetDir = runtimesDir.resolve(folderName)

        findJavaExecutable(targetDir)?.let { return@withContext it }

        log.info("Java {} ({}/{}) не найдена локально. Начинаем загрузку...", javaVersion, os, arch)
        downloadAndUnpack(javaVersion, targetDir)

        val executable = findJavaExecutable(targetDir)
            ?: throw IOException("Java была скачана, но исполняемый файл не найден!")

        if (os != "win") {
            setExecutablePermissions(executable)
        }

        return@withContext executable
    }

    private fun detectJavaVersion(mcVersion: String): Int {
        return when {
            mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6") -> 21
            mcVersion.startsWith("1.17") || mcVersion.startsWith("1.18") || mcVersion.startsWith("1.19") || mcVersion.startsWith("1.20") -> 17
            else -> 8
        }
    }

    private suspend fun downloadAndUnpack(version: Int, targetDir: Path) {
        val url = getDownloadUrl(version)
            ?: throw IOException("Нет сборки Java для этой системы (${getOsName()} ${getArchName()})")

        val isZip = url.endsWith(".zip")
        val archive = Files.createTempFile("java_pkg", if (isZip) ".zip" else ".tar.gz")

        try {
            log.info("Скачивание Java: $url")

            httpClient.prepareGet(url).execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    throw IOException("Ошибка загрузки: ${httpResponse.status}")
                }
                val channel = httpResponse.bodyAsChannel()
                val fileStream = FileOutputStream(archive.toFile())
                channel.copyTo(fileStream)
            }

            log.info("Распаковка в $targetDir")
            deleteDirectoryRecursively(targetDir)
            Files.createDirectories(targetDir)

            if (isZip) {
                unzip(archive.toFile(), targetDir)
            } else {
                untargz(archive.toFile(), targetDir)
            }
        } finally {
            Files.deleteIfExists(archive)
        }
    }
    private fun getOsName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "win"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> "linux"
            os.contains("mac") -> "mac"
            else -> "unknown"
        }
    }

    private fun getArchName(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("64") -> "x64"
            arch.contains("86") || arch.contains("32") -> "x32"
            else -> "x64" // Дефолт для странных случаев
        }
    }

    private fun findJavaExecutable(dir: Path): Path? {
        if (!Files.exists(dir)) return null

        return try {
            Files.walk(dir).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    (name == "java" || name == "java.exe") && Files.isExecutable(p)
                }.findFirst().orElse(null)
            }
        } catch (_: Exception) { null }
    }

    private fun deleteDirectoryRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Снимаем атрибуты Read-Only перед удалением
                try {
                    Files.setAttribute(file, "dos:readonly", false)
                } catch (_: Exception) { /* Игнорируем на не-Windows */ }

                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun setExecutablePermissions(path: Path) {
        try {
            // Работает на Unix-системах (Linux/Mac)
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            // Добавляем rwx-r-x-r-x
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // На Windows игнорируем
        } catch (e: Exception) {
            log.warn("Не удалось установить права на исполнение для $path: ${e.message}")
        }
    }

    private fun unzip(zip: File, dest: Path) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                // Защита от Zip Slip уязвимости
                val resolvedPath = dest.resolve(entry.name).normalize()
                if (!resolvedPath.startsWith(dest)) {
                    throw IOException("Zip entry is outside of the target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    Files.createDirectories(resolvedPath)
                } else {
                    Files.createDirectories(resolvedPath.parent)
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun untargz(tar: File, dest: Path) {
        FileInputStream(tar).use { fi ->
            BufferedInputStream(fi).use { bi ->
                GzipCompressorInputStream(bi).use { gzi ->
                    TarArchiveInputStream(gzi).use { tai ->
                        var entry = tai.nextEntry
                        while (entry != null) {
                            val resolvedPath = dest.resolve(entry.name).normalize()
                            if (!resolvedPath.startsWith(dest)) {
                                throw IOException("Tar entry is outside of the target dir: ${entry.name}")
                            }

                            if (entry.isDirectory) {
                                Files.createDirectories(resolvedPath)
                            } else {
                                Files.createDirectories(resolvedPath.parent)
                                Files.copy(tai, resolvedPath, StandardCopyOption.REPLACE_EXISTING)
                                // Восстановление прав на исполнение из архива (для Linux/Mac)
                                if (getOsName() != "win" && (entry.mode and 0b001_000_001) != 0) { // Проверяем бит execute
                                    setExecutablePermissions(resolvedPath)
                                }
                            }
                            entry = tai.nextEntry
                        }
                    }
                }
            }
        }
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
}
