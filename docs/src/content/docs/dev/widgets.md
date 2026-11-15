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
            "Nexira-Widget-Api" to 3,
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

To use another module's API, for example the packs, the accounts, the catalogue or the language module, name it in the manifest beside the other attributes:

```kotlin
"Nexira-Module-Requires" to "nexira.i18n=1.0, nexira.themes=1.0, nexira.packs=1.0, nexira.accounts=1.0, nexira.catalogue=1.0",
```

| API id             | Artifact         | Package                | What it is for                                              |
|--------------------|------------------|------------------------|-------------------------------------------------------------|
| `nexira.i18n`      | `:i18n-api`      | `hivens.i18n.api`      | Looking words up, the language in use, the language setting |
| `nexira.themes`    | `:themes-api`    | `hivens.themes.api`    | The theme in use, the choice, and bringing a theme          |
| `nexira.packs`     | `:packs-api`     | `hivens.packs.api`     | Listing, launching, installing and updating packs           |
| `nexira.accounts`  | `:accounts-api`  | `hivens.accounts.api`  | Who is signed in, the stored accounts, signing in and out   |
| `nexira.catalogue` | `:catalogue-api` | `hivens.catalogue.api` | Searching the catalogue and putting a project into a pack   |

Each entry is an API id and the version you compiled against. The launcher refuses your jar, with the reason in the log, when it does not carry that API or carries a version that does not serve yours: another major version, or an older minor one. An API you name is visible to your module. One you do not name is not, even when the launcher carries it.

### Everything else goes in your jar

Any other library your module uses has to be inside your jar, as a fat jar or with the dependencies shaded in. That includes libraries the launcher also uses, an HTTP client or an image loader say: your module cannot see the launcher's copy and gets its own. The launcher's own code is not visible to modules at all, its services, its accounts and session, its interface and its dependency injection container among it. Use the keys the widget API offers instead.

Module loading is no longer parent-first. Earlier launchers loaded a module over the whole application, so a library the launcher happened to carry was found without being bundled. A module that relied on that now fails with `NoClassDefFoundError` on the first such class, and has to bundle the library.

While the launcher runs your module's code itself, its constructors, its key declarations, its factories and its services, the thread's context class loader is your module's own loader. A bundled library that finds its providers with `ServiceLoader` then finds yours. Inside a widget's composition, and in coroutines your module starts, that is not so, so there pass your own class loader explicitly, `ServiceLoader.load(Service::class.java, MyModule::class.java.classLoader)`.

Modules cannot see each other's classes. Two modules can carry a class of the same name without interfering.

You may draw with the launcher's design system or bring your own. The example deliberately does the second: it defines its own palette and draws its own controls.

## Words

A module brings its words as files in its jar, one per language, UTF-8, `key=text`:

- `i18n/<tag>.properties` for its own words, `i18n/en.properties`, `i18n/ru.properties` and so on. They are registered under the module's id as their namespace.
- `i18n/<namespace>/<tag>.properties` for words it adds to another namespace: a translation of the launcher's own words (namespace `nexira`) or of another module's.
- `i18n/<tag>/language.properties` for a language it adds, declared the way a person's own translation folder declares one (`name`, `plural`, `fallback`, see [Translations](/Nexira/translations/)). A translation module can bring a whole language this way.

Words a module carries for another namespace are laid over that namespace's own, and a person's own translation files are laid over both. Where a module writes its own words both as `i18n/<tag>.properties` and as `i18n/<its id>/<tag>.properties`, the first wins.

A label a contribution or a setting declares is a key into the module's own words. A key with no entry is shown as written, so a module in one language can put plain text there and ship no files at all.

To look words up, name `nexira.i18n` in the manifest and compile against `:i18n-api`:

```kotlin
@Composable
fun Greeting(name: String) {
    val words = strings("mymodule")
    Text(words.text("greeting", name))          // greeting=Hello, {0}
}
```

Outside a composition, a service reads the `Translations` service: `context.services.get<Translations>().strings("mymodule").text("done")`.

A key is looked up in the person's language, then in each language that one falls back to, then in English, then shown as itself. Each text is a message in a small part of ICU MessageFormat:

| Form                                                 | What it does                                              |
|------------------------------------------------------|-----------------------------------------------------------|
| `{0}`, `{1}`                                         | The arguments in order                                    |
| `{0, plural, one {# file} other {# files}}`          | A form by the number's plural category, `#` is the number |
| `{0, plural, =0 {nothing} other {# things}}`         | An exact value before the category                        |
| `{0, select, smartycraft {SmartyCraft} other {{0}}}` | A form by the argument's text                             |
| `'{'`, `'}'`, `'#'`                                  | A literal brace or hash                                   |

`other` is required in every plural and select, and forms nest. Unlike ICU, an apostrophe anywhere else is just an apostrophe, so `'{0}'` is the argument in quotes. The categories a language uses are those of its plural family, see [Translations](/Nexira/translations/).

:::note[Moving from earlier launchers]
`ModuleStrings` no longer looks anything up: it carries a module's tables, and the language module answers them. `ModuleStrings.get` is gone, and a lookup is `strings(namespace).text(key, args)`. The files are the same and `{0}`, `{1}` mean what they meant. Two things read differently now: a literal `{` or `}` in a text has to be written `'{'` or `'}'`, and a text that does not parse is shown as written with a warning in the log.
:::

## Settings

A module keeps its own settings as a settings domain: one value with its defaults, which the launcher stores in a file of its own under the data directory's `settings` folder. Declare it beside the keys, with an app source to read it and an app command to change it, both in your own API:

```kotlin
@Serializable
data class ClockSettings(val seconds: Boolean = false)

object ClockDomain : SettingsDomain<ClockSettings>("mymodule.clock", ClockSettings.serializer()) {
    override fun defaults() = ClockSettings()
}

object ClockKeys {
    val Settings = SourceKey<ClockSettings>("mymodule.clock.settings")
    val Update = CommandKey<SettingsChange<ClockSettings>>("mymodule.clock.settings.update")
}

class MyKeys : KeyContribution {
    override val id = "mymodule.keys"
    override val label = "mymodule.keys"
    override fun declare(keys: ModuleKeys) {
        keys.settings(ClockDomain, ClockKeys.Settings, ClockKeys.Update)
    }
}
```

A widget reads `rememberScopedSource(ClockKeys.Settings)` and changes it by firing `ClockKeys.Update` with `SettingsChange { it.copy(seconds = true) }`. A service of the module reads the same value through `context.services.get<SettingsStores>().of(ClockDomain)`.

The launcher applies changes one at a time and writes the file whole. Where the file is absent or cannot be read, the module gets its defaults, or what `imported` takes from the launcher's former single settings file for a domain that was kept there before. Nothing is written for that, so it is asked again at every start until the first change is saved. A domain that was kept there can name, in `mirrored`, the fields an older build reads, and the launcher writes them there with every change, so going back to an older build keeps the choice, and a choice made there is taken back on the way up again. Taking it back, `imported` is handed the value the domain holds as `current`, and lays the former file's fields over it, so a field that file never held is kept. A file written by a newer build of the module, with a higher `schema` than the domain declares, is read as far as it can be and never written. A domain id is the module's for good, since the file is named by it, and a domain id another module already declared keeps your module's keys out entirely.

## Themes

A theme is drawn by the design system, `nx-ui`, and kept and chosen by the themes module. To bring one, name `nexira.themes` in the manifest, compile against `:themes-api`, and implement `ThemeContribution`:

```kotlin
class Dusk : ThemeContribution {
    override val id = "dusk"
    override val label = "dusk"                 // a key into your words: dusk=Dusk
    override val dark = ThemeScheme(
        steps = listOf(Color(0xFF16121F), Color(0xFF201A2C), Color(0xFF2A2338)),
        colors = listOf(Color(0xFFE88AA8), Color(0xFF8AC6E8)),
    )
}
```

and list it in `META-INF/services/hivens.themes.api.ThemeContribution`. The steps are the ladder from the page outward and the colours the theme's own in order of emphasis. Give `inks` to set the text yourself, a main and a quiet one, or leave them out to read in the launcher's. Without a `light` scheme the theme stays dark when the person asks for light. It joins the picker as your module's id, a slash, then its own id, so it cannot take the place of another theme. A person who chose it keeps that choice while your module is off or gone, and the launcher draws its default theme meanwhile.

A jar that carries a theme without naming `nexira.themes` cannot see the interface it implements. The launcher refuses it before loading any of its classes, and the log says to name `nexira.themes` in `Nexira-Module-Requires`.

To read the theme the launcher is drawn in, or to change it, use `ThemesKeys`: `Active` is the theme drawn now, `Offered` every theme on offer, `Dark` and `Mode` whether it is dark and what decides that, and `Choose`, `SetDark` and `SetMode` change them. What the wallpaper shows and what the system's scheme says are told to the themes module by the launcher alone, and a module cannot tell it either.

```kotlin
@Composable
fun ThemeCount() {
    val offered = rememberScopedSource(ThemesKeys.Offered).valueOrNull() ?: return
    val choose = rememberScopedCommand(ThemesKeys.Choose) ?: return
    Text("${offered.size} themes", Modifier.clickable { choose(offered.random().id) })
}
```

:::note[Moving from earlier launchers]
`ThemeContribution` and `ThemeScheme` were in the widget kernel, `hivens.widget.api`. They are the themes module's now, in `hivens.themes.api`, with the same members. Change the imports, rename the service file to `META-INF/services/hivens.themes.api.ThemeContribution`, and name `nexira.themes` in the manifest.
:::

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
