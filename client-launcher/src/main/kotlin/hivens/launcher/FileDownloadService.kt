package hivens.launcher

import hivens.launcher.network.ServerProtocolConfig
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IFileDownloadService
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.SessionData
import hivens.core.util.ZipUtils
import hivens.core.util.retryWithBackoff
import hivens.launcher.util.ClientRootDirs
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds


class FileDownloadService(
    private val clientProvider: HttpClientProvider,
    private val protectedPaths: ProtectedPaths,
    private val manifestCache: ManifestCache,
    private val config: ServerProtocolConfig,
) : IFileDownloadService {
    private val client get() = clientProvider.current

    companion object {
        private val logger = LoggerFactory.getLogger(FileDownloadService::class.java)

        private const val INDEX_FILENAME = ".extra_unpacked_index.json"

        private val indexJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }

    override suspend fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?,
        verifyUI: ((Int, Int) -> Unit)?,
        injectModJar: Path?,
        strictModCheck: Boolean,
        helperKeepGlobs: List<String>,
    ) = withContext(Dispatchers.IO) {
        val manifest = session.fileManifest ?: throw IOException("File manifest is empty!")
        Files.createDirectories(targetDir)

        val filesMap = flattenManifest(manifest)

        // ── Disabled-mod cleanup runs UNCONDITIONALLY ────────────────────
        // Must precede the cache short-circuit below: a stale jar in
        // top-level mods/ (from a prior install where the upstream
        // manifest placed the mod there, or from SC's own launcher)
        // never appears in the current manifest's filesMap, so the
        // integrity walk doesn't notice it. Cache hit then skips the
        // walk too, and Forge happily loads a "disabled" mod every
        // launch. The cost is one Files.walk over a few-hundred-entry
        // tree -- well under 50 ms on SSD, negligible against the rest
        // of launch latency.
        if (!ignoredFiles.isNullOrEmpty()) {
            cleanupIgnoredFiles(targetDir, ignoredFiles)
        }

        // Drop ignored entries from the working set BEFORE the cache gate.
        // An ignored mod that the manifest still lists (e.g. the upstream
        // Smarty jar we replace with open-smrt) is deliberately absent from
        // disk; leaving it in filesMap would fail the disk-sanity walk on
        // every launch and defeat the cache. The cache key already carries
        // the ignored set, so a toggle change still invalidates correctly.
        if (!ignoredFiles.isNullOrEmpty()) {
            filesMap.keys.removeIf { relativePath ->
                val clean = normalizePath(relativePath)
                ignoredFiles.any { clean.endsWith("/$it") || clean == it }
            }
        }

        // ── Smarty swap + strict verification -- also pre-cache ──────────
        // Same reasoning as the disabled-mod cleanup: both must run before
        // the cache gate so a cache hit still strips foreign jars and keeps
        // the injected open-smrt helper in place. Strict prune first, then
        // inject, so the prune can never delete the jar we just placed (it
        // is also name-excepted as a belt-and-suspenders). The allowed set is
        // the post-ignored filesMap, so a stripped Smarty jar is not re-admitted.
        val injectName = injectModJar?.fileName?.toString()
        if (strictModCheck) {
            strictPruneMods(targetDir, filesMap.keys, injectName, helperKeepGlobs)
        }
        if (injectModJar != null) {
            injectHelperJar(targetDir, injectModJar)
        }

        // ── Manifest cache short-circuit ─────────────────────────────────
        // If this same manifest *with the same ignoredFiles set* was
        // successfully synced recently (≤TTL), skip the full per-file
        // MD5 walk and the extra.zip processing. Both downstream steps
        // are themselves hash-gated and would no-op, but the integrity
        // walk alone dominates cold-start on 1000-file modpacks. The
        // TTL inside ManifestCache is the safety valve for "what if a
        // file got corrupted on disk?" scenarios.
        //
        // ignoredFiles is still part of the cache key so a toggle change
        // invalidates the cache and forces the full integrity walk;
        // cleanupIgnoredFiles above is the belt that catches stale jars,
        // the cache key is the suspenders that catches missing
        // newly-enabled jars.
        val manifestHash = manifestCache.hashOf(cacheKeyInputFor(manifest, ignoredFiles, injectName, strictModCheck))
        // Disk-sanity gate: the manifest-cache file alone can't tell
        // that the user moved their data dir leaving manifest-cache/
        // behind, deleted clients/<id>/ by hand, removed one mod, or
        // restored from a partial backup. Walk EVERY manifest entry
        // with a single stat() per file and require:
        //   * the path exists,
        //   * it's a regular file (not a dangling symlink or directory
        //     squatting on the name),
        //   * its byte size matches the manifest's recorded size.
        // If anything fails, fall through to the full MD5 integrity
        // walk + redownload.
        //
        // Cost: ~1 stat per file. A 1000-entry modpack walks in <10 ms
        // on Linux / macOS, ~50 ms on Windows. Negligible against the
        // full MD5 walk (seconds for the same pack) and the
        // user-perceived launch latency (~5+ s for non-cache paths).
        //
        // Sampling only the top N manifest entries would miss
        // deletions or truncations elsewhere -- Nexira packs run
        // 50-1000+ entries and the affected file is rarely at the top
        // of an alphabetical traversal. The walk MUST cover every
        // entry or the cache silently masks a missing mod and the
        // game crashes downstream with a NoClassDefFoundError the
        // user can't map back to a launcher-side cause.
        val cacheValid = manifestCache.isClean(serverId, manifestHash) {
            filesMap.entries.all { (rawPath, fileData) ->
                val path = targetDir.resolve(normalizePath(rawPath))
                runCatching {
                    val attrs = Files.readAttributes(path, BasicFileAttributes::class.java)
                    attrs.isRegularFile && attrs.size() == fileData.size
                }.getOrDefault(false)
            }
        }
        if (cacheValid) {
            messageUI?.invoke("Files verified (cached)")
            return@withContext
        }

        // 3. Downloading
        downloadMissingFiles(targetDir, filesMap, messageUI, progressUI, verifyUI)

        // 4. Processing Extra.zip
        processExtraZip(targetDir, filesMap, extraCheckSum, messageUI)

        // 5. Mark this manifest as cleanly synced -- next session with the
        // same manifest hash short-circuits the integrity walk above.
        // Pass the manifest content too: the offline-launch path
        // (LauncherController) recovers it from cache when sync is
        // skipped, so the classpath builder still has a file list to walk.
        manifestCache.markClean(serverId, manifestHash, manifest)
    }

    /**
     * Composes the cache-key input as
     * `<canonical-manifest-json>|ignored:<sorted-csv>|inject:<name>|strict:<bool>`.
     * Sorting the ignored set is mandatory -- `Set` iteration order isn't
     * stable, and the cache must be insensitive to insertion order while
     * sensitive to membership changes. The inject + strict bits join the key so
     * flipping either Smarty setting invalidates a previously-clean sync.
     */
    private fun cacheKeyInputFor(
        manifest: FileManifest,
        ignoredFiles: Set<String>?,
        injectName: String?,
        strictModCheck: Boolean,
    ): String {
        val ignored = ignoredFiles?.toSortedSet()?.joinToString(",") ?: ""
        return indexJson.encodeToString(manifest) +
            "|ignored:" + ignored +
            "|inject:" + (injectName ?: "") +
            "|strict:" + strictModCheck
    }

    /**
     * Deletes every jar in `mods/` whose basename is neither in the manifest
     * ([manifestKeys], full manifest paths), the injected helper ([injectName]),
     * nor a match for one of [helperKeepGlobs]. The blunt enforcement behind
     * "Strict mod verification": removes user-added jars too, which is the
     * documented intent. [helperKeepGlobs] is what keeps the open-smrt helper
     * alive on a launch where the resolver couldn't refresh it (so [injectName]
     * is null) -- without it, strict verification would delete the helper and
     * the next sync would have no network mod. Mirrors
     * `SmrtSyncService.pruneOrphanMods`; the recursive walk covers both
     * top-level `mods/` and version subdirs like `mods/1.12.2/`.
     */
    private fun strictPruneMods(
        baseDir: Path,
        manifestKeys: Set<String>,
        injectName: String?,
        helperKeepGlobs: List<String>,
    ) {
        val modsDir = baseDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return

        val allowed = HashSet<String>(manifestKeys.size + 1)
        manifestKeys.forEach { allowed.add(normalizePath(it).substringAfterLast('/')) }
        if (injectName != null) allowed.add(injectName)
        val keepPatterns = helperKeepGlobs.map { globToRegex(it) }

        var removed = 0
        try {
            Files.walk(modsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .forEach { jar ->
                        val name = jar.fileName.toString()
                        if (name !in allowed && keepPatterns.none { it.matches(name) }) {
                            runCatching {
                                Files.delete(jar)
                                removed++
                                logger.debug("Strict mod check: pruned {}", jar)
                            }.onFailure { logger.warn("Strict mod check: failed to prune {}", jar, it) }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Strict mod check: error walking mods folder", e)
        }
        if (removed > 0) logger.info("Strict mod check: pruned {} foreign jar(s) from mods/", removed)
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * Copies the open-smrt-network helper into `mods/`, replacing the upstream
     * Smarty coremod the caller stripped via `ignoredFiles`. Always overwrites:
     * the source bytes were already SHA-256 verified by `OpenSmrtHelperResolver`,
     * and a size-only skip would miss a same-size helper rebuild. The jar is tiny
     * (single-digit KB), so an unconditional copy per sync is negligible.
     */
    private fun injectHelperJar(baseDir: Path, jar: Path) {
        if (!Files.isRegularFile(jar)) {
            logger.warn("open-smrt helper: jar {} missing at inject time; skipping", jar)
            return
        }
        runCatching {
            val modsDir = baseDir.resolve("mods")
            Files.createDirectories(modsDir)
            val dest = modsDir.resolve(jar.fileName.toString())
            Files.copy(jar, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            logger.info("open-smrt helper: injected {}", dest.fileName)
        }.onFailure { logger.warn("open-smrt helper: failed to inject {}", jar, it) }
    }

    /**
     * Recursively traverses the manifest and collects all files into one map.
     */
    internal fun flattenManifest(manifest: FileManifest): MutableMap<String, FileData> {
        val result = HashMap<String, FileData>()
        fun traverse(m: FileManifest, currentPath: String) {
            m.files.forEach { (name, data) ->
                val fullPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                result[fullPath] = data
            }
            m.directories.forEach { (name, subManifest) ->
                traverse(subManifest, if (currentPath.isEmpty()) name else "$currentPath/$name")
            }
        }
        traverse(manifest, "")
        return result
    }

    private suspend fun downloadMissingFiles(
        baseDir: Path,
        files: Map<String, FileData>,
        messageUI: ((String) -> Unit)?,
        progressUI: ((Int, Int, Long, Long, String) -> Unit)?,
        verifyUI: ((Int, Int) -> Unit)?,
    ) {
        // STEP 1: Checking hashes
        //
        // MD5-walking a 1000-file modpack takes 5-30 seconds depending on
        // disk speed. Emitting [verifyUI] every 25 files (or roughly once per
        // 100ms on slow disks) gives the UI's progress bar something to
        // advance against -- otherwise the user sees "Sync... 20%" silent
        // for the entire integrity walk and assumes the launcher hung.
        messageUI?.invoke("Checking file integrity...")
        val totalFiles = files.size
        verifyUI?.invoke(0, totalFiles)

        val filesToDownload = LinkedHashMap<String, FileData>()
        var checked = 0
        for ((rawPath, data) in files) {
            val cleanPath = normalizePath(rawPath)
            if (isFileMissingOrChanged(baseDir.resolve(cleanPath), data.md5, cleanPath)) {
                filesToDownload[rawPath] = data
            }
            checked++
            // Coarse-grained progress: avoid one callback per file on a
            // 5000-file pack (would churn Compose state at thousands of
            // updates per second). 25 is fine-enough on modern SSDs and
            // coarse-enough on slow HDDs.
            if (checked % 25 == 0 || checked == totalFiles) {
                verifyUI?.invoke(checked, totalFiles)
            }
        }

        val totalFilesCount = filesToDownload.size
        // We count the total size (if there is no size, it will be 0)
        val totalBytesToDownload = filesToDownload.values.sumOf { it.size }

        if (totalFilesCount == 0) {
            messageUI?.invoke("Files verified, no updates found.")
            return
        }

        // STEP 2: Download
        messageUI?.invoke("Downloading updates ($totalFilesCount files)...")

        // Atomics for thread-safe counting
        val currentFileCounter = AtomicInteger(0)
        val downloadedBytesGlobal = AtomicLong(0)
        val semaphore = Semaphore(5) // Limit of 5 threads

        val startTime = System.currentTimeMillis()

        coroutineScope {
            // Ticker for progress callbacks. Inherits the parent dispatcher
            // (Dispatchers.IO from processSession's withContext) instead of
            // hopping to Main, so this works in headless contexts (puppet
            // mode, integration tests) where Dispatchers.Main isn't wired.
            // progressUI lambdas marshal themselves to the right thread if
            // they need to.
            val monitorJob = launch {
                while (isActive) {
                    val currentBytes = downloadedBytesGlobal.get()
                    val currentFiles = currentFileCounter.get()

                    val now = System.currentTimeMillis()
                    val durationSec = (now - startTime) / 1000.0
                    val speed = if (durationSec > 0.1) formatSpeed(currentBytes / durationSec) else "..."

                    progressUI?.invoke(
                        currentFiles,
                        totalFilesCount,
                        currentBytes,
                        totalBytesToDownload,
                        speed
                    )

                    delay(100.milliseconds)
                    if (currentFiles >= totalFilesCount && currentBytes >= totalBytesToDownload) break
                }
            }

            // Downloading
            val tasks = filesToDownload.map { (rawPath, _) ->
                async(Dispatchers.IO) {
                    if (!isActive) throw CancellationException()

                    semaphore.withPermit {
                        if (!isActive) throw CancellationException()

                        val cleanPath = normalizePath(rawPath)
                        val targetFile = baseDir.resolve(cleanPath)

                        downloadFileInternal(rawPath, targetFile) { bytesRead ->
                            // We just increase the counter. We don't touch the UI.
                            downloadedBytesGlobal.addAndGet(bytesRead.toLong())
                            if (!isActive) throw CancellationException()
                        }

                        currentFileCounter.incrementAndGet()
                    }
                }
            }

            // We are waiting for all downloads to complete
            try {
                tasks.awaitAll()
            } finally {
                monitorJob.cancel()
            }

            // Final update (100%)
            if (isActive) {
                progressUI?.invoke(
                    totalFilesCount, totalFilesCount,
                    totalBytesToDownload, totalBytesToDownload,
                    ""
                )
            }
        }
    }

    private suspend fun downloadFileInternal(
        serverPath: String,
        localPath: Path,
        onBytesRead: ((Int) -> Unit)? = null
    ) {
        val url = "${config.clientFilesBase}/" +
                serverPath.split("/").joinToString("/") { segment -> URLEncoder.encode(segment, "UTF-8").replace("+", "%20") }
        withContext(Dispatchers.IO) {
            if (localPath.parent != null) Files.createDirectories(localPath.parent)

            // Retry the whole transfer on transient network errors. The
            // SMARTYcraft channel periodically drops mid-stream over SOCKS;
            // without retry-with-resume a flaky network turns 100MB asset sync
            // into a Sisyphean restart-from-zero loop.
            retryWithBackoff(
                operation = "download $serverPath",
                shouldRetry = ::isTransientDownloadError,
            ) {
                val existing = if (Files.exists(localPath)) Files.size(localPath) else 0L

                client.prepareGet(url) {
                    if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
                }.execute { response ->
                    when (response.status) {
                        HttpStatusCode.PartialContent -> {
                            // 206: server is honouring the Range -- append the remainder.
                            // Report the already-on-disk bytes upfront so the UI's
                            // total-bytes progress hits 100% on completion. (Long-to-Int
                            // narrowing is fine here -- modpack assets are well under 2GB.)
                            if (existing > 0) onBytesRead?.invoke(existing.toInt())
                            writeBody(response, localPath, append = true, onBytesRead)
                        }
                        HttpStatusCode.OK -> {
                            // 200: server ignored Range (or we sent none) -- overwrite.
                            // Don't report existing bytes; we're throwing them away.
                            writeBody(response, localPath, append = false, onBytesRead)
                        }
                        HttpStatusCode.RequestedRangeNotSatisfiable -> {
                            // 416: partial on disk is bigger than the upstream file
                            // (corrupt write or upstream shrank). Clear and let retry
                            // fetch from byte 0. Throw the dedicated subclass so
                            // isTransientDownloadError recognizes it as retryable
                            // -- a plain IOException with this message would NOT
                            // match the predicate's substring checks and would
                            // hard-fail instead of recovering.
                            Files.deleteIfExists(localPath)
                            throw RetryableHttpException("HTTP 416 for $url; cleared bad partial, will refetch")
                        }
                        else -> throw IOException("HTTP ${response.status} for $url")
                    }
                }
            }
        }
    }

    private suspend fun writeBody(
        response: HttpResponse,
        localPath: Path,
        append: Boolean,
        onBytesRead: ((Int) -> Unit)?,
    ) {
        val channel = response.bodyAsChannel()
        FileOutputStream(localPath.toFile(), append).use { output -> // TODO: Possibly blocking call in non-blocking context could lead to thread starvation
            val buffer = ByteArray(8192)
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                onBytesRead?.invoke(read)
            }
        }
    }

    /**
     * Sentinel for "we deliberately threw to trigger a retry after
     * fixing local state". Currently the only thrower is the 416
     * branch in [downloadFileInternal], which deletes the bad partial
     * before raising this so the next retry fetches from byte 0.
     * Subclass over string-matching `cause.message` keeps the contract
     * explicit.
     */
    private class RetryableHttpException(message: String) : IOException(message)

    private fun isTransientDownloadError(t: Throwable): Boolean {
        // CancellationException must NEVER be retried -- it's how the parent
        // coroutine signals "stop"; swallowing and retrying would deadlock
        // the launch flow.
        if (t is CancellationException) return false
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is RetryableHttpException) return true
            if (cause is ConnectException ||
                cause is SocketException ||
                cause is ClosedByteChannelException ||
                cause is SocketTimeoutException
            ) return true
            if (cause is IOException &&
                cause.message?.contains("Connection reset", ignoreCase = true) == true
            ) return true
            cause = cause.cause
        }
        return false
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val kb = bytesPerSec / 1024
        if (kb < 1024) return "${kb.roundToInt()} KB/s"
        val mb = kb / 1024
        return String.format("%.1f MB/s", mb)
    }

    /**
     * Checks whether the file needs to be downloaded (no file, empty, or the hash does not match).
     * Includes protection for user-specific config files.
     */
    internal fun isFileMissingOrChanged(file: Path, expectedMd5: String, relativePath: String): Boolean {
        if (!Files.exists(file)) return true
        if (Files.isDirectory(file)) return false
        if (expectedMd5 == "any") return false // "any" hash means "do not check"

        return try {
            if (Files.size(file) == 0L) return true

            // Protected user-config: present + non-empty means hands-off.
            // The default list is in ProtectedPaths.kt; users extend it via
            // dataDir/protected-paths.json without recompiling.
            if (protectedPaths.isProtected(relativePath)) return false

            val localMd5 = calculateMD5(file)
            if (!localMd5.equals(expectedMd5, ignoreCase = true)) return true

            // ZIP-structure validation for `mods/*.jar`. Bytes-on-disk
            // can match the manifest's MD5 verbatim and still be a
            // malformed ZIP -- happens when the upstream uploaded
            // already-corrupt bytes (manifest hash was computed over
            // the broken file) or a partial write that the post-write
            // integrity check missed. NeoForge's BootstrapLauncher
            // dies with "invalid CEN header (bad signature)" on the
            // broken jar and the user has no signal except the crash.
            // Scoped to `mods/` only -- that's the corruption hot zone
            // and a 1000-file `libraries/` scan would dominate cold
            // start.
            if (isModsJar(relativePath) && !isZipValid(file)) return true

            false
        } catch (_: Exception) {
            true // In case of any reading error, it is better to re-download
        }
    }

    private fun isModsJar(relativePath: String): Boolean {
        val lower = relativePath.lowercase().replace('\\', '/')
        return lower.endsWith(".jar") && lower.contains("mods/")
    }

    /**
     * @return true if [file] opens cleanly as a JAR/ZIP and the central
     *         directory walk completes without exception. False on any
     *         exception (truncated archive, corrupt CEN, IO error). Cheap
     *         enough to call per-jar on the mods set (~1-3s for the
     *         Create-class 200-jar pack on SSD).
     */
    private fun isZipValid(file: Path): Boolean = try {
        java.util.jar.JarFile(file.toFile()).use { jar ->
            jar.entries().asSequence().count() > 0
        }
    } catch (_: Exception) { false }

    /**
     * Snapshot of the last successful extra.zip extraction. Persisted as
     * [INDEX_FILENAME] in `baseDir` so the next sync can diff old paths
     * against new and prune files the upstream modpack removed.
     *
     * Default-constructed (empty hash + empty paths) when no previous
     * snapshot exists -- first unpack writes the index, second-and-later
     * unpacks get the prune behavior.
     */
    @kotlinx.serialization.Serializable
    private data class ExtraZipIndex(
        val hash: String = "",
        val paths: List<String> = emptyList(),
    )

    private fun processExtraZip(
        baseDir: Path,
        files: Map<String, FileData>,
        serverCheckSum: String?,
        messageUI: ((String) -> Unit)?
    ) {
        val extraKey = files.keys.firstOrNull { normalizePath(it).endsWith("extra.zip") } ?: return
        val localZip = baseDir.resolve(normalizePath(extraKey))
        if (!Files.exists(localZip)) return

        val localHash = try { calculateMD5(localZip) } catch (_: Exception) { "" }
        val indexFile = baseDir.resolve(INDEX_FILENAME)
        val previousIndex = readIndex(indexFile)

        // Skip-unzip decision: server hash takes precedence; fallback to
        // index hash from last successful extract.
        val skipReason = when {
            !serverCheckSum.isNullOrEmpty() && localHash.equals(serverCheckSum, true) -> "server hash matches"
            localHash.isNotEmpty() && localHash == previousIndex.hash               -> "index hash matches last unpack"
            else                                                                    -> null
        }
        if (skipReason != null) {
            logger.debug("extra.zip unpack skipped -- {}", skipReason)
            return
        }

        try {
            messageUI?.invoke("Setting up the client...")
            val newPaths = ZipUtils.unzip(localZip.toFile(), baseDir.toFile())
            pruneOrphans(baseDir, previousIndex.paths, newPaths)
            writeIndex(indexFile, ExtraZipIndex(hash = localHash, paths = newPaths))
        } catch (e: Exception) {
            logger.error("Error unpacking extra.zip", e)
        }
    }

    /**
     * Files in [previousPaths] that no longer appear in [currentPaths] are
     * orphans -- the upstream modpack removed them, so the local install
     * should drop them too. Protected paths (per [protectedPaths]) are
     * never touched even if the index says they were last there: the
     * user may have edited an `options.txt` that originally arrived in
     * extra.zip, and we promised never to overwrite their config.
     */
    private fun pruneOrphans(baseDir: Path, previousPaths: List<String>, currentPaths: List<String>) {
        val current = currentPaths.toSet()
        val orphans = previousPaths.filter { it !in current }
        if (orphans.isEmpty()) return

        var pruned = 0
        for (rel in orphans) {
            if (protectedPaths.isProtected(rel)) {
                logger.debug("orphan {} kept -- protected path", rel)
                continue
            }
            val target = baseDir.resolve(rel)
            try {
                if (Files.deleteIfExists(target)) pruned++
            } catch (e: Exception) {
                logger.warn("Failed to prune orphan {}", rel, e)
            }
        }
        if (pruned > 0) logger.info("Pruned {} orphan files removed by upstream extra.zip", pruned)
    }

    private fun readIndex(indexFile: Path): ExtraZipIndex {
        if (!Files.exists(indexFile)) return ExtraZipIndex()
        return runCatching {
            indexJson.decodeFromString<ExtraZipIndex>(Files.readString(indexFile))
        }.getOrElse {
            logger.warn("extra.zip index unreadable; treating as empty (one missed prune cycle)", it)
            ExtraZipIndex()
        }
    }

    private fun writeIndex(indexFile: Path, index: ExtraZipIndex) {
        runCatching {
            Files.writeString(indexFile, indexJson.encodeToString(index))
        }.onFailure { logger.warn("Failed to persist extra.zip index", it) }
    }


    /**
     * Removes prefixes like "Industrial/mods/..." -> "mods/..."
     */
    internal fun normalizePath(rawPath: String): String {
        val parts = rawPath.split("/")
        if (parts.size < 2) return rawPath

        // First segment is a known root dir -> leave as-is. Otherwise the
        // first segment is a server-name prefix to strip.
        val root = parts[0]
        if (ClientRootDirs.isKnown(root)) return rawPath
        return rawPath.substring(root.length + 1)
    }

    internal fun calculateMD5(file: Path): String {
        val md = MessageDigest.getInstance("MD5")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cleanupIgnoredFiles(baseDir: Path, ignoredFiles: Set<String>) {
        if (ignoredFiles.isEmpty()) return

        val modsDir = baseDir.resolve("mods")
        if (!Files.exists(modsDir)) return

        var deletedCount = 0
        var failureCount = 0

        // Recursive walk covers both top-level mods/ and version subdirs
        // like mods/1.12.2/. A jar gets matched by basename so the
        // ignoredFiles set can contain plain names ("FoamFix.jar")
        // regardless of where the manifest currently places it. Catches
        // the case where SC moved the jar between top-level and a
        // version subdir across releases -- without this, the old copy
        // in the old location would survive and Forge would load a
        // user-disabled mod.
        try {
            Files.walk(modsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .forEach { file ->
                        val fileName = file.fileName.toString()
                        if (ignoredFiles.contains(fileName)) {
                            try {
                                Files.delete(file)
                                deletedCount++
                                logger.debug("Removed disabled mod jar: {}", file)
                            } catch (e: Exception) {
                                failureCount++
                                logger.warn("Failed to remove disabled mod jar: {}", file, e)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Error walking mods folder during cleanup", e)
        }

        if (deletedCount > 0 || failureCount > 0) {
            logger.info(
                "Disabled-mod cleanup: removed {}, failures {}",
                deletedCount, failureCount,
            )
        }
    }
}
