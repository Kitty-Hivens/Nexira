package hivens.launcher.component

/**
 * The heap flags a person typed into an instance's JVM arguments.
 *
 * What is typed by hand wins over what the launcher would decide, the memory
 * setting and the adaptive sizer included. The command builder used to append
 * its own `-Xms` and `-Xmx` after the typed ones, and the JVM takes the last
 * occurrence of a flag, so a typed `-Xmx6G` was on the command line and never
 * applied.
 *
 * A typed maximum and a typed minimum are told apart, because they ask for
 * different things. A maximum in any form means the builder adds no heap flags at
 * all: adding only a minimum could put it above the typed maximum, which the JVM
 * refuses to start with. A minimum alone still leaves the ceiling to the memory
 * setting, raised to the typed minimum if that is higher: dropping the ceiling
 * with it handed the game the JVM's own default, a quarter of the machine.
 */
object JvmHeapArgs {

    private val SIZED_MAX = Regex("^-Xmx[0-9].*$")
    private val SIZED_MIN = Regex("^-Xms[0-9].*$")

    private val XX_MAX = setOf("MaxHeapSize", "MaxRAM", "MaxRAMPercentage", "MaxRAMFraction")
    private val XX_MIN = setOf(
        "InitialHeapSize", "MinHeapSize", "InitialRAMPercentage", "MinRAMPercentage", "InitialRAMFraction", "MinRAMFraction",
    )

    /** Every heap flag among [args], maximum or minimum, in the order they were given. */
    fun inArgs(args: List<String>): List<String> = args.filter(::isHeapFlag)

    /** The heap flags in a raw arguments field, split the way the launch splits it. */
    fun inArgs(raw: String?): List<String> = inArgs(split(raw))

    /** The flags among [args] that set the heap's ceiling. */
    fun maxIn(args: List<String>): List<String> = args.filter { SIZED_MAX.matches(it) || xxName(it) in XX_MAX }

    /** [maxIn] over a raw arguments field. */
    fun maxIn(raw: String?): List<String> = maxIn(split(raw))

    /** The flags among [args] that set the heap's starting or least size. */
    fun minIn(args: List<String>): List<String> = args.filter { SIZED_MIN.matches(it) || xxName(it) in XX_MIN }

    fun isHeapFlag(arg: String): Boolean = arg in maxIn(listOf(arg)) || arg in minIn(listOf(arg))

    /**
     * The largest typed minimum in megabytes, where it is written as a size
     * (`-Xms2G`, `-XX:InitialHeapSize=2147483648`), or null where it is not, a
     * percentage or a fraction having no size of its own.
     */
    fun minMb(args: List<String>): Long? = minIn(args).mapNotNull { arg ->
        val value = when {
            SIZED_MIN.matches(arg) -> arg.removePrefix("-Xms")
            xxName(arg) == "InitialHeapSize" || xxName(arg) == "MinHeapSize" -> arg.substringAfter('=', "")
            else -> return@mapNotNull null
        }
        sizeMb(value)
    }.maxOrNull()

    private fun sizeMb(value: String): Long? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val unit = v.last().lowercaseChar()
        val number = (if (unit.isLetter()) v.dropLast(1) else v).toLongOrNull() ?: return null
        val bytes = when (unit) {
            'k' -> number * 1024L
            'm' -> number * 1024L * 1024L
            'g' -> number * 1024L * 1024L * 1024L
            't' -> number * 1024L * 1024L * 1024L * 1024L
            else -> if (unit.isDigit()) number else return null
        }
        return (bytes + 1024L * 1024L - 1) / (1024L * 1024L)
    }

    private fun xxName(arg: String): String? =
        if (arg.startsWith("-XX:")) arg.removePrefix("-XX:").substringBefore('=') else null

    private fun split(raw: String?): List<String> =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")).orEmpty()
}
