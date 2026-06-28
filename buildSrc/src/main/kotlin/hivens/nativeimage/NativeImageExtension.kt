package hivens.nativeimage

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Public DSL for the `nexira.native-image` convention plugin, configured from
 * a consumer's `build.gradle.kts` via the `nativeImage { ... }` block.
 *
 * Mirrors the [hivens.packaging.PackagingExtension] idiom: `Property<T>` /
 * `ListProperty<T>` for accurate UP-TO-DATE tracking. Defaults are seeded in
 * [NativeImagePlugin] so a consumer can write an empty `nativeImage { }` and
 * still get a working build, overriding only what differs.
 *
 * The toolchain (`native-image` binary) is located vendor-agnostically at task
 * time: [graalvmHome] -> `GRAALVM_HOME` -> `NATIVE_IMAGE_HOME` -> a scan of
 * `/usr/lib/jvm` -> `PATH`. Liberica NIK is the documented choice, but any
 * GraalVM 25 install works for the headless CLI.
 */
abstract class NativeImageExtension {

    /** Output binary file name (e.g. "nexira-cli"). */
    abstract val imageName: Property<String>

    /**
     * Fully-qualified main class. Defaults to the `application` plugin's
     * `mainClass` when that plugin is applied, so a consumer rarely sets it.
     */
    abstract val mainClass: Property<String>

    /**
     * Arguments passed to `native-image`. Seeded with safe defaults
     * (--no-fallback, experimental VM options unlock, native-access, headless);
     * a consumer appends extras with `buildArgs.add(...)` -- the convention
     * value is the base, adds accumulate on top.
     */
    abstract val buildArgs: ListProperty<String>

    /**
     * Arguments the tracing-agent run hands to the app's `main`. Overridable
     * per-invocation via `-PnativeAgentArgs="launch foo --dry-run"`.
     */
    abstract val agentArgs: ListProperty<String>

    /**
     * Explicit GraalVM / Liberica-NIK home. Leave unset to auto-detect via
     * `GRAALVM_HOME` / `NATIVE_IMAGE_HOME` / `/usr/lib/jvm` scan / `PATH`.
     */
    abstract val graalvmHome: Property<String>

    /** Directory the native binary is written to. Defaults to `build/nativeImage`. */
    abstract val outputDir: DirectoryProperty

    /** Where the tracing agent writes harvested metadata. Defaults to `build/native/agent-output`. */
    abstract val agentOutputDir: DirectoryProperty

    /**
     * Committed metadata destination -- where `nativeImageMetadataCopy` lands
     * the harvested config so the build consumes it from the classpath.
     * Defaults to `src/main/resources/META-INF/native-image/hivens/<imageName>`.
     */
    abstract val committedMetadataDir: DirectoryProperty
}
