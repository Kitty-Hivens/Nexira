package hivens.widget.api

/**
 * Marks what only the host calls: making and closing levels, keeping page levels,
 * retrying failed factories, and writing the registries directly.
 *
 * A module reaches these types through the composition, so it can see them, but
 * calling one from a widget breaks what the host relies on: a level closed under
 * its owner, a registry entry written twice. A module declares its keys through
 * [KeyContribution] instead, and reads and fires them through the `remember`
 * helpers. The host opts in where it wires the tree.
 */
@RequiresOptIn(
    message = "Host plumbing. A module declares its keys through KeyContribution and reads them through the remember helpers.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
annotation class HostApi
