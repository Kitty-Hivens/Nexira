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
 * Why SPI rather than a direct singleton: keeps the chaos-engine code
 * (animations, coroutine event loop, overlay rendering, text corruption
 * tables) out of the default classpath. `RealAprilFools` + its
 * supporting files land in `desktopAprilFoolsMain/` and are added to
 * the compilation only when `-PauraAprilFools=true` is on the Gradle
 * command line. Production builds therefore cannot accidentally
 * activate chaos behaviour even if the calendar date hits April 1 --
 * no implementation exists to render it. Same pattern as
 * `PuppetServerLoader`.
 */
object AprilFoolsLoader {
    val instance: AprilFoolsLifecycle by lazy {
        ServiceLoader.load(AprilFoolsLifecycle::class.java)
            .firstOrNull() ?: NoOpAprilFools
    }
}
