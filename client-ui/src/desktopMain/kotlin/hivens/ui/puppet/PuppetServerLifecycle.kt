package hivens.ui.puppet

/**
 * Two-method lifecycle for the puppet HTTP control surface. Always
 * present in every build of client-ui; the real Ktor-backed implementation
 * (RealPuppetServer) lives in the `desktopPuppetMain` source dir which is
 * only added to the desktop compilation when `-PauraPuppetPort=N` is on
 * the Gradle command line.
 *
 * Production jars therefore do NOT carry the Ktor server classes the real
 * implementation needs -- ServiceLoader returns nothing, the loader falls
 * back to [NoOpPuppetServer], and `startIfRequested()` is a no-op even if
 * a malicious caller manages to set the `aura.puppet.port` system property
 * at runtime. This is the security boundary that makes the puppet surface
 * impossible to enable in distributed binaries by any means short of
 * dropping a custom jar on the classpath.
 *
 * Discovery uses [java.util.ServiceLoader] (Java SPI). The
 * `META-INF/services/hivens.ui.puppet.PuppetServerLifecycle` descriptor
 * shipped in `desktopPuppetMain/resources` names [RealPuppetServer] as
 * the provider; ServiceLoader instantiates it via its public no-arg
 * constructor at first [PuppetServerLoader.instance] access.
 */
interface PuppetServerLifecycle {
    /**
     * Start the puppet HTTP server if the `aura.puppet.port` system property
     * is set and parses as an integer. Idempotent across repeated calls.
     * No-op in [NoOpPuppetServer]; real binding behaviour in [RealPuppetServer].
     */
    fun startIfRequested()

    /**
     * Stop any running puppet HTTP server. Idempotent if no server is running.
     */
    fun stop()
}
