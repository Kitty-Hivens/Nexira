package hivens.core.net

import java.nio.file.Path

/**
 * The files a transfer keeps beside the one it is fetching: the staging file the
 * bytes land in before they are proven, and the journal recording which of its
 * blocks arrived.
 *
 * The naming is shared rather than spelled at each site because anything that
 * reports on an instance's content has to be able to tell the launcher's own
 * bookkeeping from the files a person installed -- and a second copy of ".part"
 * elsewhere is a rule that only holds until one of the two is edited.
 */
object TransferStaging {
    /** Appended to the destination name while its bytes are still in flight. */
    const val PARTIAL_SUFFIX = ".part"

    /** Appended to the staging file's name for the journal that describes it. */
    const val JOURNAL_SUFFIX = ".state"

    /** The staging file [dest] is downloaded through. */
    fun partialOf(dest: Path): Path = dest.resolveSibling("${dest.fileName}$PARTIAL_SUFFIX")

    /** The journal describing [partial]. */
    fun journalOf(partial: Path): Path = partial.resolveSibling("${partial.fileName}$JOURNAL_SUFFIX")

    /** True when [fileName] is a staging file or its journal. */
    fun isStaging(fileName: String): Boolean =
        fileName.endsWith(PARTIAL_SUFFIX) || fileName.endsWith(PARTIAL_SUFFIX + JOURNAL_SUFFIX)
}
