package hivens.launcher.di

import org.koin.core.module.Module

/**
 * The modules every host starts, in the order they are registered.
 *
 * These ask for paths, a secret store, a Java manager and a game host by type and
 * supply none of them: that is the host's module, and each entry point adds its own
 * (see [hivens.launcher.di.desktop.desktopModules]). One list, so that the GUI, the
 * CLI and the tests that load the whole graph agree on what the graph is.
 */
fun launcherModules(): List<Module> = listOf(
    networkModule,
    authModule,
    cacheModule,
    runtimeModule,
    mirrorModule,
    launchPipelineModule,
    appModule,
)
