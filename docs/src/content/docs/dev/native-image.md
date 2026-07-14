---
title: Native image (GraalVM / Liberica NIK)
description: Building the headless Nexira CLI to a Linux native binary, and why the Compose GUI cannot be native-imaged today.
---

Nexira's launch pipeline (auth, pack resolve, download/verify, JRE provisioning,
runtime resolve, game launch) is Compose-free and lives in `:client-core` /
`:client-launcher` / `:client-auth*`. The `:client-cli` module is a headless
entrypoint over that pipeline, built to a GraalVM / Liberica-NIK native binary
for Linux. The Compose GUI (`:client-ui`) stays on the JVM -- see
[Why not the GUI](#why-not-the-gui).

## What you get

A single self-contained `nexira-cli` executable: no JVM needed at runtime,
~10 ms cold start (vs ~130 ms for the JVM launcher), ~55 MB. It drives the same
launcher core the GUI does, including the OS keyring over D-Bus via libvault's
Panama bindings.

```
nexira-cli list                              # installed pack instances
nexira-cli launch <packId>                   # offline launch
nexira-cli launch <packId> --provider smartycraft   # reuse the GUI's stored account
nexira-cli launch <packId> --dry-run         # resolve + print the plan, no spawn
nexira-cli version | help
```

## Prerequisites

- A GraalVM 25 distribution with `native-image`. Liberica NIK is the documented
  choice; any GraalVM 25 works (the headless CLI uses no vendor-specific flags).
  - SDKMAN (Liberica NIK): `sdk install nik` -> installs under
    `~/.sdkman/candidates/nik/current`.
  - Or a distro package / tarball (e.g. Arch `jdk25-graalvm-bin`).
- A C toolchain for the final link: `gcc` + `zlib` headers
  (`base-devel` on Arch, `build-essential zlib1g-dev` on Debian/Ubuntu).
- ~4 GB free RAM and a couple of minutes -- `native-image` is its own process,
  separate from the Gradle/Kotlin daemons.

The build resolves `native-image` vendor-agnostically: the `nativeImage { graalvmHome }`
property, else `GRAALVM_HOME`, else `NATIVE_IMAGE_HOME`, else a scan of
`/usr/lib/jvm` (preferring a Liberica NIK install when several are present),
else `PATH`. Set `GRAALVM_HOME` if auto-detect picks the wrong one:

```sh
export GRAALVM_HOME="$HOME/.sdkman/candidates/nik/current"
```

## Build and run

```sh
GRAALVM_HOME=... ./gradlew :client-cli:nativeImage
./client-cli/build/nativeImage/nexira-cli list
```

The binary is `client-cli/build/nativeImage/nexira-cli`. Linux x86-64 only;
`native-image` does not cross-compile, so build on the platform you target.

### CPU target (`-march`)

native-image bakes the target CPU instruction set at build time (unlike the JVM
JIT, which adapts at runtime). The default is `x86-64-v3` (needs AVX2, ~2013+).
Override with `-PnativeMarch=<value>`:

```sh
./gradlew :client-cli:nativeImage -PnativeMarch=native      # fastest, THIS CPU only
./gradlew :client-cli:nativeImage -PnativeMarch=x86-64-v2   # portable floor to distribute
```

`-march=native` SIGILLs on any CPU older than the build host -- never distribute
it; pick a baseline (`x86-64-v2`/`v3`) for that. `native-image -march=list` lists
the options. For an I/O-bound launcher the delta is small; PGO and startup matter
more than `-march`.

## Reachability metadata

`native-image` is built with `--no-fallback`: anything reflective, resource-,
ServiceLoader-, or FFM-based must be declared, or it fails (at build for most
kinds, at first call for unregistered FFM downcalls). The committed metadata
lives at `client-cli/src/main/resources/META-INF/native-image/hivens/nexira-cli/`
and is harvested with GraalVM's tracing agent rather than written by hand.

Workflow (the agent merges across runs, so exercise several code paths):

```sh
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun                                   # default: 'list'
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun -PnativeAgentArgs="launch <id> --dry-run"
# smartycraft dry-run drives the libvault keyring -> captures the FFM downcalls:
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun -PnativeAgentArgs="launch <id> --provider smartycraft --dry-run"

./gradlew :client-cli:nativeImageMetadataCopy   # build/native/agent-output -> committed resources
git diff client-cli/src/main/resources/META-INF/native-image   # review, then commit
```

Re-harvest when a new reachable path appears: a first real game launch
(`GameCommandBuilder`, `RuntimeProvisioner`, full `JavaManagerService` download)
exercises code the dry-runs do not, and may surface a few more entries. The
libraries carry their own metadata where it matters -- OkHttp ships an
`OkHttpFeature`, kotlinx.serialization uses statically-generated serializers --
so the harvest is mostly coroutines internals, security providers, a handful of
resources, and the libvault FFM downcalls.

## The plumbing

`nexira.native-image` is a `buildSrc` convention plugin (`hivens.nativeimage`),
the same shape as `nexira.packaging`: typed `ExecOperations` tasks, no
third-party Gradle plugin, configuration-cache clean. It registers, under the
`native-image` group:

- `nativeImage` -- build the binary.
- `nativeImageAgentRun` -- run under the tracing agent to harvest metadata.
- `nativeImageMetadataCopy` -- promote harvested metadata into the resources.

Defaults (overridable via the `nativeImage { }` block): `--no-fallback`,
`-H:+UnlockExperimentalVMOptions`, `--enable-native-access=ALL-UNNAMED`,
`-Djava.awt.headless=true`. No `--gc=G1` -- that is Oracle-GraalVM-only and
would break on Liberica NIK (Community-based, serial GC).

## Why not the GUI

Compose Desktop renders through Skiko (Skia + AWT). Vanilla Compose-AWT does not
build into a working native image: Skiko loads `libjawt`/Skia at runtime by
paths that are unset in a native binary (`java.home` is absent), and the AWT
windowing path is not native-image-ready. This is an upstream limitation
([SKIKO-580](https://youtrack.jetbrains.com/issue/SKIKO-580/),
[skiko#925](https://github.com/JetBrains/skiko/issues/925)), not a config gap.
The only known workaround replaces AWT windowing with the JWM library, which is
proof-of-concept grade and does not carry our stack (skinema, FileKit, Coil,
libtray).

So `:client-ui` stays on the JVM. The `nexira.native-image` plugin is not
CLI-specific -- if Skiko gains native-image support (static jawt linkage) or the
JWM path matures, the same plugin can target the GUI module. Until then the
headless CLI is the native surface, and the launcher core is deliberately kept
Compose-free so it can be.

## Limits

- Linux x86-64 only; no cross-compilation.
- The keyring path needs `--enable-native-access=ALL-UNNAMED` (baked into the
  build defaults) and a running Secret Service / D-Bus session at runtime.
- A real game launch beyond `--dry-run` is not yet exercised in CI; harvest once
  against a real launch before relying on it for production.
