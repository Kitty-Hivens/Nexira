---
title: Native image (GraalVM / Liberica NIK)
description: The nexira.native-image build plugin, its tasks and defaults, and why the Compose GUI is not built native today.
---

`nexira.native-image` is a `buildSrc` convention plugin (`hivens.nativeimage`)
that builds a JVM application module into a GraalVM / Liberica NIK native
binary. No module applies it today. It is kept for the day the launcher itself
is built native; see [Why not the GUI](#why-not-the-gui) for what stands in the
way.

It has the same shape as `nexira.packaging`: typed `ExecOperations` tasks, no
third-party Gradle plugin, configuration-cache clean.

## Applying it

```kotlin
plugins {
    application
    id("nexira.native-image")
}

application {
    mainClass.set("example.MainKt")
}

nativeImage {
    imageName.set("nexira")
    buildArgs.add("--initialize-at-run-time=...")  // appended to the defaults
}
```

The plugin builds from the module's `jar` task and its `runtimeClasspath`
configuration, so the module needs both. The jar carries the compiled classes
and the committed reachability metadata, the configuration carries the
dependencies.

## Tasks

All three sit in the `native-image` group.

| Task                      | What it does                                                                 |
|---------------------------|------------------------------------------------------------------------------|
| `nativeImage`             | Builds the binary with `native-image`.                                       |
| `nativeImageAgentRun`     | Runs the app on the GraalVM JVM under the tracing agent to harvest metadata. |
| `nativeImageMetadataCopy` | Copies the harvested metadata into the committed resources.                  |

`nativeImage` is never taken from the build cache: the binary is large and tied
to the host it was built on. It is still skipped as up to date when its
classpath, arguments, main class and output are unchanged. `nativeImageAgentRun`
always runs, since it executes the app.

## Defaults

Everything is set in the `nativeImage { }` block, and every value has a
default.

| Property               | Default                                                                                                              |
|------------------------|----------------------------------------------------------------------------------------------------------------------|
| `imageName`            | `app`                                                                                                                |
| `mainClass`            | The `application` plugin's `mainClass`, when that plugin is applied.                                                 |
| `buildArgs`            | `--no-fallback`, `-H:+UnlockExperimentalVMOptions`, `--enable-native-access=ALL-UNNAMED`, `-Djava.awt.headless=true` |
| `agentArgs`            | None. The arguments the agent run hands to `main`.                                                                   |
| `graalvmHome`          | Unset, which means auto-detect (see below).                                                                          |
| `outputDir`            | `build/nativeImage`                                                                                                  |
| `agentOutputDir`       | `build/native/agent-output`                                                                                          |
| `committedMetadataDir` | `src/main/resources/META-INF/native-image/hivens/<imageName>`                                                        |

`--no-fallback` makes a gap in the metadata a build failure instead of an image
that quietly needs a JVM. `--enable-native-access=ALL-UNNAMED` is there for
Panama bindings such as the keyring's. `-Djava.awt.headless=true` suits an app
that opens no window and has to be dropped for one that does. There is no
`--gc=G1`: that collector is Oracle GraalVM only and breaks the build on
Liberica NIK, which is Community based and has the serial collector.

## Prerequisites

- A GraalVM distribution with `native-image`, on at least the Java version the
  build compiles to (27 at the moment): an older one cannot read the class
  files. Liberica NIK is the documented choice. Any GraalVM works, since the
  defaults use no vendor-specific flag.
  - SDKMAN (Liberica NIK): `sdk install nik` installs it under
    `~/.sdkman/candidates/nik/current`.
  - Or a distribution package or tarball (Arch: `jdk25-graalvm-bin`).
- A C toolchain for the final link: `gcc` and the `zlib` headers
  (`base-devel` on Arch, `build-essential zlib1g-dev` on Debian and Ubuntu).
- About 4 GB of free memory and a few minutes. `native-image` runs as its own
  process, apart from the Gradle and Kotlin daemons.

The plugin finds `native-image` without assuming a vendor, in this order: the
`nativeImage { graalvmHome }` property, `GRAALVM_HOME`, `NATIVE_IMAGE_HOME`, a
scan of `/usr/lib/jvm` (a Liberica NIK install wins when there are several),
then `PATH`. Set `GRAALVM_HOME` when the detection picks the wrong one:

```sh
export GRAALVM_HOME="$HOME/.sdkman/candidates/nik/current"
```

## Building

```sh
GRAALVM_HOME=... ./gradlew :<module>:nativeImage
```

The binary lands in `<module>/build/nativeImage/<imageName>`. The lookup
expects a Unix layout (`bin/native-image`), and `native-image` does not
cross-compile, so build on the system the binary is for.

### CPU target (`-march`)

`native-image` fixes the CPU instruction set when it builds, where the JVM's JIT
adapts at run time. The default target is `x86-64-v3`, which needs AVX2 (CPUs
from about 2013 on). Override it with `-PnativeMarch=<value>`:

```sh
./gradlew :<module>:nativeImage -PnativeMarch=native      # fastest, this CPU only
./gradlew :<module>:nativeImage -PnativeMarch=x86-64-v2   # a portable floor for a binary you hand out
```

A `-march=native` binary dies with SIGILL on any CPU older than the build host,
so never hand one out: pick a baseline (`x86-64-v2` or `v3`) for that.
`native-image -march=list` lists the options.

## Reachability metadata

With `--no-fallback`, anything reached through reflection, resources,
`ServiceLoader` or the FFM API has to be declared, or the image fails: at build
time for most kinds, at the first call for an FFM downcall nobody registered.
The metadata is harvested with GraalVM's tracing agent rather than written by
hand, and committed under `committedMetadataDir`, where the jar picks it up.

The agent merges what each run records, so run every path the binary will
take, one run per set of arguments:

```sh
GRAALVM_HOME=... ./gradlew :<module>:nativeImageAgentRun -PnativeAgentArgs="<args>"
GRAALVM_HOME=... ./gradlew :<module>:nativeImageAgentRun -PnativeAgentArgs="<other args>"

./gradlew :<module>:nativeImageMetadataCopy   # build/native/agent-output -> committed resources
git diff <module>/src/main/resources/META-INF/native-image   # review before committing
```

`-PnativeAgentArgs` is split on spaces. The run uses the GraalVM home's own
`java`, since the agent library ships with GraalVM, and passes
`--enable-native-access=ALL-UNNAMED` and `-Djava.awt.headless=true`. A path the
agent never saw is a path the image does not know, so harvest again whenever
the app gains one.

## Why not the GUI

Compose Desktop renders through Skiko, which draws with Skia into an AWT
window. Skiko finds `libjawt` and its Skia library through paths it derives from
`java.home`, and a native binary has no `java.home`. That is a known upstream
limitation ([SKIKO-580](https://youtrack.jetbrains.com/issue/SKIKO-580/),
[skiko#925](https://github.com/JetBrains/skiko/issues/925)) rather than a
missing setting here, and it is what blocks `:client-ui` today.

Whether the AWT windowing path itself works in a native image has not been
tried in this project. It is untested, not known to fail. The known workaround
replaces AWT windowing with the JWM library, which is
proof-of-concept grade and does not carry the launcher's stack (skinema,
FileKit, Coil, libtray).

So `:client-ui` stays on the JVM. If Skiko gains native-image support, for
example by linking `jawt` statically, or the JWM route matures, this plugin is
the starting point for building the launcher native, with
`-Djava.awt.headless=true` taken out of its build arguments.
