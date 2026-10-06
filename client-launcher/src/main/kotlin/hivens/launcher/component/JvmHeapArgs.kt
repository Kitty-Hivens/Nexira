package hivens.launcher.component

/**
 * The heap flags a person typed into an instance's JVM arguments.
 *
 * What is typed by hand wins over what the launcher would decide, the memory
 * setting and the adaptive sizer included. The command builder used to append
 * its own `-Xms` and `-Xmx` after the typed ones, and the JVM takes the last
 * occurrence of a flag, so a typed `-Xmx6G` was on the command line and never
 * applied. Any of these present means the builder adds no heap flags at all:
 * dropping only the matching one could pair a typed maximum with a builder
 * minimum above it, which the JVM refuses to start with.
 */
object JvmHeapArgs {

    private val SIZED = Regex("^-X(mx|ms)[0-9].*$")

    private val XX_NAMES = setOf(
        "MaxHeapSize", "InitialHeapSize", "MinHeapSize",
        "MaxRAM", "MaxRAMPercentage", "InitialRAMPercentage", "MinRAMPercentage",
    )

    /** The heap flags among [args], in the order they were given. */
    fun inArgs(args: List<String>): List<String> = args.filter(::isHeapFlag)

    /** The heap flags in a raw arguments field, split the way the launch splits it. */
    fun inArgs(raw: String?): List<String> =
        inArgs(raw?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")).orEmpty())

    fun isHeapFlag(arg: String): Boolean =
        SIZED.matches(arg) || (arg.startsWith("-XX:") && arg.removePrefix("-XX:").substringBefore('=') in XX_NAMES)
}
