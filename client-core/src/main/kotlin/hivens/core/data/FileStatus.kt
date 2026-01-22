package hivens.core.data

/**
 * Determines the status of a local file during an integrity check.
 */
enum class FileStatus {
    /** The file is missing from the disk. */
    MISSING,

    /** The file is present, but the hash does not match (corrupt/outdated). */
    MISMATCH,

    /** The file is present and the hash sum matches (valid). */
    VALID
}
