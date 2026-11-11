package hivens.ui.puppet

/**
 * Default [PuppetServerLifecycle] -- silent no-op. Used in every build
 * that does NOT include the `desktopPuppetMain` source dir. See
 * [PuppetServerLifecycle] for the security-boundary rationale.
 *
 * Not registered via Java SPI: [PuppetServerLoader] falls back here
 * when ServiceLoader returns no provider, which is the case in default
 * production builds because the `META-INF/services` descriptor lives
 * exclusively in `desktopPuppetMain/resources`.
 */
internal object NoOpPuppetServer : PuppetServerLifecycle {
    override fun startIfRequested() = Unit
    override fun stop() = Unit
}
