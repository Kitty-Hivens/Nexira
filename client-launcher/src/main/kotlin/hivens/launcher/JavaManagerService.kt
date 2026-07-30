package hivens.launcher

import hivens.core.io.UnpackBudget
import hivens.core.io.UnpackLimits
import hivens.core.api.interfaces.IJavaManager
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.platform.OS
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
import java.util.concurrent.TimeUnit

class JavaManagerService(
    baseDir: Path,
    private val transfers: TransferEngine,
) : IJavaManager {
    private val log = LoggerFactory.getLogger(JavaManagerService::class.java)
    private val runtimesDir: Path = baseDir.resolve("runtimes")

    /**
     * Where an archive is downloaded to. Inside the data dir and named after what
     * it is, rather than a fresh system temp file per attempt: a two hundred
     * megabyte download that a relaunch cannot continue is a download that a bad
     * line never completes.
     */
    private val downloadsDir: Path = runtimesDir.resolve(".downloads")

    override suspend fun getJavaPath(version: String): Path =
        getJavaPathForMajor(detectJavaVersion(version))

    override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = withContext(Dispatchers.IO) {
        val os = OS.platform.bellsoft
        val arch = OS.arch.bellsoft

        val folderName = "java-$javaMajor-$os-$arch"
        val targetDir = runtimesDir.resolve(folderName)

        val existing = findJavaExecutable(targetDir)
        if (existing != null && isJavaUsable(existing)) {
            return@withContext existing
        }
        if (existing != null) {
            log.warn("Java at {} failed -version check, treating as broken and re-downloading", existing)
        } else {
            log.info("Java {} ({}/{}) was not found locally. We are starting to download...", javaMajor, os, arch)
        }
        onProgress("Downloading Java $javaMajor ($os/$arch)...")
        downloadAndUnpack(javaMajor, targetDir, onProgress)

        val executable = findJavaExecutable(targetDir)
            ?: throw IOException("Java was downloaded, but the executable file was not found!")

        if (os != "win") {
            setExecutablePermissions(executable)
        }

        if (!isJavaUsable(executable)) {
            throw IOException("Java was downloaded and extracted, but $executable failed -version check. Archive may be corrupt or missing native loader files (e.g. libjli.so).")
        }

        onProgress("Java $javaMajor ready")
        return@withContext executable
    }

    private suspend fun downloadAndUnpack(version: Int, targetDir: Path, onProgress: (String) -> Unit = {}) {
        val urls = getDownloadUrls(version)
        if (urls.isEmpty()) {
            throw IOException("There is no Java build for this system (${OS.platform.bellsoft} ${OS.arch.bellsoft})")
        }

        // Try each mirror in order; first one that returns a usable
        // archive wins. Fallback exists because CloudFlare in front of
        // BellSoft 403s certain regions / IP ranges entirely (filed
        // 2026-05-24: RF-based tester saw consistent 403 from BellSoft
        // despite valid URL, while curl from elsewhere returned 200).
        // Adoptium is hosted on GitHub releases which has wider regional
        // reachability and less aggressive bot detection.
        //
        // The loop stays even though the engine can walk mirrors itself: here the
        // next host is also worth trying when the archive downloads fine and then
        // will not unpack, which is a failure the engine cannot see.
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            val isZip = url.endsWith(".zip")
            val archive = downloadsDir.resolve("java-$version-${OS.platform.bellsoft}-${OS.arch.bellsoft}" + if (isZip) ".zip" else ".tar.gz")
            try {
                log.info("Download Java attempt {}/{}: {}", index + 1, urls.size, url)
                var lastPct = -1
                transfers.fetch(
                    Transfer(
                        url = url,
                        dest = archive,
                        // Verified by unpacking it and running `java -version` rather
                        // than by hash: a checksum fetched from the same host over the
                        // same connection as the archive is worth little, and a tree
                        // with no working java in it is caught before anything
                        // installed is touched. See [installUnpacked].
                        expect = null,
                        // Browser-shaped User-Agent. The default ktor identifier is on
                        // CloudFlare's bot signature list and gets blanket-403'd from
                        // regions it flags as bot-heavy. A real-Chrome UA passes the
                        // cheap heuristic; TLS-fingerprint detection would still catch
                        // us, but neither BellSoft nor GitHub appears to use that tier.
                        userAgent = DOWNLOAD_UA,
                        // Nothing addresses this path but the version in its name, and
                        // a half-downloaded archive is a partial, not a file.
                        skip = SkipIfPresent.Never,
                    )
                ) { done, total ->
                    // A fresh Java 25 download is ~200 MB, and without this the UI sits
                    // on PrepareStage.JVM saying nothing for minutes.
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        if (pct >= lastPct + 5 || pct == 100) {
                            lastPct = pct
                            onProgress("Downloading Java $version: $pct%")
                        }
                    }
                }

                onProgress("Unpacking Java $version archive...")
                log.info("Unpacking to {}", targetDir)
                installUnpacked(archive, targetDir, isZip)
                Files.deleteIfExists(archive)
                return
            } catch (e: Exception) {
                // Whatever arrived is left where it is. The partial beside this
                // archive is what the next attempt continues from, and on the route
                // this fallback exists for, continuing rather than starting over is the
                // difference between finishing and never finishing.
                log.warn("Download from {} failed: {}", url, e.message)
                lastError = e
                // Continue to next mirror.
            }
        }
        throw IOException(
            "All Java $version download mirrors failed for ${OS.platform.bellsoft} ${OS.arch.bellsoft}. " +
                "Last error: ${lastError?.message ?: "unknown"}",
            lastError,
        )
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

    /**
     * Runs `<javaExe> -version` and returns true iff the process completes
     * with exit code 0 within a few seconds. Detects JDKs whose binary exists
     * and has the executable bit set but cannot actually launch — e.g. an
     * extracted archive missing native loader files like `libjli.so`, where
     * the dynamic linker fails before the JVM itself starts.
     */
    internal fun isJavaUsable(javaExe: Path): Boolean {
        return try {
            val process = ProcessBuilder(javaExe.toString(), "-version")
                .redirectErrorStream(true)
                .start()
            // Drain the merged stream in a background daemon thread so a
            // chatty JDK does not block on a full pipe buffer, and so a
            // hanging child cannot keep us in readBytes() past the
            // waitFor timeout. Daemon so JVM exit is never blocked by a
            // stuck reader.
            Thread {
                try { process.inputStream.use { it.readBytes() } } catch (_: Exception) {}
            }.apply { isDaemon = true }.start()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                // destroyForcibly is async (SIGKILL on Linux,
                // TerminateProcess on Windows). Block briefly so file
                // handles release before the caller deletes targetDir
                // -- on Windows a still-alive java.exe keeps extracted
                // files locked, turning the recovery re-download into
                // an exception loop.
                process.waitFor(3, TimeUnit.SECONDS)
                return false
            }
            process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Unpacks [archive] into [targetDir], keeping whatever is already installed
     * there until the new copy is complete and usable.
     *
     * The previous order emptied [targetDir] first and unpacked into it, so a
     * truncated archive, a full disk, or an interrupted unpack left the user
     * with no JVM where a working one had been -- and the retry loop then moved
     * on to the next mirror having already destroyed the fallback. A JVM is not
     * cheap to re-fetch on a metered or blocked connection.
     *
     * Unpacking to a sibling first also gives a real integrity gate: an archive
     * that arrives truncated produces a tree with no `java` in it, and that is
     * checked before anything installed is touched. That check is worth more
     * than comparing a checksum fetched from the same host over the same
     * connection as the archive, which an attacker able to serve one can serve
     * the other.
     */
    internal fun installUnpacked(archive: Path, targetDir: Path, isZip: Boolean) {
        val incoming = targetDir.resolveSibling("${targetDir.fileName}.incoming")
        val previous = targetDir.resolveSibling("${targetDir.fileName}.previous")
        deleteDirectoryRecursively(incoming)
        deleteDirectoryRecursively(previous)
        Files.createDirectories(incoming)

        try {
            if (isZip) unzip(archive.toFile(), incoming) else untargz(archive.toFile(), incoming)
            if (findJavaExecutable(incoming) == null) {
                // Worded to match the post-install check below: this is the same
                // condition found earlier, and the all-mirrors-failed error
                // quotes this message, so the reason reaches the user either way.
                throw IOException("Java was downloaded, but the executable file was not found in the unpacked archive")
            }

            // Two renames with the swap between them, rather than a delete and
            // a full unpack: the window where neither copy is in place is as
            // short as the filesystem allows.
            if (Files.exists(targetDir)) Files.move(targetDir, previous)
            Files.move(incoming, targetDir)
            deleteDirectoryRecursively(previous)
        } catch (e: Exception) {
            runCatching { deleteDirectoryRecursively(incoming) }
            // A swap that failed between the two renames leaves the install
            // under `.previous`; put it back rather than leaving the user with
            // nothing.
            if (!Files.exists(targetDir) && Files.exists(previous)) {
                runCatching { Files.move(previous, targetDir) }
            }
            runCatching { deleteDirectoryRecursively(previous) }
            throw e
        }
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
        // ZipFile (random-access central-directory reader) over the
        // streaming ZipInputStream because only the central directory
        // carries unix-mode external-attributes -- the streaming
        // variant can't see the symbolic-link bit. A plain Zip Slip
        // check (`startsWith(dest)`) catches `../` traversal but
        // misses a symlink entry whose linked target sits outside
        // [dest]; the next extraction would write attacker bytes to
        // that target.
        val budget = UnpackBudget(UnpackLimits.RUNTIME, zip.name)
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
                    budget.entry()
                    zf.getInputStream(entry).use { input -> budget.copyTo(input, resolvedPath) }
                }
            }
        }
    }

    internal fun untargz(tar: File, dest: Path) {
        val budget = UnpackBudget(UnpackLimits.RUNTIME, tar.name)
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
                                // BellSoft Linux / macOS JDK tarballs
                                // include legitimate intra-package
                                // symlinks (e.g. `jre/lib/.../libjsig.so
                                // -> libjsig.so.0`). Allow the link when
                                // its target -- resolved relative to the
                                // symlink's parent -- stays within
                                // [dest]; reject when it would escape so
                                // a tampered upstream can't redirect the
                                // next write outside our extraction root.
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
                                budget.entry()
                                budget.copyTo(tai, resolvedPath)
                                // Restore execute bits on Linux / mac:
                                // 0o111 mask = any of owner / group /
                                // other execute. Stricter masks miss
                                // archives that ship group-only-exec
                                // (`0o010`) entries.
                                if (!OS.isWindows && (entry.mode and 0b001_001_001) != 0) {
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

    /**
     * Ordered list of mirrors to try for the given Java major. The
     * downloader walks the list in order; first one that delivers a
     * usable archive wins. Adoptium is the fallback because BellSoft
     * (via CloudFlare) blanket-403s certain regions / IP ranges that
     * the user's network may sit behind.
     */
    internal fun getDownloadUrls(version: Int): List<String> =
        listOfNotNull(getBellSoftUrl(version), getAdoptiumUrl(version))

    /**
     * Backwards-compatible alias: existing tests + callers that want
     * just the primary URL keep working. New code should prefer
     * [getDownloadUrls] so fallback is exercised.
     */
    internal fun getDownloadUrl(version: Int): String? = getBellSoftUrl(version)

    internal fun getBellSoftUrl(version: Int): String? {
        val os = OS.platform.bellsoft
        val arch = OS.arch.bellsoft
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
            25 -> when (os) {
                "win" if arch == "x64" -> "https://download.bell-sw.com/java/25.0.3+11/bellsoft-jdk25.0.3+11-windows-amd64-full.zip"
                "linux" if arch == "x64" -> "https://download.bell-sw.com/java/25.0.3+11/bellsoft-jdk25.0.3+11-linux-amd64-full.tar.gz"
                "mac" if arch == "x64" -> "https://download.bell-sw.com/java/25.0.3+11/bellsoft-jdk25.0.3+11-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "https://download.bell-sw.com/java/25.0.3+11/bellsoft-jdk25.0.3+11-macos-aarch64-full.tar.gz"
                else -> null
            }
            else -> null
        }
    }

    /**
     * Adoptium / Temurin GitHub-release URL for the given Java major.
     * Pinned to a known LTS-line build per major; bump these by hand
     * when a newer build is needed. GitHub releases are statically
     * served, no CloudFlare bot manager in front -- works from
     * regions where BellSoft's CDN returns 403.
     *
     * The `+` in the Temurin tag name is %2B-encoded so the URL stays
     * valid through every HTTP-client URL-parser variant. The filename
     * uses the underscored form (`21.0.5_11`) which is Adoptium's own
     * convention.
     */
    internal fun getAdoptiumUrl(version: Int): String? {
        val os = OS.platform.bellsoft
        val arch = OS.arch.bellsoft
        return when (version) {
            8 -> when (os) {
                "win"   if arch == "x64"   -> adoptium(8, "8u442-b06", "8u442b06", "x64",     "windows", "zip")
                "linux" if arch == "x64"   -> adoptium(8, "8u442-b06", "8u442b06", "x64",     "linux",   "tar.gz")
                "mac"   if arch == "x64"   -> adoptium(8, "8u442-b06", "8u442b06", "x64",     "mac",     "tar.gz")
                "mac"   if arch == "arm64" -> adoptium(8, "8u442-b06", "8u442b06", "aarch64", "mac",     "tar.gz")
                else -> null
            }
            17 -> when (os) {
                "win"   if arch == "x64"   -> adoptium(17, "17.0.13+11", "17.0.13_11", "x64",     "windows", "zip")
                "linux" if arch == "x64"   -> adoptium(17, "17.0.13+11", "17.0.13_11", "x64",     "linux",   "tar.gz")
                "mac"   if arch == "x64"   -> adoptium(17, "17.0.13+11", "17.0.13_11", "x64",     "mac",     "tar.gz")
                "mac"   if arch == "arm64" -> adoptium(17, "17.0.13+11", "17.0.13_11", "aarch64", "mac",     "tar.gz")
                else -> null
            }
            21 -> when (os) {
                "win"   if arch == "x64"   -> adoptium(21, "21.0.5+11", "21.0.5_11", "x64",     "windows", "zip")
                "linux" if arch == "x64"   -> adoptium(21, "21.0.5+11", "21.0.5_11", "x64",     "linux",   "tar.gz")
                "mac"   if arch == "x64"   -> adoptium(21, "21.0.5+11", "21.0.5_11", "x64",     "mac",     "tar.gz")
                "mac"   if arch == "arm64" -> adoptium(21, "21.0.5+11", "21.0.5_11", "aarch64", "mac",     "tar.gz")
                else -> null
            }
            25 -> when (os) {
                "win"   if arch == "x64"   -> adoptium(25, "25.0.3+9", "25.0.3_9", "x64",     "windows", "zip")
                "linux" if arch == "x64"   -> adoptium(25, "25.0.3+9", "25.0.3_9", "x64",     "linux",   "tar.gz")
                "mac"   if arch == "x64"   -> adoptium(25, "25.0.3+9", "25.0.3_9", "x64",     "mac",     "tar.gz")
                "mac"   if arch == "arm64" -> adoptium(25, "25.0.3+9", "25.0.3_9", "aarch64", "mac",     "tar.gz")
                else -> null
            }
            else -> null
        }
    }

    private fun adoptium(
        major: Int,
        tag: String,
        fileVersion: String,
        arch: String,
        os: String,
        ext: String,
    ): String {
        val tagEncoded = tag.replace("+", "%2B")
        // Java 8 release tag prefix is `jdk` (no dash before the version),
        // 9+ uses `jdk-`. Mirrors Adoptium's own release naming.
        val tagPrefix = if (major == 8) "jdk" else "jdk-"
        return "https://github.com/adoptium/temurin${major}-binaries/releases/download/" +
            "$tagPrefix$tagEncoded/OpenJDK${major}U-jdk_${arch}_${os}_hotspot_$fileVersion.$ext"
    }

    companion object {
        // Real-Chrome User-Agent. See downloadAndUnpack for why this is
        // not the launcher's default identifier.
        internal const val DOWNLOAD_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
