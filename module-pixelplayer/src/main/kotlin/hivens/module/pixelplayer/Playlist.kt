package hivens.module.pixelplayer

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * What the widget will play, resolved from a folder.
 *
 * Pure and file-system-only: no decoding, no engine, so the rules that decide
 * what counts as a track are testable without natives or an audio device.
 */
internal object Playlist {

    /**
     * Every file under [root] whose extension is in [extensions], sorted by path
     * so the order is the same on every start.
     *
     * [recursive] is a real question rather than a convenience: a library folder
     * is a tree of album directories, while a folder of loose files is not, and
     * walking one as if it were the other either finds nothing or finds a
     * thousand things.
     *
     * Extensions are matched without their dot and case-insensitively, and the
     * list is what to INCLUDE. That is the useful direction for a folder like
     * a rhythm-game song library, where the audio worth listening to sits beside
     * per-format duplicates nobody wants queued.
     */
    fun scan(root: Path, recursive: Boolean, extensions: Set<String>): List<Path> {
        if (!root.isDirectory()) return emptyList()
        val wanted = extensions.map { it.removePrefix(".").lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return emptyList()

        val depth = if (recursive) Int.MAX_VALUE else 1
        // FOLLOW_LINKS, because `~/Music -> /mnt/media/music` is an ordinary
        // layout and without it the walk reads the start node as a plain symlink,
        // emits that one entry and reports an empty library with no diagnostic.
        // Cycles are the price: the walk reports one rather than spinning, and a
        // failure here already resolves to an empty list.
        return Files.walk(root, depth, FileVisitOption.FOLLOW_LINKS).use { stream ->
            stream.filter { !it.isDirectory() }
                .filter { it.extension.lowercase() in wanted }
                .sorted()
                .toList()
        }
    }

    /** `"mp3, .FLAC ,, wav"` -> `{mp3, flac, wav}`. Tolerant because a user types this into a text field. */
    fun parseExtensions(raw: String): Set<String> =
        raw.split(',', ';', ' ')
            .map { it.trim().removePrefix(".").lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** The file name without its extension: what to show when the file carries no title tag. */
    fun titleOf(path: Path): String = path.name.substringBeforeLast('.', path.name)
}
