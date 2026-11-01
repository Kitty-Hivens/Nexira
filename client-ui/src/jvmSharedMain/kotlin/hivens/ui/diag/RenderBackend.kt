package hivens.ui.diag

/**
 * Which graphics backend the shell is actually drawing through.
 *
 * `skiko.renderApi` is a request, and the launcher never sets it, so every
 * readout built on that property says "default" no matter what Skiko settled
 * on. The distinction the property cannot make is the one that matters: a
 * driver that refuses a device drops the whole UI onto `SOFTWARE_FAST`, where a
 * full-screen window is rasterised on the CPU and the cost lands on the machine
 * rather than on the launcher alone. Reported as "everything went slow", with
 * nothing in a bundle to separate it from a launcher that is merely busy.
 *
 * A desktop host probes the window and publishes what it finds; any other host
 * leaves the readout at `unknown`.
 */
object RenderBackend {

    @Volatile
    private var cached: String? = null

    /** The resolved backend, or `unknown` before a host has published one. */
    val current: String get() = cached ?: UNKNOWN

    /** The published backend, or null while none has been. */
    val published: String? get() = cached

    /** Records the backend the host found. Stable for the process: the API is picked once per layer. */
    fun publish(value: String) { cached = value }

    internal const val UNKNOWN = "unknown"
}
