package hivens.launcher

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

class JavaManagerService(
    baseDir: Path,
    private val clientProvider: HttpClientProvider
) : IJavaManager {
    private val log = LoggerFactory.getLogger(JavaManagerService::class.java)
    private val httpClient get() = clientProvider.current
    private val runtimesDir: Path = baseDir.resolve("runtimes")

    /**
     * Returns the path to the Java executable.
     * If the required version is not available, it downloads it.
     */
    override suspend fun getJavaPath(version: String): Path = withContext(Dispatchers.IO) {
        val javaVersion = detectJavaVersion(version)
        val os = getOsName()
        val arch = getArchName()

        val folderName = "java-$javaVersion-$os-$arch"
        val targetDir = runtimesDir.resolve(folderName)

        findJavaExecutable(targetDir)?.let { return@withContext it }

        log.info("Java {} ({}/{}) was not found locally. We are starting to download...", javaVersion, os, arch)
        downloadAndUnpack(javaVersion, targetDir)

        val executable = findJavaExecutable(targetDir)
            ?: throw IOException("Java was downloaded, but the executable file was not found!")

        if (os != "win") {
            setExecutablePermissions(executable)
        }

        return@withContext executable
    }

    internal fun detectJavaVersion(mcVersion: String): Int {
        return when {
            mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6") -> 21
            mcVersion.startsWith("1.17") || mcVersion.startsWith("1.18") || mcVersion.startsWith("1.19") || mcVersion.startsWith("1.20") -> 17
            else -> 8
        }
    }

    private suspend fun downloadAndUnpack(version: Int, targetDir: Path) {
        val url = getDownloadUrl(version)
            ?: throw IOException("There is no Java build for this system (${getOsName()} ${getArchName()})")

        val isZip = url.endsWith(".zip")
        val archive = Files.createTempFile("java_pkg", if (isZip) ".zip" else ".tar.gz")

        try {
            log.info("Download Java: $url")

            httpClient.prepareGet(url).execute { httpResponse ->
                if (!httpResponse.status.isSuccess()) {
                    throw IOException("Loading error: ${httpResponse.status}")
                }
                val channel = httpResponse.bodyAsChannel()
                FileOutputStream(archive.toFile()).use { fileStream ->
                    channel.copyTo(fileStream)
                }
            }

            log.info("Unpacking to $targetDir")
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
    internal fun getOsName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "win"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> "linux"
            os.contains("mac") -> "mac"
            else -> "unknown"
        }
    }

    internal fun getArchName(): String {
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            arch.contains("64") -> "x64"
            arch.contains("86") || arch.contains("32") -> "x32"
            else -> "x64" // Default for strange cases
        }
    }

    internal fun findJavaExecutable(dir: Path): Path? {
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
                // Remove Read-Only attributes before deleting
                try {
                    Files.setAttribute(file, "dos:readonly", false)
                } catch (_: Exception) { /* Ignorable on non-Windows */ }

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
            // Works on Unix systems (Linux/Mac)
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            // Add rwx-r-x-r-x
            permissions.add(PosixFilePermission.OWNER_EXECUTE)
            permissions.add(PosixFilePermission.GROUP_EXECUTE)
            permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Ignorable on Windows
        } catch (e: Exception) {
            log.warn("Failed to set execution rights for $path: ${e.message}")
        }
    }

    internal fun unzip(zip: File, dest: Path) {
        // ZipFile (random-access central-directory reader) over the streaming
        // ZipInputStream -- only the central directory carries unix-mode
        // external-attributes, so the streaming variant can't see the
        // symbolic-link bit (#187). A plain Zip Slip check (`startsWith(dest)`)
        // catches `../` traversal but misses a symlink entry whose linked
        // target sits outside [dest]; the next extraction would write
        // attacker bytes to that target.
        ZipFile.builder().setFile(zip).get().use { zf ->
            for (entry in zf.entries) {
                // Protection against Zip Slip vulnerabilities
                val resolvedPath = dest.resolve(entry.name).normalize()
                if (!resolvedPath.startsWith(dest)) {
                    throw IOException("Zip entry is outside of the target dir: ${entry.name}")
                }
                if (entry.isUnixSymlink) {
                    // For Zip, the link target is stored as the entry's payload
                    // bytes (not a separate field like TAR). Validate that the
                    // target, resolved relative to the symlink's parent, stays
                    // within [dest] -- same CVE-resistant pattern as the
                    // TAR path below.
                    val linkTarget = zf.getInputStream(entry).use { it.readBytes() }.decodeToString()
                    val resolvedTarget = resolvedPath.parent.resolve(linkTarget).normalize()
                    if (!resolvedTarget.startsWith(dest)) {
                        throw SecurityException(
                            "Symlink target escapes destination: ${entry.name} -> $linkTarget",
                        )
                    }
                    Files.createDirectories(resolvedPath.parent)
                    Files.deleteIfExists(resolvedPath)
                    Files.createSymbolicLink(resolvedPath, Path.of(linkTarget))
                    continue
                }

                if (entry.isDirectory) {
                    Files.createDirectories(resolvedPath)
                } else {
                    Files.createDirectories(resolvedPath.parent)
                    zf.getInputStream(entry).use { input ->
                        Files.copy(input, resolvedPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    internal fun untargz(tar: File, dest: Path) {
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
                            // Hard-reject entry kinds that have no legitimate use
                            // in any archive we extract: hardlinks (would let a
                            // tampered tarball create a hardlink to a sensitive
                            // file outside [dest] for the next write to clobber),
                            // FIFOs, character devices, block devices.
                            if (entry.isLink || entry.isFIFO ||
                                entry.isCharacterDevice || entry.isBlockDevice) {
                                throw SecurityException(
                                    "Archive contains non-regular entry (${entry.javaClass.simpleName}): ${entry.name}",
                                )
                            }

                            if (entry.isSymbolicLink) {
                                // BellSoft Linux/macOS JDK tarballs include
                                // legitimate intra-package symlinks (e.g.
                                // jre/lib/.../libjsig.so -> libjsig.so.0).
                                // Allow the link when its target, resolved
                                // relative to the symlink's parent, stays
                                // within [dest]; reject when it would escape,
                                // so a tampered upstream can't redirect the
                                // next write outside our extraction root.
                                // (#202 -- was blanket-rejected before, which
                                // broke fresh Linux installs since every
                                // BellSoft Linux JDK ships symlinks.)
                                val linkTarget = entry.linkName ?: ""
                                val resolvedTarget = resolvedPath.parent.resolve(linkTarget).normalize()
                                if (!resolvedTarget.startsWith(dest)) {
                                    throw SecurityException(
                                        "Symlink target escapes destination: ${entry.name} -> $linkTarget",
                                    )
                                }
                                Files.createDirectories(resolvedPath.parent)
                                Files.deleteIfExists(resolvedPath)
                                Files.createSymbolicLink(resolvedPath, Path.of(linkTarget))
                            } else if (entry.isDirectory) {
                                Files.createDirectories(resolvedPath)
                            } else {
                                Files.createDirectories(resolvedPath.parent)
                                Files.copy(tai, resolvedPath, StandardCopyOption.REPLACE_EXISTING)
                                // Restoring execution rights from an archive (for Linux/Mac)
                                // 0o111 = any of owner / group / other execute. Earlier mask
                                // 0o101 skipped the group bit; archives that ship 0o010-only
                                // entries would land without +x.
                                if (getOsName() != "win" && (entry.mode and 0b001_001_001) != 0) {
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

    internal fun getDownloadUrl(version: Int): String? {
        val os = getOsName()
        val arch = getArchName()
        return when (version) {
            8 -> when (os) {
                "win" if arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-windows-amd64-full.zip"
                "win" if arch == "x32" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-windows-i586.zip"
                "linux" if arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-linux-amd64-full.tar.gz"
                "mac" if arch == "x64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "https://download.bell-sw.com/java/8u472+9/bellsoft-jdk8u472+9-macos-aarch64.tar.gz"
                else -> null
            }
            17 -> when (os) {
                "win" if arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-windows-amd64-full.zip"
                "win" if arch == "x32" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-windows-i586-full.zip"
                "linux" if arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64-full.tar.gz"
                "mac" if arch == "x64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "https://download.bell-sw.com/java/17.0.17+15/bellsoft-jdk17.0.17+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            21 -> when (os) {
                "win" if arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-windows-amd64-full.zip"
                "linux" if arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-linux-amd64-full.tar.gz"
                "mac" if arch == "x64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "https://download.bell-sw.com/java/21.0.9+15/bellsoft-jdk21.0.9+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            else -> null
        }
    }
}
