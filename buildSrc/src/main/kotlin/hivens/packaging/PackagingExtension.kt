package hivens.packaging

import org.gradle.api.Action
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Public DSL for the `aura.packaging` convention plugin. Configured from a
 * consumer's `build.gradle.kts` via the `packaging { ... }` block.
 *
 * Single source of truth for everything that flows into either:
 *   - the gradle-side `customRuntime` / `customJpackageImage` tasks, or
 *   - the AppImage shell script (via the generated `packaging-profile.sh`
 *     that downstream commits will introduce).
 *
 * Properties use Gradle's `Property<T>` / `ListProperty<T>` types so the
 * consuming task can declare them as `@Input` and get accurate UP-TO-DATE
 * tracking for free -- mutating a flag here invalidates the cached runtime
 * image, leaving everything else hot.
 *
 * Nested [jlink] is exposed via the standard Gradle nested-extension idiom
 * (`@Inject ObjectFactory.newInstance` to construct the sub-block; an
 * `action(...)` overload so callers write `packaging { jlink { ... } }`).
 */
abstract class PackagingExtension @Inject constructor(objects: ObjectFactory) {

    /** Display name and base file name (e.g. "AuraLauncher"). */
    abstract val appName: Property<String>

    /** Fully-qualified main class (e.g. "hivens.ui.MainKt"). */
    abstract val mainClass: Property<String>

    /**
     * Application version handed to jpackage's `--app-version`. Must match
     * `MAJOR.MINOR[.BUILD[.REVISION]]` digits-only (jpackage rejects
     * pre-release suffixes; Compose Desktop's `packageVersion` and Inno
     * Setup's `VersionInfoVersion` are similarly strict). Strip `-rc1`
     * etc. on the consumer side before setting this.
     */
    abstract val appVersion: Property<String>

    /**
     * JDK modules to include in the jlinked runtime. Order is preserved
     * because it ends up on the jlink `--add-modules` comma-separated list,
     * and a stable order keeps build-input hashing reproducible.
     */
    abstract val modules: ListProperty<String>

    /**
     * JVM arguments baked into the jpackage launcher script via repeated
     * `--java-options` flags. Single source of truth for the runtime
     * launch profile; mirrors what the AppImage AppRun also hands to
     * java, with platform-conditional entries excluded by the consumer.
     */
    abstract val jvmArgs: ListProperty<String>

    /** Icon for the Windows jpackage app image (`.ico`). */
    abstract val windowsIcon: RegularFileProperty

    /** Icon for the macOS jpackage app image (`.icns`). */
    abstract val macosIcon: RegularFileProperty

    /**
     * macOS bundle identifier. Goes into jpackage's
     * `--mac-package-identifier` and the resulting .app's
     * `CFBundleIdentifier`. Reverse-DNS form derived from the
     * `hivens.dev` apex, so "dev.hivens.auralauncher".
     */
    abstract val macosPackageIdentifier: Property<String>

    /**
     * Nested jlink-flag configuration. Defaults are seeded in
     * [PackagingPlugin]; the consumer overrides only what differs.
     */
    val jlink: JlinkOptionsExtension = objects.newInstance(JlinkOptionsExtension::class.java)

    fun jlink(action: Action<in JlinkOptionsExtension>) {
        action.execute(jlink)
    }
}

/**
 * Knobs that translate one-to-one to jlink CLI flags. Each is optional --
 * leaving a property unset means the corresponding flag is omitted, NOT
 * that it defaults to off. [PackagingPlugin] seeds project-wide defaults
 * (strip-debug / no-header-files / no-man-pages on; the rest off) so a
 * consumer can write `jlink { }` with an empty body and still get
 * reasonable output.
 */
abstract class JlinkOptionsExtension {

    /** `--strip-debug`. Drops debug-info attributes from .class files in the runtime. */
    abstract val stripDebug: Property<Boolean>

    /** `--no-header-files`. Excludes `include/` C headers (JNI). */
    abstract val noHeaderFiles: Property<Boolean>

    /** `--no-man-pages`. Excludes `man/` documentation. */
    abstract val noManPages: Property<Boolean>

    /**
     * `--compress=<value>`. JDK 21+ syntax is `zip-N` (N=0..9); JDK 20 and
     * older used `0|1|2`. Leave unset to skip the flag entirely (jlink
     * default = no compression). Set to "zip-9" for our release profile.
     */
    abstract val compress: Property<String>

    /**
     * `--vm=<kind>`. Drops sibling HotSpot variants. Values: "server",
     * "client", "minimal", "all". Aura's release profile pins to "server"
     * (saves ~22 MB by dropping client + minimal native libraries).
     * Leave unset to keep all variants.
     */
    abstract val vmKind: Property<String>

    /**
     * `--include-locales=<langtag-list>`. Restricts `jdk.localedata` to the
     * given BCP 47 language tags. Without this flag, including
     * `jdk.localedata` in [PackagingExtension.modules] would pull in the
     * full ~50 MB locale dataset; without `jdk.localedata` at all, only
     * `java.base`'s en_US fallback is available. Aura's release profile
     * sets "en,ru,de" matching the shipped i18n bundles.
     */
    abstract val includeLocales: Property<String>
}
