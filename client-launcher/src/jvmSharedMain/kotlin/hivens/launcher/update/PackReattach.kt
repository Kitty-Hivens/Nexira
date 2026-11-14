package hivens.launcher.update

import hivens.core.data.PackInstance
import hivens.core.data.PackReference
import hivens.core.update.CompatChange
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What putting a detached pack back on its source will do to its files, measured
 * against the source build it will land on. Paths are relative to the instance.
 *
 * Lists only what will change. A reattach moves the pack the way its source's
 * ordinary update does, with `mods/` held strictly to the build: a file the player
 * edited that the build did not change keeps the edit and is not listed, and the
 * mods are the build's whatever was done to them. Worlds, screenshots and settings
 * the build does not ship are none of the update's business and are not listed.
 */
data class ReattachPreview(
    /**
     * The build as the reattach is told it: the source's own key where its labels
     * are not unique (a Modrinth version id), its label otherwise.
     */
    val build: String,
    /** The source build the pack lands on, as a player reads it. */
    val toVersion: String,
    /** How far the build moves the pack's Minecraft and loader, graded the way an update is. */
    val compat: CompatChange,
    /** Files that go: mods and mod folders the build does not ship, and files the update retires. */
    val removed: List<String>,
    /** Files the build ships that are not on disk, which come back. */
    val restored: List<String>,
    /** Files the build ships that are on disk with other bytes, which the source's copy replaces. */
    val replaced: List<String>,
    /** Mods whose bytes are right but sit switched off, which the build switches back on. */
    val turnedOn: List<String> = emptyList(),
    /** Optional mods the pack's choice has off but which sit switched on, which the build switches off. */
    val turnedOff: List<String> = emptyList(),
    /**
     * Files the player edited that the build changed too: the player's copy stays
     * and the build's lands beside it as `<path>.new`, as an ordinary update does.
     */
    val kept: List<String> = emptyList(),
    /** What the comparison could tell the player's edits from the build's by. */
    val basis: ReattachBasis = ReattachBasis.Full,
    /**
     * Files in `mods/` that would not open to be judged. While any are left the
     * reattach is refused: one of them might be the build's own, and a file nothing
     * could read is never deleted on a guess.
     */
    val unreadable: List<String> = emptyList(),
) {
    /** Everything that comes back from the source, missing or changed. */
    val returned: List<String> get() = (restored + replaced).sorted()

    /** Whether reattaching changes no file, only the record. */
    val isEmpty: Boolean
        get() = removed.isEmpty() && restored.isEmpty() && replaced.isEmpty() && turnedOn.isEmpty() && turnedOff.isEmpty() &&
            kept.isEmpty()

    /**
     * What this preview showed, in one value: the build, its grade and every path
     * under the heading it was listed under. Two previews with the same fingerprint
     * would have told the player the same thing.
     */
    val fingerprint: String
        get() {
            val lines = buildList {
                add("build=$build")
                add("version=$toVersion")
                add("compat=$compat")
                removed.sorted().forEach { add("removed:$it") }
                restored.sorted().forEach { add("restored:$it") }
                replaced.sorted().forEach { add("replaced:$it") }
                turnedOn.sorted().forEach { add("on:$it") }
                turnedOff.sorted().forEach { add("off:$it") }
                kept.sorted().forEach { add("kept:$it") }
                add("basis=$basis")
                unreadable.sorted().forEach { add("unreadable:$it") }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

    /** What a player agrees to by confirming this preview, to hand to the reattach. */
    val confirmation: ReattachConfirmation get() = ReattachConfirmation(build, fingerprint)
}

/** What a reattach comparison had to go on, which the player is told when it is less than everything. */
enum class ReattachBasis {
    /** The record of what the build placed, and the identity of each of its mods. */
    Full,

    /**
     * The record of what the build placed, but not the build the pack was detached
     * from, so a mod renamed between builds is matched by its file name only.
     */
    NamesOnly,

    /**
     * No record of what the build placed: the player's edits cannot be told from the
     * build's, so every file that differs from the build is replaced.
     */
    NoBaseline,
}

/**
 * A preview the player agreed to: the build it measured and its
 * [ReattachPreview.fingerprint]. A reattach runs only on one of these, and only
 * while the pack still measures the same against that build.
 */
data class ReattachConfirmation(val build: String, val fingerprint: String)

/**
 * The pack no longer measures against the build the way the confirmed preview
 * said: its files changed, the build changed, or no preview was confirmed at all.
 * Nothing was touched. A new preview says what a reattach would do now.
 */
class ReattachPreviewStaleException(instanceId: String) :
    IllegalStateException("pack $instanceId changed since the reattach was previewed; nothing was touched, preview it again")

/**
 * Files in the pack's `mods/` would not open to be judged, so the reattach was
 * refused and nothing was touched. [paths] names them, relative to the instance.
 */
class ReattachUnreadableException(instanceId: String, val paths: List<String>) :
    IllegalStateException("pack $instanceId has files that could not be read: ${paths.joinToString()}; nothing was touched")

/**
 * Puts a pack detached from one source back on a build of that source. One per
 * source that can, chosen by the origin of [PackInstance.forkedFrom].
 *
 * The pack moves to the build the way that source's ordinary update moves it, so a
 * config the player edited and the build did not change keeps the edit. `mods/` is
 * held strictly to the build: what the player added is removed, what they removed
 * or changed comes back, and no mod of theirs is kept as a layer over the build. A
 * pack whose mods are its owner's is a fork, and a fork is published as its own pack.
 */
interface PackReattacher {
    /**
     * What reattaching [instance] to [source] would remove and bring back, against
     * the build it would land on. Reads only.
     */
    suspend fun previewReattach(instance: PackInstance, source: PackReference): ReattachPreview

    /**
     * Brings [instance]'s files to the build [confirmed] names and writes the record
     * back onto [source], the way an install of that build writes it. Returns the
     * record as written.
     *
     * The preview is measured again under the instance's lock before anything is
     * written, and a pack that no longer measures as [confirmed] says is refused
     * with [ReattachPreviewStaleException]: the player agreed to a list, and the
     * reattach does that list or nothing.
     */
    suspend fun reattach(
        instance: PackInstance,
        source: PackReference,
        confirmed: ReattachConfirmation,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): PackInstance

    /** What the source calls the pack, for a player to read, or null when it cannot say. */
    suspend fun sourceName(source: PackReference): String?

    /**
     * Lets go of whatever [instance]'s finished previews kept for a reattach that is
     * no longer coming: nobody wants them any more. A reattach that already took over
     * what a preview kept is not affected.
     */
    suspend fun discardPreview(instance: PackInstance) = Unit
}

/**
 * Refuses a reattach whose fresh measurement is not the one the player confirmed.
 * Called before the first write.
 */
internal fun ReattachPreview.requireConfirmed(confirmed: ReattachConfirmation, instanceId: String) {
    if (build != confirmed.build || fingerprint != confirmed.fingerprint) throw ReattachPreviewStaleException(instanceId)
    if (unreadable.isNotEmpty()) throw ReattachUnreadableException(instanceId, unreadable)
}

/**
 * The source's own choice of build for a reattach: the build the pack is pinned
 * to when its player stopped following the latest and the source still has it,
 * otherwise the latest.
 *
 * [PackInstance.pinnedPackVersion] always names the installed build, so it is a
 * pin only once [PackInstance.followLatest] is off. [isListed] says whether the
 * source still serves a build.
 */
internal suspend fun PackInstance.reattachPin(isListed: suspend (String) -> Boolean): String? {
    if (followLatest) return null
    val pin = installedBuildKey ?: pinnedPackVersion ?: return null
    return pin.takeIf { isListed(it) }
}

/**
 * This record back on [source] at [version]: the source restored as the pack's
 * reference and the fork note dropped. The build fields are the caller's, taken
 * from the build it placed, the way an install takes them.
 */
internal fun PackInstance.backOn(source: PackReference, version: String?): PackInstance =
    copy(packRef = source.copy(version = version ?: source.version), forkedFrom = null)

/**
 * [paths] relative to [clientDir], each folder among them replaced by everything
 * under it: its files, its subfolders, empty ones included, and its links, taken as
 * links. A snapshot of a reattach has to give a removed mod folder back whole, and
 * a link the reattach removes is the link, never what it points at.
 */
internal fun filesUnder(clientDir: Path, paths: Collection<String>): Set<String> {
    val root = clientDir.normalize()
    val out = LinkedHashSet<String>()
    for (rel in paths) {
        val path = root.resolve(rel).normalize()
        if (!path.startsWith(root)) continue
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(path).use { walk ->
                walk.forEach { out += root.relativize(it).joinToString("/") }
            }
        } else {
            out += rel
        }
    }
    return out
}

/**
 * Where a reattach keeps the source build's archive between its preview and its
 * apply, for a source that publishes a build as one: under the data directory
 * rather than the system's temporary one, which is memory on many systems, and
 * per instance, so deleting the instance can take it.
 */
fun reattachArchiveDir(dataDir: Path, instanceDirName: String): Path =
    reattachArchiveRoot(dataDir).resolve(instanceDirName)

/** Where every instance's [reattachArchiveDir] sits, swept at startup since no reattach outlives its process. */
fun reattachArchiveRoot(dataDir: Path): Path = dataDir.resolve("downloads").resolve("reattach")
