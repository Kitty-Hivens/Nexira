package hivens.core.api.model

/**
 * Origin of a [ServerProfile]. Drives downstream choices that
 * depend on which upstream produced the entry -- mainly the
 * shape of the per-server settings screen (SmartyCraft and the
 * Hivens mirror declare different optional-mod surfaces and
 * different administrative actions), and any source-specific
 * sync paths the launcher takes.
 *
 * Currently two values, both shipped: [Smartycraft] for entries
 * produced by SmartyCraft's launcher API, [Mirror] for entries
 * produced by the smrt.hivens.dev mirror. Additional values
 * land when an additional upstream becomes real, not before --
 * speculative variants do not belong in this enum.
 */
enum class ServerSource {
    Smartycraft,
    Mirror,
}
