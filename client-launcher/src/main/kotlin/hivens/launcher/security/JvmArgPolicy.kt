package hivens.launcher.security

/**
 * Decides which user-supplied JVM arguments a launch that carries a session
 * token is willing to pass on.
 *
 * The rule is about the SHAPE of a value, not a list of names, because a list of
 * names is the thing that loses. The JVM's flag space is open and grows with
 * every release, so anything enumerating the dangerous ones is out of date the
 * day a JDK ships. What does not change is that a flag can only be a way to run
 * code if its value is a path, a class or a command -- a number cannot be any of
 * those.
 *
 * So: a boolean or a numeric `-XX` is passed, whatever it is named. That is the
 * whole of GC selection and heap tuning, which is per-machine and genuinely the
 * user's business -- ZGC over G1 is not a policy question. A `-XX` whose value
 * is neither is refused, which is exactly `OnError=`, `OnOutOfMemoryError=`,
 * `VMOptionsFile=`, `Flags=`, `SharedArchiveFile=` and `CompileCommandFile=`,
 * without any of them being named here. A flag added by a future JDK is passed
 * if it is a number and refused if it is a path, which is the right default in
 * both directions.
 *
 * System properties are passed by default, because that is what mods actually
 * need -- `-Dmixin.env.disableRefMap`, `-Dforge.logging.console.level`,
 * `-Dcustomskinloader.ignorePatchFailure`. A property cannot load anything by
 * itself; the few whose value the JVM resolves to a class or a path are in
 * [DENIED_PROPERTIES], and `fml.coreMods.load` is the one that reads like an
 * ordinary setting and is a coremod loader.
 *
 * [DENIED_FLAGS] holds what the launcher sets for its own reasons and will not
 * let a launch turn back off.
 *
 * This bounds what a launch carries; it does not bound what a machine can do. A
 * patched launcher ignores all of it. The point is that a line in a settings
 * field stops being enough.
 */
object JvmArgPolicy {

    /** Property keys whose value the JVM resolves to a class or a path it loads. */
    val DENIED_PROPERTIES: Set<String> = setOf(
        "java.system.class.loader",
        "java.class.path",
        "java.library.path",
        "java.util.logging.config.class",
        "java.security.manager",
        "jdk.attach.allowAttachSelf",
        "fml.coreMods.load",
        "legacy.classpath",
    )

    /** `-XX` names the launcher owns; a user value would undo a decision it made. */
    val DENIED_FLAGS: Set<String> = setOf(
        "DisableAttachMechanism",
    )

    /** Size-suffixed integers (`4G`, `512m`), plain integers, and percentages. */
    private val NUMERIC = Regex("^[0-9]+(\\.[0-9]+)?[kKmMgGtT]?%?$")

    /** `-Xmx4G` and friends: a size the builder also models. */
    private val SIZED_X = Regex("^-X(mx|ms|mn|ss)[0-9].*$")

    /**
     * Splits [raw] on whitespace and keeps what a bound launch may carry.
     * Returns the surviving arguments and the refused ones, so the caller can
     * name what it dropped instead of leaving a user to wonder why their flag
     * had no effect.
     */
    fun filter(raw: String?): Result {
        val tokens = raw?.trim()?.takeIf { it.isNotEmpty() }?.split(Regex("\\s+")).orEmpty()
        val kept = ArrayList<String>(tokens.size)
        val refused = ArrayList<String>()
        for (token in tokens) if (allows(token)) kept += token else refused += token
        return Result(kept, refused)
    }

    fun allows(arg: String): Boolean = when {
        arg.startsWith("-D") -> allowsProperty(arg.removePrefix("-D"))
        arg.startsWith("-XX:") -> allowsFlag(arg.removePrefix("-XX:"))
        SIZED_X.matches(arg) -> true
        // Everything else -- an agent, a boot classpath, a module path, a
        // classpath, a legacy -Xrun library -- names something to load.
        else -> false
    }

    private fun allowsProperty(body: String): Boolean {
        val key = body.substringBefore('=')
        return key.isNotEmpty() && key !in DENIED_PROPERTIES
    }

    private fun allowsFlag(body: String): Boolean {
        // Boolean form: +Name / -Name, no value to point anywhere.
        if (body.startsWith("+") || body.startsWith("-")) return body.drop(1) !in DENIED_FLAGS
        val name = body.substringBefore('=')
        if (name.isEmpty() || name in DENIED_FLAGS) return false
        val value = body.substringAfter('=', missingDelimiterValue = "")
        return NUMERIC.matches(value)
    }

    data class Result(val kept: List<String>, val refused: List<String>)
}
