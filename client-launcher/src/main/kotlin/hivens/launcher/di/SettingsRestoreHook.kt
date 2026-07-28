package hivens.launcher.di

import hivens.config.ExperimentalProtocolOverride
import hivens.config.Protocol
import hivens.core.api.interfaces.ISettingsService
import org.slf4j.LoggerFactory

/**
 * On Koin start, pushes persisted user preferences from [ISettingsService]
 * into the global state holders that consume them from non-DI sites.
 *
 * One such holder today: [Protocol.setMimicLauncherVersion], backed by a JVM
 * system property that `Protocol.MIMIC_LAUNCHER_VERSION` re-reads on every
 * access.
 *
 * Wired as `single(createdAtStart = true)` in [appModule] so Koin
 * instantiates this hook during `startKoin { modules(...) }` and the
 * `init {}` side-effects land before the first network call. Replaces
 * the prior `KoinJavaComponent.get<ISettingsService>(...)` escape hatch
 * that ran after Koin start: the new shape makes the dependency
 * explicit and removes the Java-interop call from `Main.kt`.
 *
 * Side-effects are wrapped in `runCatching` so a corrupt settings file
 * never prevents Koin from booting -- the legacy code had the same
 * defensive shape and the rationale (warn + continue, don't crash the
 * launcher) is unchanged.
 */
class SettingsRestoreHook(
    settingsService: ISettingsService,
) {
    init {
        runCatching {
            val settings = settingsService.getSettings()
            @OptIn(ExperimentalProtocolOverride::class)
            Protocol.setMimicLauncherVersion(settings.mimicVersionOverride)
        }.onFailure {
            LoggerFactory.getLogger("SettingsRestoreHook")
                .warn("Failed to restore persisted experimental overrides at startup", it)
        }
    }
}
