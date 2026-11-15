---
title: Writing a widget module
description: How to build a Nexira widget module and get it loaded at runtime.
---

A widget module is an ordinary Kotlin/Compose JVM project that builds to a jar. The launcher finds it in the widgets folder at startup and asks it for its widgets. The launcher is never compiled against it.

A complete worked example lives in [`examples/widget-pixelplayer`](https://github.com/Kitty-Hivens/Nexira/tree/dev/examples/widget-pixelplayer). It draws everything itself and depends on nothing in the launcher beyond the widget kernel, which is the shape yours should have too.

:::caution[The kernel is not published yet]
`widget-api`, `widget-model` and `widget-processor` are not in a public Maven repository, so today the only way to build against them is to check out the launcher repository and put your module in it. Publishing them is planned; until then, treat everything below as the shape rather than a recipe you can run from an empty directory.
:::

## A widget

A widget is a top-level composable taking one parameter:

```kotlin
@Widget(id = "example.clock", displayName = "Clock")
@Composable
fun ClockWidget(instance: WidgetInstance) {
    Text(remember { LocalTime.now().toString() })
}
```

The `id` must be unique across everything running in that launcher, including other people's modules. Prefix it with your module id. A collision is resolved by dropping the later claim, so an id that clashes with a built-in widget means your widget silently never appears, and the launcher logs which module lost.

Optional arguments to `@Widget`:

- `displayName` -- what the editor calls it. Defaults to the function name.
- `removable` -- set false to hide the remove control. For widgets whose absence would break a surface.
- `slots` -- ids of drop targets this widget exposes for nested widgets. Container widgets only.
- `propsClass` -- a `@Serializable` data class of tunable settings. Every field needs a default. The editor builds a form from it; the widget reads values with `instance.rememberProps<T>()`.
- `surface` -- the plane the widget sits on, as a `SurfaceSpec` in the same JSON the layout file carries. Blank (the default) means no plane: the widget draws its content and nothing behind it. The processor decodes the string at build time and fails the build on a malformed one. Declare the plane here rather than drawing it yourself, and the user can then reshape it from the editor.
- `drawsOwnSurface` -- set true for a widget that paints its own plane in its body. Blank `surface` means "no plane, and one may be added"; this says "a plane exists and the kernel did not draw it", so the editor stops offering a second one on top. Reach for it only where a record cannot describe the plane: a shape that animates, or one that changes with the widget's own state.

Wrong signatures fail the build with a diagnostic rather than at runtime.

## The build

Two things separate a widget module from any other Compose project.

**Name your own registry.** The annotation processor generates a registry object, and two modules generating the same fully-qualified name would collide on the classpath with one losing its widgets and nothing saying so. Pick names nobody else will:

```kotlin
ksp {
    arg("widgetRegistryPackage", "com.example.mymodule.generated")
    arg("widgetRegistryName", "MyModuleWidgetRegistry")
}
```

**Declare yourself in the manifest.** This is what the launcher reads before it opens a single class:

```kotlin
tasks.jar {
    manifest {
        attributes(
            "Nexira-Widget-Api" to 1,
            "Nexira-Module-Id" to "mymodule",
            "Nexira-Module-Name" to "My Module",
        )
    }
}
```

`Nexira-Widget-Api` must equal `hivens.widget.api.WidgetApi.VERSION` in the launcher you are targeting. A module declaring anything else is refused and the log says both numbers. This is deliberate: widgets carry compiler-generated calls into the Compose runtime, and a module built against a different one can link and then behave wrongly, which costs far more to diagnose than a module that never loads for a stated reason.

`Nexira-Module-Id` is how you appear in logs and, later, in the launcher's own module list. Keep it stable across releases.

The service entry the launcher discovers you by is generated for you into `META-INF/services`; you do not write it.

## Dependencies

Each module is loaded through a class loader of its own, and that loader does not see the whole launcher. It sees a fixed set of shared classes, and looks everything else up in your jar.

```kotlin
dependencies {
    api(project(":widget-model"))
    api(project(":widget-api"))
    // Only if you draw with the launcher's design system.
    implementation(project(":nx-ui"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
}
```

### What every module sees

These always come from the launcher, and a copy in your jar is ignored. That is what keeps your composables talking to the same Compose runtime as everything else, so declare them as dependencies and do not bundle them:

- the widget kernel, `widget-api` and `widget-model` (`hivens.widget.api`, `hivens.widget.model`);
- the launcher's design system, `nx-ui`: `NxTheme`, the `Nx` components and the colour, spacing and motion tokens. Its colour helpers use `com.materialkolor`, which is shared with it;
- Compose (`androidx.*`, `org.jetbrains.compose.*`) and Skia (`org.jetbrains.skia`, `org.jetbrains.skiko`);
- the Kotlin standard library, kotlinx.coroutines, kotlinx.serialization and SLF4J;
- the media engine, skinema (`dev.hivens.skinema`), whose native libraries a process can load only once.

`nx-ui` is part of what your module builds against, the same way the widget kernel is. A launcher release that changes its public API can break modules built on the old one.

### Other modules' APIs

To use another module's API, for example the packs, the accounts or the catalogue module, name it in the manifest beside the other attributes:

```kotlin
"Nexira-Module-Requires" to "nexira.packs=1.0, nexira.accounts=1.0, nexira.catalogue=1.0",
```

Each entry is an API id and the version you compiled against. The launcher refuses your jar, with the reason in the log, when it does not carry that API or carries a version that does not serve yours: another major version, or an older minor one. An API you name is visible to your module. One you do not name is not, even when the launcher carries it.

### Everything else goes in your jar

Any other library your module uses has to be inside your jar, as a fat jar or with the dependencies shaded in. That includes libraries the launcher also uses, an HTTP client or an image loader say: your module cannot see the launcher's copy and gets its own. The launcher's own code is not visible to modules at all, its services, its accounts and session, its interface and its dependency injection container among it. Use the keys the widget API offers instead.

Module loading is no longer parent-first. Earlier launchers loaded a module over the whole application, so a library the launcher happened to carry was found without being bundled. A module that relied on that now fails with `NoClassDefFoundError` on the first such class, and has to bundle the library.

While the launcher runs your module's code itself, its constructors, its key declarations, its factories and its services, the thread's context class loader is your module's own loader. A bundled library that finds its providers with `ServiceLoader` then finds yours. Inside a widget's composition, and in coroutines your module starts, that is not so, so there pass your own class loader explicitly, `ServiceLoader.load(Service::class.java, MyModule::class.java.classLoader)`.

Modules cannot see each other's classes. Two modules can carry a class of the same name without interfering.

You may draw with the launcher's design system or bring your own. The example deliberately does the second: it defines its own palette and draws its own controls.

## Installing while you work

```kotlin
tasks.register<Copy>("installWidget") {
    from(tasks.jar)
    into(providers.gradleProperty("widgetsDir").orElse(
        providers.systemProperty("user.home").map { "$it/.local/share/nexira/widgets" },
    ))
}
```

Then `./gradlew installWidget` and restart the launcher. Modules are scanned once at startup, so every change costs a restart.

## What the launcher does not do

There is no sandbox. The class loader keeps the launcher's own classes out of your module's view, but your module still runs with the same access as the launcher: files, network, everything the JVM can reach. The launcher does not restrict it and does not ask the user to approve anything. What it does instead is name your module in the log and, in time, in a list the user can see and switch off.

Nothing is signed or verified. A module is trusted because a person chose to put the file there.

## Distribution

Attach the jar to a release on your own repository. There is no store to submit to and no approval step. A shared index of modules is planned; until it exists, a link is the distribution mechanism.
