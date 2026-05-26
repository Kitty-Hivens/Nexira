package hivens.launcher.component

/**
 * Pack/server-agnostic input for [GameCommandBuilder.build]. Both
 * the legacy SC server-centric flow and the Hivens pack-centric
 * flow project their domain object (ServerProfile vs PackInstance +
 * mirror manifest) onto this shape before invoking the command
 * builder.
 *
 * Keeps [GameCommandBuilder] from having to know about ServerProfile
 * or PackInstance; only the values it actually uses to assemble
 * argv survive into the launch path.
 */
internal data class LaunchTarget(
    /**
     * Minecraft version, e.g. `"1.12.2"`. Drives VersionConfig lookup,
     * the `--version` arg, `--fml.mcVersion`, and asset/natives layout.
     */
    val mcVersion: String,
    /**
     * NeoForge-only argument overrides (`fml.neoForgeVersion`,
     * `fml.fmlVersion`, etc). Null / empty defers to the in-bundle
     * auto-detector + baked defaults. SC server-list profiles pre-fill
     * these from the dashboard; pack-centric installs leave them null
     * and let the detector run.
     */
    val neoForgeArgs: Map<String, String>? = null,
    /**
     * Optional override of NeoForge's `-DignoreList=...` system property.
     * Null defers to the version-keyed default in GameCommandBuilder.
     */
    val ignoreModulesList: String? = null,
    /**
     * Free-text JVM args; whitespace-split into argv. Null / blank
     * keeps the VersionConfig's baked-in `-XX:` GC args. Wins over
     * the bundled GC args when non-blank (the partition-and-append
     * dance from the legacy code stays as-is).
     */
    val jvmArgsOverride: String? = null,
    /** Human label used in log lines ("Running <name>..."). */
    val displayName: String,
)
