package hivens.ui.easter

import java.util.ServiceLoader

/**
 * Resolves the active [AprilFoolsLifecycle] via Java SPI.
 *
 * The cached [instance] is the first provider returned by
 * `ServiceLoader.load(AprilFoolsLifecycle::class.java)`, or
 * [NoOpAprilFools] when no provider is registered (default production
 * builds -- see [AprilFoolsLifecycle] for why).
 *
 * Thread safety: `by lazy` uses synchronized initialization, so
 * concurrent first-access from multiple threads returns the same
 * instance without racing the ServiceLoader scan.
 *
 * Why SPI rather than a direct singleton: the shell mounts the seasonal
 * surface through one interface and never names the engine, so the module
 * boundary holds in both directions -- `client-ui` compiles without knowing
 * `RealAprilFools` exists, and the engine is replaceable by the NoOp for any
 * build that wants it gone.
 *
 * `RealAprilFools` is registered from this module's `desktopMain` resources,
 * so an ordinary build resolves it and the calendar (April 1-14) is the only
 * thing deciding whether anything happens. Dropping that registration file is
 * all it takes to ship a launcher with no seasonal behaviour at all -- which
 * is exactly the state this loader spent its first releases in by accident,
 * hence `AprilFoolsLoaderTest`.
 */
object AprilFoolsLoader {
    val instance: AprilFoolsLifecycle by lazy {
        ServiceLoader.load(AprilFoolsLifecycle::class.java)
            .firstOrNull() ?: NoOpAprilFools
    }
}
