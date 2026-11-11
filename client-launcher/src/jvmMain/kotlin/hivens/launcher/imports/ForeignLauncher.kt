package hivens.launcher.imports

/**
 * A third-party launcher Nexira can import instances from. Identity is stable
 * across UI rebrands (used as a key and in puppet/test ids); [displayName] is
 * the human label.
 *
 * The same physical `.minecraft` directory backs both the vanilla Mojang
 * launcher and TLauncher, so they share the single [Vanilla] entry -- the
 * importer reads the directory, not the brand that wrote it.
 */
enum class ForeignLauncher(val displayName: String) {
    Vanilla("Minecraft Launcher"),
    Modrinth("Modrinth App"),
    Prism("Prism Launcher"),
    Ftb("FTB App"),
}
