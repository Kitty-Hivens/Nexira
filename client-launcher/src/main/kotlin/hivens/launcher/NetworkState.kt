package hivens.launcher

/**
 * Global network state flags.
 * Shared between DI modules and UI layer.
 */
object NetworkState {
    /** Set to true when user explicitly accepted SSL bypass via warning dialog. */
    @Volatile
    var sslBypassEnabled: Boolean = false
}
