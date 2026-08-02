package hivens.launcher.security

/**
 * Removes the environment variables that load code into a child process before
 * anything the launcher put on the command line gets a say.
 *
 * The game inherits the launcher's environment, and the launcher inherits the
 * session's, so a value written once into a shell profile or
 * `~/.config/environment.d/` reaches every launch from then on. That makes the
 * environment a second, invisible argument list: `JAVA_TOOL_OPTIONS` alone
 * carries a `-javaagent:` into the JVM without a single character appearing in
 * argv, which would leave any policy applied to the command line decorative.
 *
 * A deny list is the wrong shape for JVM flags -- that set is open and grows
 * with each JDK -- but it is the right shape here: what loads code into a
 * process is defined by the dynamic linker and the `java` launcher, both
 * documented and both stable. The list below is the whole of it, not a sample.
 *
 * Two tiers, because the cost differs:
 *
 *  - [JVM_OPTION_VARS] are sealed on every launch. Nothing legitimate in a
 *    gaming setup routes through them, and each one is an alternative argv.
 *  - [LOADER_VARS] are sealed only for a launch that will carry a session
 *    token. `LD_PRELOAD` is how MangoHud and gamemode attach, so stripping it
 *    everywhere would break ordinary tools on the platform most of our users
 *    are on; a pack with no server binding gets no token and is not worth that
 *    cost.
 *
 * Sealing is not proof. It costs an attacker a patched launcher instead of a
 * line in a profile, which is the whole of what a client-side measure can buy.
 */
object LaunchEnvironment {

    /** Alternative argument lists honoured by the `java` launcher itself. */
    val JVM_OPTION_VARS: Set<String> = setOf(
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "JAVA_OPTIONS",
        "CLASSPATH",
    )

    /**
     * Loader and toolkit hooks that map a path into the process. The macOS
     * `DYLD_*` names are listed for the same reason their Linux counterparts
     * are, and the toolkit entries because GLFW pulls a portal dialog on a
     * desktop session, which loads modules by these paths.
     */
    val LOADER_VARS: Set<String> = setOf(
        "LD_PRELOAD",
        "LD_AUDIT",
        "LD_LIBRARY_PATH",
        "LD_DYNAMIC_WEAK",
        "DYLD_INSERT_LIBRARIES",
        "DYLD_LIBRARY_PATH",
        "DYLD_FRAMEWORK_PATH",
        "GTK_MODULES",
        "GTK3_MODULES",
        "GIO_EXTRA_MODULES",
        "QT_PLUGIN_PATH",
    )

    /**
     * Strips the sealed names from [env] in place and returns those that were
     * actually present, sorted, so the caller can name them. A value the user
     * set deliberately disappearing without a word is its own bug report.
     *
     * [sealLoaderVars] carries the server-binding decision from the caller
     * rather than being derived here -- the launch path already knows whether
     * the pack declared a binding, and that answer is not reconstructible from
     * an environment map.
     */
    fun seal(env: MutableMap<String, String>, sealLoaderVars: Boolean): List<String> {
        val names = if (sealLoaderVars) JVM_OPTION_VARS + LOADER_VARS else JVM_OPTION_VARS
        return names.filter { env.remove(it) != null }.sorted()
    }
}
