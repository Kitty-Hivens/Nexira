package hivens.packaging

/**
 * Extracts the launch flags that the JVM records as `jdk.module.*` system
 * properties from a full jvmArgs list.
 *
 * These are the flags CDS validates: a shared archive stores the values that
 * were in force when it was dumped, and mapping it with a different set makes
 * the JVM throw the archived module graph away --
 *
 * ```
 * [error][cds] Mismatched values for property jdk.module.addopens:
 *              java.base/sun.nio.ch=ALL-UNNAMED ... specified during runtime
 *              but not during dump time
 * [error][cds] Disabling optimized module handling
 * ```
 *
 * printed on every launch. The base archive is therefore dumped by
 * [CustomRuntimeTask] (and by `scripts/build-appimage.sh` for the AppImage)
 * with exactly this subset of the launcher's own arguments, so dump time and
 * run time agree by construction rather than by two lists staying in sync.
 *
 * Both spellings jlink/jpackage accept are handled; the two-token form is
 * normalized to `--flag=value` so consumers can treat every entry as one
 * argv element.
 */
internal object ModuleSystemArgs {

    private val PREFIXES = listOf(
        "--add-modules",
        "--add-exports",
        "--add-opens",
        "--add-reads",
        "--enable-native-access",
        "--limit-modules",
        "--patch-module",
        "--upgrade-module-path",
    )

    fun filter(args: List<String>): List<String> = buildList {
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val prefix = PREFIXES.firstOrNull { arg == it || arg.startsWith("$it=") }
            when {
                prefix == null -> Unit
                arg != prefix -> add(arg)
                else -> args.getOrNull(i + 1)?.let { value ->
                    add("$prefix=$value")
                    i++
                }
            }
            i++
        }
    }
}
