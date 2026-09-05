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

Depend on the kernel and on whatever you genuinely need. What you must not do is assume your own copy of the shared runtime will be used:

```kotlin
dependencies {
    api(project(":widget-model"))
    api(project(":widget-api"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
}
```

Each module is loaded through its own class loader over the launcher's, and delegation is parent-first. If your jar bundles Compose, the Kotlin standard library or the widget API, the launcher's copies are used and yours are ignored. That is what keeps a composable in your module talking to the same Compose runtime as everything else. Genuinely private dependencies, ones the launcher does not have, are loaded from your jar as normal.

Modules cannot see each other's classes. Two modules can carry a class of the same name without interfering.

You are not obliged to use the launcher's design system, and the example deliberately does not: it defines its own palette and draws its own controls. Both approaches are fine.

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

There is no sandbox. Your module runs with the same access as the launcher: files, network, everything the JVM can reach. The launcher does not restrict it and does not ask the user to approve anything. What it does instead is name your module in the log and, in time, in a list the user can see and switch off.

Nothing is signed or verified. A module is trusted because a person chose to put the file there.

## Distribution

Attach the jar to a release on your own repository. There is no store to submit to and no approval step. A shared index of modules is planned; until it exists, a link is the distribution mechanism.
