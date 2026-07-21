package hivens.launcher.update

import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.fileManifestOf
import hivens.core.data.flatten
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.Json

/**
 * Reads, writes and computes the [FileManifest] of an install layout. The manifest is
 * a flat `relative/path -> FileData{sha1, sha256, size}` map (as a directory tree),
 * with `/` separators and no leading slash, so it round-trips through [flatten] and
 * feeds [hivens.core.update.LauncherUpdatePlanner] directly.
 *
 * [scan] hashes each file once for both digests. [staging] and the manifest file
 * itself are never part of a scan -- they are update scaffolding, not shipped content.
 */
object LayoutManifest {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    /** Walk [root] into a manifest, excluding [excludes] subtrees (by real path prefix). */
    fun scan(root: Path, excludes: Set<Path> = emptySet()): FileManifest {
        if (!Files.isDirectory(root)) return FileManifest()
        val exclude = excludes.map { it.toAbsolutePath().normalize() }
        val entries = LinkedHashMap<String, FileData>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .filter { file ->
                    val abs = file.toAbsolutePath().normalize()
                    exclude.none { abs.startsWith(it) }
                }
                .sorted()
                .forEach { file ->
                    val rel = root.relativize(file).joinToString("/") { it.toString() }
                    val (sha1, sha256) = hash(file)
                    entries[rel] = FileData(size = Files.size(file), sha1 = sha1, sha256 = sha256)
                }
        }
        return fileManifestOf(entries)
    }

    fun read(file: Path): FileManifest? =
        if (Files.exists(file)) runCatching { json.decodeFromString<FileManifest>(Files.readString(file)) }.getOrNull()
        else null

    fun write(file: Path, manifest: FileManifest) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(manifest))
    }

    /** SHA-1 and SHA-256 of [file] in one read pass. */
    private fun hash(file: Path): Pair<String, String> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                sha1.update(buf, 0, n)
                sha256.update(buf, 0, n)
            }
        }
        return sha1.digest().toHex() to sha256.digest().toHex()
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) append("%02x".format(b.toInt() and 0xff))
    }
}
