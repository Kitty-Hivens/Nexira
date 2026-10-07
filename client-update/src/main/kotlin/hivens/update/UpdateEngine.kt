package hivens.update

import hivens.core.data.FileManifest
import hivens.core.data.flatten
import hivens.core.update.FileAction
import hivens.core.update.LauncherPatch
import hivens.core.update.LauncherUpdatePlan
import hivens.core.io.AtomicFiles
import hivens.core.io.resolveWithinRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Where staged update bytes come from -- whole files and binary patches. Abstracted
 * so the engine is testable against a local fixture and the real implementation maps
 * to GitHub release assets. Throws on a failed fetch.
 */
interface AssetSource {
    /** Fetch the whole target file for [path] into [dest]. */
    fun fetchFile(path: String, dest: Path)
    /** Fetch the binary [patch] delta into [dest]. */
    fun fetchPatch(patch: LauncherPatch, dest: Path)
}

/** A verified, ready-to-apply update: staged files by relative path + the files to remove. */
data class StagedUpdate(val staged: Map<String, Path>, val deletes: List<String>)

class UpdateVerifyException(path: String, expected: String, actual: String) :
    RuntimeException("integrity check failed for $path: expected $expected, got $actual")

/**
 * Downloads and patches an update into [InstallLayout.stagingDir], verifying every
 * produced file's sha256 against the remote manifest BEFORE anything touches the live
 * layout. A failed verify aborts the whole update (fail-closed) -- a patch that
 * mis-applied, a truncated download or a tampered asset never reaches the install.
 */
class UpdateStager(private val layout: InstallLayout, private val source: AssetSource) {
    private val log = LoggerFactory.getLogger(UpdateStager::class.java)

    fun stage(plan: LauncherUpdatePlan, remote: FileManifest): StagedUpdate {
        val staging = layout.stagingDir
        // The files an interrupted apply has yet to move are in staging, and a
        // resume reads a missing one as already moved. Clearing it now would have
        // the resume record a version whose files never arrived.
        check(!Files.exists(layout.applyMarker)) {
            "an interrupted update has not been finished yet; staging still holds its files"
        }
        cleanStaging(staging)
        Files.createDirectories(staging)
        val remoteFlat = remote.flatten()
        val staged = LinkedHashMap<String, Path>()

        // Every path below comes from the remote manifest, so each is held inside the
        // directory it is meant for: a `..` in a release document must not reach
        // outside staging or read a file from outside the install.
        for (action in plan.actions) when (action) {
            is FileAction.Download -> {
                val dest = resolveWithinRoot(staging, action.path)
                dest.parent?.let { Files.createDirectories(it) }
                source.fetchFile(action.path, dest)
                verify(dest, remoteFlat[action.path]?.sha256, action.path)
                staged[action.path] = dest
            }

            is FileAction.Patch -> {
                val patchTmp = resolveWithinRoot(staging, action.path + ".patch", action.path)
                patchTmp.parent?.let { Files.createDirectories(it) }
                source.fetchPatch(action.patch, patchTmp)
                val dest = resolveWithinRoot(staging, action.path)
                // Patch the CURRENTLY installed file into the target.
                BinaryPatch.apply(resolveWithinRoot(layout.root, action.path), patchTmp, dest)
                Files.deleteIfExists(patchTmp)
                // Verify the RESULT, not the patch -- a wrong patch cannot slip through.
                verify(dest, remoteFlat[action.path]?.sha256, action.path)
                staged[action.path] = dest
            }

            is FileAction.Delete -> Unit // handled by the applier
        }

        log.info("staged {} files ({} to delete)", staged.size, plan.deletes.size)
        return StagedUpdate(staged, plan.deletes.map { it.path })
    }

    private fun verify(file: Path, expectedSha256: String?, path: String) {
        if (expectedSha256.isNullOrEmpty()) throw UpdateVerifyException(path, "<sha256 in manifest>", "<missing>")
        val actual = LayoutManifest.sha256Of(file)
        if (!actual.equals(expectedSha256, ignoreCase = true)) throw UpdateVerifyException(path, expectedSha256, actual)
    }

    private fun cleanStaging(staging: Path) {
        if (!Files.exists(staging)) return
        Files.walk(staging).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

/** The durable record of an in-flight apply, resumable after a crash. */
@Serializable
internal data class ApplyCommit(
    val version: String,
    val moves: List<String>,   // relative paths staged and to be moved into the layout
    val deletes: List<String>, // relative paths to remove
)

/**
 * Commits a [StagedUpdate] into the live [InstallLayout]: atomically moves each staged
 * file over the live one, deletes removed files, then writes the new manifest.json and
 * version. A commit marker (written before the first move) makes the apply resumable --
 * a crash mid-apply is finished on the next start via [recover], never leaving a
 * half-updated layout.
 */
class LayoutApplier(private val layout: InstallLayout) {
    private val log = LoggerFactory.getLogger(LayoutApplier::class.java)
    private val json = Json { encodeDefaults = true }
    private val marker: Path get() = layout.applyMarker

    /** An apply started moving files and has not committed; [recover] finishes it. */
    val hasPendingApply: Boolean get() = Files.exists(marker)

    fun apply(staged: StagedUpdate, newManifest: FileManifest, newVersion: String) {
        val pending = ApplyCommit(newVersion, staged.staged.keys.toList(), staged.deletes)
        // Before the marker, so a path that would leave the install refuses the
        // whole apply while nothing has moved yet.
        targetsOf(pending)
        Files.createDirectories(layout.stagingDir)
        AtomicFiles.writeString(marker, json.encodeToString(ApplyCommit.serializer(), pending))
        commit(newManifest)
    }

    /** Finish an interrupted apply, if a marker is present. Idempotent. */
    fun recover(remoteManifestFor: (String) -> FileManifest?) {
        val m = marker
        if (!Files.exists(m)) return
        val commit = runCatching { json.decodeFromString(ApplyCommit.serializer(), Files.readString(m)) }.getOrNull() ?: run {
            Files.deleteIfExists(m); return
        }
        val manifest = remoteManifestFor(commit.version)
        if (manifest == null) {
            // Kept for the next start. Dropping it left the layout half applied with
            // the old manifest recorded, and every delta after that patched files
            // that were already new and failed verification for good.
            log.warn("apply recovery: no manifest for {} yet, keeping the marker for the next attempt", commit.version)
            return
        }
        log.info("resuming interrupted apply to {}", commit.version)
        commitFrom(commit, manifest)
    }

    // No version parameter: the marker written a moment ago carries it, and
    // [commitFrom] reads the version from there so the resumed path and this one
    // cannot disagree about which version was applied.
    private fun commit(newManifest: FileManifest) {
        val commit = json.decodeFromString(ApplyCommit.serializer(), Files.readString(marker))
        commitFrom(commit, newManifest)
    }

    /**
     * Every path [commit] touches, resolved and held inside its directory. Throws
     * on the first one that leaves it, before anything is moved.
     */
    private fun targetsOf(commit: ApplyCommit): Pair<List<Pair<Path, Path>>, List<Path>> {
        val moves = commit.moves.map { rel ->
            resolveWithinRoot(layout.stagingDir, rel) to resolveWithinRoot(layout.root, rel)
        }
        val deletes = commit.deletes.map { rel -> resolveWithinRoot(layout.root, rel) }
        return moves to deletes
    }

    private fun commitFrom(commit: ApplyCommit, newManifest: FileManifest) {
        val (moves, deletes) = targetsOf(commit)
        // Moves: a staged file still present is moved in; one already moved (resumed
        // run) is simply absent from staging and skipped.
        for ((from, to) in moves) {
            if (!Files.exists(from)) continue
            to.parent?.let { Files.createDirectories(it) }
            moveInto(from, to)
        }
        for (target in deletes) {
            runCatching { Files.deleteIfExists(target) }
                .onFailure { log.warn("apply: failed to delete {}", target, it) }
        }
        // The Leyden AOT cache is built against the app jar's exact bytes. If this apply
        // changed the jar, the shipped cache is stale -- drop it so startup regenerates a
        // fresh one instead of loading a mismatched (ignored or misbehaving) cache.
        val appJarRel = layout.root.relativize(layout.appJar).joinToString("/") { it.toString() }
        if (appJarRel in commit.moves) {
            runCatching { Files.deleteIfExists(layout.aotCache) }
                .onFailure { log.warn("apply: failed to invalidate AOT cache", it) }
        }
        LayoutManifest.write(layout.manifestFile, newManifest)
        AtomicFiles.writeString(layout.versionFile, commit.version)
        Files.deleteIfExists(marker)
        cleanStaging()
        log.info("apply committed: version {}", commit.version)
    }

    private fun moveInto(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // Cross-device or an FS without atomic move: fall back to a plain replace.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanStaging() {
        val staging = layout.stagingDir
        if (!Files.exists(staging)) return
        Files.walk(staging).use { s ->
            s.sorted(Comparator.reverseOrder())
                .filter { it != staging } // keep the dir itself
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
