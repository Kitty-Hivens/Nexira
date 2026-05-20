package hivens.ui.puppet

import java.util.ServiceLoader

/**
 * Resolves the active [PuppetServerLifecycle] via Java SPI.
 *
 * The cached [instance] is the first provider returned by
 * `ServiceLoader.load(PuppetServerLifecycle::class.java)`, or
 * [NoOpPuppetServer] when no provider is registered (default
 * production builds -- see [PuppetServerLifecycle] for why).
 *
 * Thread safety: `by lazy` uses synchronized initialization, so
 * concurrent first-access from multiple threads returns the same
 * instance without racing the ServiceLoader scan.
 *
 * Why SPI rather than direct call: keeps the heavy Ktor server
 * dependencies out of the default classpath. RealPuppetServer + its
 * Ktor server stack land in `desktopPuppetMain` and are added to the
 * compilation only when `-PauraPuppetPort=N` is on the Gradle command
 * line. Production builds therefore cannot accidentally bind the
 * control surface even if `-Dnexira.puppet.port=N` is set at runtime,
 * because no implementation exists to bind.
 */
internal object PuppetServerLoader {
    val instance: PuppetServerLifecycle by lazy {
        ServiceLoader.load(PuppetServerLifecycle::class.java)
            .firstOrNull() ?: NoOpPuppetServer
    }
}
