package hivens.core.data

/**
 * Well-formedness of the identity a [PackInstance] carries, checked on the way
 * into a repository.
 *
 * A pack is identified by the pair (origin, id); an instance points at one
 * through [PackInstance.packRef], and at a second through
 * [PackInstance.forkedFrom] when it was forked from somewhere. Nothing used to
 * hold a write to that. A reference with an empty id persisted as readily as a
 * real one and surfaced much later, as a Library entry the catalogue cannot be
 * asked about and an update check with no id to send.
 *
 * The rule is structural on purpose. Resolving a reference against the pack
 * catalogue would put a network round trip on every registry write, and the
 * catalogues are remote: an instance whose origin is unreachable this minute is
 * still a legitimate instance. What is answerable offline is whether every
 * identifier the instance carries is an identifier at all.
 *
 * A version is either a pin or absent. The blank string is neither, and it
 * reads as a pin everywhere it is displayed or compared, so it is rejected
 * rather than normalised here -- the installers already resolve an empty
 * upstream version to null, and this holds them to it.
 */
object PackIdentity {

    /**
     * True when [reference] names a pack: an id, and a version that either
     * pins something or floats.
     */
    fun isValid(reference: PackReference): Boolean =
        reference.id.isNotBlank() && reference.version?.isBlank() != true

    /** True when every identifier on [instance] is well formed. */
    fun isValid(instance: PackInstance): Boolean = violation(instance) == null

    /**
     * @return [instance] verbatim when its identity is well formed.
     * @throws IllegalArgumentException naming the field that is not. A dangling
     *         reference can only be built by launcher code, never typed in by a
     *         user, so it is a programming error and reads as one.
     */
    fun require(instance: PackInstance): PackInstance {
        val violation = violation(instance)
        require(violation == null) {
            "Rejected pack instance ${instance.id.ifBlank { "<no id>" }}: $violation"
        }
        return instance
    }

    /**
     * [instance] with the repairable half of a malformed identity put right: a
     * blank version is what an install wrote when its source declared none, and
     * it already means what an absent one means.
     *
     * Applied when a registry loads, so an entry written before [require]
     * existed keeps working instead of failing every write made against it
     * afterwards. Anything left invalid after this could only have been built
     * wrong in code, and [require] says so.
     */
    fun normalize(instance: PackInstance): PackInstance = instance.copy(
        packRef = instance.packRef.withoutBlankVersion(),
        forkedFrom = instance.forkedFrom?.withoutBlankVersion(),
        pinnedPackVersion = instance.pinnedPackVersion?.ifBlank { null },
    )

    private fun PackReference.withoutBlankVersion(): PackReference =
        if (version?.isBlank() == true) copy(version = null) else this

    private fun violation(instance: PackInstance): String? = when {
        instance.id.isBlank() -> "instance id is blank"
        instance.instanceDirName.isBlank() -> "instanceDirName is blank"
        !isValid(instance.packRef) -> "packRef does not name a pack"
        instance.forkedFrom?.let { !isValid(it) } == true -> "forkedFrom does not name a pack"
        instance.pinnedPackVersion?.isBlank() == true -> "pinnedPackVersion is blank"
        else -> null
    }
}
