package hivens.launcher.legacy

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Turns one leftover client into a pack the launcher still understands.
 *
 * Local and never Mirror. A mirror pack is held to a manifest and a roster: the
 * launch checks what is in `mods/` against what the pack names, and refuses a
 * token when they disagree. An adopted tree has neither, so calling it a mirror
 * pack would be a claim nothing could check. Local is the honest shape -- a
 * directory of content its owner is responsible for -- and it is what the
 * importers already produce for every other outside source.
 *
 * Content is HARDLINKED rather than copied, which is the one place this differs
 * from [hivens.launcher.imports.ForeignInstanceImporter]. That one copies because
 * the foreign launcher keeps its own copy and an edit on one side must not bleed
 * into the other. Nothing owns `clients/` any more, so there is no other side --
 * and a real install is gigabytes, which a copy would demand twice over from a
 * disk that may not have it. The old tree stays readable until the sweep removes
 * it, and removing it then costs nothing, because the bytes are already shared.
 */
class RetiredClientAdopter(
    /**
     * Fills the shared roots for a version and loader, which is the one thing
     * this needs out of [hivens.launcher.runtime.RuntimeProvisioner]. Taken as a
     * function rather than the provisioner itself so the adoption can be exercised
     * over a real tree without standing up an HTTP stack to download a runtime the
     * assertions never look at. Answers the loader version it resolved, which the
     * adopted pack records, or null for vanilla.
     */
    private val ensureRuntime: suspend (mcVersion: String, loader: String?, progress: (Int, Int, String) -> Unit) -> String?,
    private val javaManager: IJavaManager,
    private val repository: IPackRepository,
    private val dataDir: Path,
    private val assetsDir: Path,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(RetiredClientAdopter::class.java)

    /**
     * What the adoption managed.
     *
     * [failed] is the number of content files that could not be placed, and it is
     * the reason this is a result rather than just an instance. The content is
     * hardlinked, so the source tree is redundant afterwards and the caller will
     * want to remove it -- but only if nothing was left behind. One unreadable
     * file means the instance is incomplete, and removing the source then would
     * turn a recoverable gap into a permanent one.
     */
    data class Adopted(
        val instance: PackInstance,
        val linked: Int,
        val failed: Int,
        /**
         * Bytes that are now a shared inode rather than a second copy.
         *
         * Removing the source frees none of these, which is the whole point of
         * hardlinking -- so a caller that reports reclaimed space has to subtract
         * them or it tells the player it recovered gigabytes it did not.
         */
        val sharedBytes: Long = 0L,
    ) {
        /** True when every file made it, so the source tree carries nothing unique. */
        val complete: Boolean get() = failed == 0
    }

    /**
     * Adopts [client] as a Local pack.
     *
     * [mcVersion] and [loader] are passed in rather than read off the client,
     * because what the tree says is a suggestion the reader has had the chance to
     * correct -- see [RetiredClient]. A blank version is refused here rather than
     * guessed: an instance provisioned against the wrong Minecraft is a pack that
     * fails at launch for a reason nobody can trace back to this moment.
     */
    suspend fun adopt(
        client: RetiredClient,
        mcVersion: String,
        loader: String?,
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, file: String) -> Unit = { _, _, _ -> },
    ): Adopted = withContext(io) {
        val mc = mcVersion.trim().takeIf { it.isNotEmpty() }
            ?: throw IOException("Cannot adopt '${client.name}' without a Minecraft version.")
        val loaderId = loader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "vanilla" }
        val instanceId = UUID.randomUUID().toString()
        // The id is appended after the cap rather than sanitized with the name:
        // inside it, a long folder name pushes the UUID out and two adoptions
        // reduce to one directory, where the second lands in the first's tree and
        // the sweep then removes a source whose content went nowhere.
        val instanceDirName = sanitize(client.name).take(NAME_BUDGET) + "-" + instanceId
        val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
        onReserveDir(clientDir)
        Files.createDirectories(clientDir)
        log.info("adopt: '{}' ({} on {}) -> {}", client.name, loaderId ?: "vanilla", mc, clientDir)

        var loaderVersion: String? = null
        val transfer = try {
            linkContent(client.dir, clientDir, progress).also {
                seedSharedAssets(client.dir)
                loaderVersion = ensureRuntime(mc, loaderId, progress)
            }
        } catch (e: Throwable) {
            // The directory was reserved and is now half-filled with hardlinks to
            // a source that is still there. Nothing lists it, nothing will ever
            // finish it, and a second attempt reserves another one under a new id
            // -- so it goes back before the failure is passed on. The links are
            // shared inodes, so removing them takes nothing from the source.
            discard(clientDir)
            throw e
        }

        val instance = PackInstance(
            id = instanceId,
            packRef = PackReference(origin = PackOrigin.Local, id = sanitize(client.name).lowercase(), version = null),
            displayName = client.name,
            instanceDirName = instanceDirName,
            createdAtEpoch = Instant.now().epochSecond,
            runtime = InstanceRuntime(),
            // A note, unlike the from-nothing creator's, because this one carries
            // something the owner has to know and cannot see: the content came
            // from a path that is gone, so nothing will update it and its mods are
            // now theirs to keep current.
            notes = "Adopted from the retired SmartyCraft client '${client.name}'. Nothing updates it now.",
            cachedManifest = CachedManifestSnapshot(
                minecraftVersion = mc,
                loaderName = loaderId ?: "vanilla",
                // Pinned to what the adoption resolved. Left blank it was "the latest"
                // on every launch, which moved and needed the network.
                loaderVersion = loaderVersion.orEmpty(),
                javaMajor = javaManager.detectJavaVersion(mc),
            ),
        )
        repository.put(instance)
        log.info(
            "adopt: registered instance {} with {} linked file(s), {} failed",
            instanceId, transfer.linked, transfer.failed,
        )
        if (transfer.failed > 0) {
            log.warn(
                "adopt: '{}' is incomplete -- {} file(s) could not be placed, so the source must be kept",
                client.name, transfer.failed,
            )
        }
        Adopted(instance, transfer.linked, transfer.failed, transfer.sharedBytes)
    }

    /** Removes a reservation that will never be finished. */
    private fun discard(dir: Path) {
        runCatching {
            if (!Files.exists(dir)) return
            Files.walk(dir).use { tree ->
                tree.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure { log.warn("adopt: could not remove the unfinished {}", dir, it) }
    }

    private data class Transfer(val linked: Int, val failed: Int, val sharedBytes: Long)

    /** What became of one file, which decides whether its bytes are shared. */
    private enum class Placement { Linked, Copied, Failed }

    /**
     * Hardlinks the client's own content, leaving behind everything the launcher
     * provisions for itself.
     *
     * The skip list is what a SmartyCraft client tree carries beside its content:
     * the unpacked and archived vanilla runtime, the per-version libraries root,
     * and -- on at least one real client -- a whole bundled JRE under `bin/` and
     * `lib/`, which is most of why that one is two gigabytes. None of it should
     * follow the content into an instance the shared provisioner fills properly.
     */
    private suspend fun linkContent(
        src: Path,
        dest: Path,
        progress: (Int, Int, String) -> Unit,
    ): Transfer {
        var linked = 0
        var failed = 0
        var shared = 0L
        val srcReal = runCatching { src.toRealPath() }.getOrDefault(src)
        fun place(from: Path, to: Path, label: String) {
            when (link(from, to)) {
                Placement.Linked -> {
                    linked++
                    shared += sizeOf(from)
                }
                Placement.Copied -> linked++
                Placement.Failed -> failed++
            }
            progress(linked, 0, label)
        }
        Files.newDirectoryStream(src).use { top ->
            for (child in top) {
                if (isRuntimeArtefact(child.name)) continue
                if (Files.isSymbolicLink(child)) {
                    if (!carryLink(child, dest.resolve(child.name), srcReal)) failed++
                    continue
                }
                if (child.isRegularFile()) {
                    place(child, dest.resolve(child.name), child.name)
                    continue
                }
                Files.walk(child).use { tree ->
                    for (path in tree) {
                        currentCoroutineContext().ensureActive()
                        val rel = src.relativize(path).toString()
                        val target = dest.resolve(rel)
                        when {
                            // A link is answered before the two predicates under
                            // it, because the walk does not follow links and both
                            // of those do. Asking a link whether it is a directory
                            // gets an answer about what it points at, and the walk
                            // never descends into it -- so the branch below would
                            // make an empty folder, count nothing as failed, and
                            // let the caller delete a tree it had not carried.
                            Files.isSymbolicLink(path) -> {
                                Files.createDirectories(target.parent)
                                if (!carryLink(path, target, srcReal)) failed++
                            }
                            path.isDirectory() -> Files.createDirectories(target)
                            path.isRegularFile() -> {
                                Files.createDirectories(target.parent)
                                place(path, target, rel)
                            }
                        }
                    }
                }
            }
        }
        return Transfer(linked, failed, shared)
    }

    /**
     * A link the player put there, carried across as a link.
     *
     * Worlds on a second disk are kept this way, and those bytes are not in the
     * client tree at all: pointing the new link at the same real file leaves the
     * content exactly where it was, and the source folder can still be let go.
     *
     * A link INTO the tree is refused instead. What it points at is what the sweep
     * is about to remove, so recreating it would hand the instance a path that
     * stops existing minutes later. Refusing counts as a file that did not make
     * it, which is what keeps the source and tells the reader to look.
     */
    private fun carryLink(link: Path, dest: Path, srcReal: Path): Boolean {
        val real = runCatching { link.toRealPath() }.getOrNull()
        if (real == null) {
            log.warn("adopt: link {} resolves to nothing, so the source must be kept", link)
            return false
        }
        if (real.startsWith(srcReal)) {
            log.warn("adopt: link {} points inside the tree the sweep would remove", link)
            return false
        }
        return runCatching { Files.createSymbolicLink(dest, real) }
            .onFailure { log.warn("adopt: could not recreate link {}", dest, it) }
            .isSuccess
    }

    /**
     * The client's vanilla assets into the shared root, where every pack of that
     * version reads them.
     *
     * Only when the tree actually carries the vanilla shape: a SmartyCraft client
     * unpacks `assets/` with `indexes/` and `objects/`, which is exactly what the
     * shared root holds, so the provisioner's later pass finds them present and
     * downloads nothing. Files already in the shared root are left alone.
     */
    private suspend fun seedSharedAssets(src: Path) {
        val assets = src.resolve("assets")
        if (!assets.resolve("objects").isDirectory()) return
        var linked = 0
        runCatching {
            Files.walk(assets).use { tree ->
                for (path in tree) {
                    currentCoroutineContext().ensureActive()
                    if (!path.isRegularFile() || Files.isSymbolicLink(path)) continue
                    val target = assetsDir.resolve(assets.relativize(path).toString())
                    if (Files.exists(target)) continue
                    Files.createDirectories(target.parent)
                    if (link(path, target) != Placement.Failed) linked++
                }
            }
        }.onFailure { log.warn("adopt: could not seed shared assets from {}", assets, it) }
        if (linked > 0) log.info("adopt: seeded {} asset file(s) into the shared root", linked)
    }

    /**
     * A shared inode where the filesystem allows it, a copy where it does not.
     * Answers whether the file is now at [dest], because the caller decides
     * whether the source can be let go on exactly that.
     */
    private fun link(src: Path, dest: Path): Placement =
        try {
            Files.createLink(dest, src)
            Placement.Linked
        } catch (_: UnsupportedOperationException) {
            copy(src, dest)
        } catch (e: java.nio.file.FileSystemException) {
            // A file already at the target is NOT this adoption having placed it.
            // The walk yields every path once, so anything there came from outside
            // -- a previous run that stopped halfway, or another folder that
            // reduced to the same directory. Reading that as success is how a
            // source gets deleted for content nothing carried.
            if (e is java.nio.file.FileAlreadyExistsException) {
                log.warn("adopt: {} already exists and is not this adoption's doing", dest)
                Placement.Failed
            } else {
                // Cross-device (EXDEV), or a filesystem with no hardlinks at all.
                copy(src, dest)
            }
        }

    private fun copy(src: Path, dest: Path): Placement =
        runCatching { Files.copy(src, dest) }
            .onFailure { log.warn("adopt: could not place {}", dest, it) }
            .fold({ Placement.Copied }, { Placement.Failed })

    private fun sizeOf(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0L)

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    internal companion object {
        /**
         * How much of the folder name the instance directory keeps, leaving room
         * for the separator and the 36-character id that makes it unique.
         */
        private const val NAME_BUDGET = 59

        /**
         * Top-level names that are the retired path's own runtime rather than the
         * player's content, matched case-insensitively.
         *
         * `bin` and `lib` are on it for a reason worth stating: on a modern
         * SmartyCraft client they are not game natives but a bundled Windows JRE,
         * and carrying that into an instance would move a gigabyte of someone
         * else's Java into a folder the launcher provisions its own for.
         */
        private val RUNTIME_DIRS = setOf(
            "assets", "libraries", "versions", "bin", "lib", "natives", "runtime",
            "logs", "crash-reports", "cache", "downloads", ".fabric", ".mixin.out", ".quilt",
        )

        private val RUNTIME_FILES = setOf("extra.zip", "assets.zip", "natives.zip", "fabricloader.log")

        private val VERSIONED_DIR = Regex("""(libraries|natives)-[0-9][0-9.]*""")
        private val VERSIONED_ZIP = Regex("""(assets|natives)-[0-9][0-9.]*\.zip""")

        internal fun isRuntimeArtefact(name: String): Boolean {
            val lower = name.lowercase()
            return lower in RUNTIME_DIRS ||
                lower in RUNTIME_FILES ||
                VERSIONED_DIR.matches(lower) ||
                VERSIONED_ZIP.matches(lower)
        }
    }
}
