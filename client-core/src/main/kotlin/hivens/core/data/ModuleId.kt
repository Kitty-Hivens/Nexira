package hivens.core.data

/**
 * A system module boot recovery can disable so a launcher that an environment
 * otherwise breaks still starts: the tray on a DE without SNI, skinema when its
 * FFmpeg natives fail to load, the keyring on a hostile Secret Service. Disabling
 * is a recovery action applied at the NEXT boot -- the module simply never inits
 * -- never a hot-toggle on the live app. Persisted as [id] strings in
 * [SettingsData.disabledModules]: stable ids, not the enum name, so an id a build
 * does not know maps to no module instead of failing the whole settings decode.
 */
enum class ModuleId(val id: String) {
    Tray("tray"),
    Notify("notify"),
    Skinema("skinema"),
    Keyring("keyring");

    companion object {
        fun fromId(id: String): ModuleId? = entries.firstOrNull { it.id == id }
    }
}
