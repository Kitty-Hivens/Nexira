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
import java.nio.file.StandardCopyOption
import java.time.Instant
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
     * assertions never look at.
     */
    private val ensureRuntime: suspend (mcVersion: String, loader: String?, progress: (Int, Int, String) -> Unit) -> Unit,
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
    data class Adopted(val instance: PackInstance, val linked: Int, val failed: Int) {
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
        val instanceDirName = sanitize("${client.name}-$instanceId")
        val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
        onReserveDir(clientDir)
        Files.createDirectories(clientDir)
        log.info("adopt: '{}' ({} on {}) -> {}", client.name, loaderId ?: "vanilla", mc, clientDir)

        val transfer = linkContent(client.dir, clientDir, progress)
        seedSharedAssets(client.dir)
        ensureRuntime(mc, loaderId, progress)

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
                loaderVersion = "",
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
        Adopted(instance, transfer.linked, transfer.failed)
    }

    private data class Transfer(val linked: Int, val failed: Int)

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
        fun place(from: Path, to: Path, label: String) {
            if (link(from, to)) linked++ else failed++
            progress(linked, 0, label)
        }
        Files.newDirectoryStream(src).use { top ->
            for (child in top) {
                if (isRuntimeArtefact(child.name)) continue
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
        return Transfer(linked, failed)
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
                    if (link(path, target)) linked++
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
    private fun link(src: Path, dest: Path): Boolean =
        try {
            Files.createLink(dest, src)
            true
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Already placed by an earlier pass over the same tree.
            true
        } catch (_: UnsupportedOperationException) {
            copy(src, dest)
        } catch (_: java.nio.file.FileSystemException) {
            // Cross-device (EXDEV), or a filesystem with no hardlinks at all.
            copy(src, dest)
        }

    private fun copy(src: Path, dest: Path): Boolean =
        runCatching { Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { log.warn("adopt: could not place {}", dest, it) }
            .isSuccess

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    internal companion object {
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
