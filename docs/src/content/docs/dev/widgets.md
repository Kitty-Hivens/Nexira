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

:::note[Moving from earlier launchers]
`Nexira-Module-Requires` used to say only that the launcher carries an API. What it names is now brought in with your module, and a missing one makes the module wait where it used to be refused. Name an API your module does without in `Nexira-Module-Uses`.
:::

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
- the launcher's design system, `nx-ui`: `NxTheme`, the `Nx` components and the names it asks the look's axes by;
- `com.materialkolor`, a colour library;
- Compose (`androidx.*`, `org.jetbrains.compose.*`) and Skia (`org.jetbrains.skia`, `org.jetbrains.skiko`);
- the Kotlin standard library, kotlinx.coroutines, kotlinx.serialization and SLF4J;
- the media engine, skinema (`dev.hivens.skinema`), whose native libraries a process can load only once.

`nx-ui` is part of what your module builds against, the same way the widget kernel is. A launcher release that changes its public API can break modules built on the old one.

### Other modules' APIs

To use another module's API, for example the packs, the accounts, the catalogue or the language module, name it in the manifest beside the other attributes:

```kotlin
"Nexira-Module-Requires" to "nexira.i18n=1.0, nexira.themes=2.0, nexira.packs=2.0, nexira.accounts=1.0, nexira.catalogue=2.0",
```

| API id                 | Artifact              | Package                    | What it is for                                                                            |
|------------------------|-----------------------|----------------------------|-------------------------------------------------------------------------------------------|
| `nexira.i18n`          | `:i18n-api`           | `hivens.i18n.api`          | Looking words up, the language in use, the language setting                               |
| `nexira.themes`        | `:look:colour-nx-api` | `hivens.themes.api`        | The theme in use, the choice, and bringing a theme                                        |
| `nexira.packs`         | `:packs-api`          | `hivens.packs.api`         | Listing, launching, installing and updating packs                                         |
| `nexira.accounts`      | `:accounts-api`       | `hivens.accounts.api`      | Who is signed in, the stored accounts, signing in and out                                 |
| `nexira.catalogue`     | `:catalogue-api`      | `hivens.catalogue.api`     | Searching, installing a project, and bringing a source                                    |
| `nexira.look.manager`  | `:look:manager-api`   | `hivens.look.manager.api`  | Bringing an axis of the look, a provider or a pack, and the choice                        |
| `nexira.look.colour`   | `:look:colour-api`    | `hivens.look.colour.api`   | Asking colour by role, and answering the colour axis                                      |
| `nexira.look.motion`   | `:look:motion-api`    | `hivens.look.motion.api`   | Asking movement by role, and answering the motion axis                                    |
| `nexira.look.shape`    | `:look:shape-api`     | `hivens.look.shape.api`    | Asking corners and outlines by role, and answering the shape axis                         |
| `nexira.look.stroke`   | `:look:stroke-api`    | `hivens.look.stroke.api`   | Asking strokes by role, and answering the stroke axis                                     |
| `nexira.look.surface`  | `:look:surface-api`   | `hivens.look.surface.api`  | Asking planes by kind, and answering the surface axis                                     |
| `nexira.look.timing`   | `:look:timing-api`    | `hivens.look.timing.api`   | Asking the delay before a tooltip appears, and answering the timing axis                  |
| `nexira.look.feedback` | `:look:feedback-api`  | `hivens.look.feedback.api` | Asking how a widget answers hover, focus, press and drag, and answering the feedback axis |
| `nexira.look.type`     | `:look:type-api`      | `hivens.look.type.api`     | Asking the face text is drawn in, and answering the type axis                             |
| `nexira.look.icon`     | `:look:icon-api`      | `hivens.look.icon.api`     | Drawing an icon of the look's library or one of your own, and answering the icon axis     |
| `nexira.look.cursor`   | `:look:cursor-api`    | `hivens.look.cursor.api`   | Asking the pointer over what you draw, and answering the cursor axis                      |

Each entry is an API id and the version you compiled against. An API you name is visible to your module. One you do not name is not, even when the launcher carries it.

### Requiring and using

`Nexira-Module-Requires` names what your module does not run without. The launcher brings each in with your module, whether the document names it or not, and it resolves the modules of the folder and its own together, so yours loads after everything it requires. Where something is missing, is switched off, or is there in a version that does not serve yours (another major version, or an older minor one), your module waits: it is listed with the reason, its widgets stay in the layout, and it loads as soon as the reason is gone. Modules that require each other in a circle all wait.

`Nexira-Module-Uses` names what your module makes use of where it is there and runs without. Nothing is brought in for it and nothing waits. The classes of an API you only use are visible while it is there in a version that serves yours, so ask before you touch them, by a key that answers or a source that is not null.

```kotlin
"Nexira-Module-Requires" to "nexira.catalogue=2.0",
"Nexira-Module-Uses" to "nexira.packs=2.0",
```

A module can also be required by its id. It offers itself by it where its manifest states a version of its own, `"Nexira-Module-Version" to "1.0"`, and there need be no API behind it.

### Publishing an API

Another module can compile against yours. Name the API and its version, and the packages of your jar it lives in:

```kotlin
"Nexira-Module-Provides" to "mymodule.wobble=1.0",
"Nexira-Module-Api-Packages" to "mymodule.wobble=com.example.wobble.api",
```

A module that names `mymodule.wobble` in its manifest sees those packages, the same classes your module runs with, and nothing else of your jar. An API of several packages names its id once for each. A jar may carry an API and nothing more. An id the launcher or an earlier jar offers cannot be offered again, and a package the launcher shares with every module cannot be published: the launcher refuses the jar and says so. When your jar is replaced, the modules that require it are read again with it.

:::note[Moving from earlier launchers]
`nexira.packs` is 2.0. A pack's `source.origin`, an install's `origin` and an `InstallRequest`'s `origin` are the source's id exactly as the pack's record carries it, `Mirror` or `Local` say, where 1.0 gave the name in lower case. A request is matched by that id exactly, so `mirror` names no source. Rebuild against 2.0 and compare origins as the ids they are.
:::

### Everything else goes in your jar

Any other library your module uses has to be inside your jar, as a fat jar or with the dependencies shaded in. That includes libraries the launcher also uses, an HTTP client or an image loader say: your module cannot see the launcher's copy and gets its own. The launcher's own code is not visible to modules at all, its services, its accounts and session, its interface and its dependency injection container among it. Use the keys the widget API offers instead.

Module loading is no longer parent-first. Earlier launchers loaded a module over the whole application, so a library the launcher happened to carry was found without being bundled. A module that relied on that now fails with `NoClassDefFoundError` on the first such class, and has to bundle the library.

While the launcher runs your module's code itself, its constructors, its key declarations, its factories and its services, the thread's context class loader is your module's own loader. A bundled library that finds its providers with `ServiceLoader` then finds yours. Inside a widget's composition, and in coroutines your module starts, that is not so, so there pass your own class loader explicitly, `ServiceLoader.load(Service::class.java, MyModule::class.java.classLoader)`.

Modules cannot see each other's classes beyond an API one publishes and the other names. Two modules can carry a class of the same name without interfering.

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

A theme is kept, chosen and answered to the colour axis by the themes module. To bring one, name `nexira.themes` in the manifest, compile against `:look:colour-nx-api`, and implement `ThemeContribution`:

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

## The look the launcher draws

The launcher draws its windows with what modules answer, and names nothing of the modules behind it. It reads two keys at the app level, the way a widget reads any key: `LookKeys.Provide` (`look.provide`), the answers of every axis of the look as the look manager resolves them, which it wraps everything it draws in, and `I18nKeys.Words`, the words. It names no axis and no provider: an axis a module brings reaches every window through that one value, see [Axes of the look](#axes-of-the-look). Colour is such an axis, so the theme a window is drawn in, and whether it is dark, are what the colour provider answers, the themes module's for the default look. Where no look manager is brought in, every axis reads its base answers, colourless and dark, and where no language module is, every word is shown as its key.

Inside the look, `NxTheme { }` is the bridge from the look to Compose and Material: it sets Material's colour scheme, the content colour, the indication, text selection and the right-click menu from the colour axis's answers, so a Material component and a foundation default draw in the look too. To draw something in one theme of the themes module on purpose, a preview of a component say, wrap it in `ThemePreview(theme, dark) { NxTheme { } }`: `ThemePreview`, of the themes module's API, provides that theme's answers for colour, and the bridge inside is set from them.

The themes module and the language module make what answers those keys themselves, as app services: the theme manager over the module's settings and its theme file, the language engine over the person's own translations and the words every module ships. A module compiled into the launcher reaches what it needs for that through services the host keeps for the modules it trusts, and no jar sees: `HostFiles` for the data directory, the host's atomic, ordered writes inside it, and the name a file of the module's is told to the person as when a newer build wrote it, `TakenWords` for the words every owner ships, `TakenContributions` for what the modules brought in contribute, and `BringIn` to bring in a module compiled into the launcher that the document does not name, through the same checks as the ones it names.

The window the launcher boots in is drawn before any key is up, so a module compiled into the launcher answers the app sources that window needs through `CompiledModule.boot`, from the same files its keys open on:

```kotlin
override val boot = BootAnswers { context ->
    BootValues.of { put(ClockKeys.Settings, context.settings(ClockDomain)) }
}
```

`context.settings` reads a settings domain the way the host opens it and writes nothing. The answer has to be what the key answers once it is up, or the window changes under the person as the boot ends. The modules asked are the compiled modules the document names, read before the boot the way the shell reads it, so a document that leaves a module out has the boot window drawn with the same fallbacks as the shell. A module answers only the app sources its keys declare and reads only the settings domains it declares, and an answer that strays from them is refused whole. Of two modules that declare one key, the first the build lists keeps it. A module whose words, keys or answer throw costs only its own values. A jar is not loaded yet when the boot window is drawn, so it is never asked.

## Axes of the look

The look is more than colour: motion, shape, spacing, type, sound, anything a module decides to vary. Each part is an axis, and the look manager, `nexira.look.manager`, picks one provider per axis from the person's choice and hands its answers to the composition. A widget that wants to respect the person's choice reads an axis's answers. One that does not care draws itself.

Three things, all in `:look:manager-api`:

- An **axis**, `LookAxis<A>`: a class you extend with an id, the version of its contract, the type `A` of its answers and the base answers, the defaults used when no provider is chosen for the axis. It makes its own composition local, `local`, which a widget reads the answers through, and the manager provides the answers under that local and nothing else, so no axis reaches what the launcher provides around it. A change of one answer recomposes only what reads it. A module may bring an axis of its own, and the first module to bring an axis id has it.
- A **provider**, `LookProvider<A>`: the axis it answers, the family it belongs to (`nx`, `material`, yours), the contract version of the axis it was built against, and `open`, which hands back its answers as a `StateFlow` ready at once. The manager opens a provider only while the choice needs it, on a thread of its own and never the one that draws, and closes it when that stops. Two providers never answer one axis, and one built against a contract version the axis does not serve is refused. A provider may stand on another of the same axis by naming its family in `basedOn`: `open` is handed that one's answers in `context.under(axis)`, and `LookAnswers.over(context, axis) { under -> ... }` answers with what it changes of them. Where that family is not there, or fails, the provider stands on the axis's base answers, and it is opened again when the family comes back. Families that stand on each other in a circle are all refused.
- A **pack**, `LookPack`: an id, a label in your words, and the family it names for each axis. A pack names providers and nothing else, and may leave axes out. The default pack's id, `nexira`, is kept for the modules compiled into the launcher.

Everything behind a provider's answers is the provider's own: a dark and a light variant and what switches between them, a blur, its settings, its keys, and how other modules extend it, a theme for a theme provider say, through a contribution of its own API. The manager knows none of it. When one of them changes, the provider hands new answers through its `StateFlow`. A module that steers one provider, a day and night switch say, asks `LookState.draws(axis, family)` and shows itself only while that provider answers its axis.

Each axis is answered by the first of these that names a provider which is there and opens: the person's family for that axis, the family the chosen pack names, the family the default pack names, and the axis's base answers. A pack or a family whose module is away stays chosen and is passed over until it comes back. A provider whose `open` or answers throw anything, a cancellation of its own included, or whose answers are not the axis's type, is passed over the same way, and costs only itself.

A module bringing an axis, a provider for it and a pack, with `nexira.look.manager` named in its manifest:

```kotlin
data class Wobble(val amount: Float)

object WobbleAxis : LookAxis<Wobble>("mymodule.wobble", ApiVersion(1, 0), Wobble::class, Wobble(0f)) {
    override val label = "wobble"                   // a key into your words
}

class JellyWobble : LookProvider<Wobble> {
    override val label = "jelly"
    override val axis = "mymodule.wobble"
    override val family = "jelly"
    override val contract = ApiVersion(1, 0)
    override fun open(context: LookContext) = LookAnswers.of(Wobble(0.3f))
}

class JellyPack : LookPack {
    override val id = "mymodule.jelly"
    override val label = "jelly.pack"
    override val providers = mapOf("mymodule.wobble" to "jelly")
}
```

List each in `META-INF/services` under its type, `hivens.look.manager.api.LookAxis`, `LookProvider` and `LookPack`, and name the manager's API in the manifest from the property the launcher keeps it under:

```kotlin
"Nexira-Module-Requires" to "nexira.look.manager=${providers.gradleProperty("nexira.lookManagerApi").get()}",
```

A widget reads `WobbleAxis.local.current`, and draws still outside any look. To draw a subtree in another look, wrap it in `OverrideLook(LookChoice.build { pack = "mymodule.jelly" }) { ... }`: the subtree gets what the person would get by choosing that on top of their own choice, every axis going down the whole chain again, the subtree's family for it, the person's own, the subtree's pack or else the person's, the default pack, the base. Until its providers have answered, the subtree keeps the look around it. A choice is made with `LookChoice.build` and changed with its `with` functions, never through a constructor, so a field a later launcher adds does not break your module. The person's choice is `LookKeys.Settings`, changed through `LookKeys.UpdateSettings`, and `LookKeys.State` says which provider answers each axis, why, and what is on offer. Code outside composition, a window's chrome or a tray icon say, reads every axis at once from `LookKeys.Answers`, a `LookSnapshot` holding what the composition is provided at that moment: `snapshot.of(WobbleAxis)`. A service, which reads no source, gets the same from the app service `LookView`, beside the state. Before the boot the manager answers the snapshot too, from the same pass as the rest.

A document names the manager and nothing else of the look. The axes, providers and packs of every module compiled into the launcher reach the manager whether the document names their module or not, beside those of every jar switched on, and only what the choice needs is opened. A provider is opened with what its own module may use: the services as its module sees them, its own settings among them and no other module's, in `LookContext.services`. Where the provider's module is compiled into the launcher and the document does not name it, the manager brings that module in first, so its keys, services and settings are there as if the document named it, and it stays in while the launcher runs. Before the boot a provider gets its own module's boot context in `LookContext.boot` instead, its files and settings read and not kept, and answers the same from them. The boot window opens in the look the shell then draws: the manager answers the same keys before the boot, from the same choice and the modules compiled in, and closes the providers it opened for that before the window draws. A jar is not loaded before the boot, so a choice that names a jar's pack or family opens the boot window on what comes next in the choice, and the shell turns to the jar's look once the jar is loaded.

### Colour

The colour axis, `colour`, comes with the launcher in `:look:colour-api` (`nexira.look.colour`). A widget asks it by what it draws, never by a value: `Ink` for text and glyphs (`Ink.main`, `Ink.quiet`, `Ink.on(fill)`), `Paint` for colours that mean something (`Paint.lead`, `Paint.status(Status.Error)`, `Paint.category(member)`, and `Paint.partner`, `Paint.third` and `Paint.palette`, the look's own colours for art and decoration), `Fill` for areas, `Line` for strokes, `Ground` for what content sits on (`Ground.current`, `Ground.page`, `Ground.dark`) and `Media` for colours over a picture. A fill, a rule and the faint ink are made over the colour content sits on, which whatever draws a plane sets in `LocalGround`, and the page outside any. `Status` is the manager's API's, so every axis that answers by it names the same word.

A provider of colour answers with `ColourAnswers.build { ... }`, filling the roles it answers. A role it leaves alone keeps its base answer, and a role the others are made of carries them along: fill only the lead, and the tinted fills, marks and edges are in that lead. Where no provider answers, the base answers hold, plain greys for a dark ground with no colour at all. The default pack names the `nx` family for colour.

### Motion

The motion axis, `motion`, comes with the launcher in `:look:motion-api` (`nexira.look.motion`). A widget asks it by what is happening, never by a duration: `Motion.tap` for a press or a hover answering, `Motion.colourShift`, `Motion.track` for a mark travelling, `Motion.fade`, `Motion.popup`, `Motion.panelSlide`, `Motion.reveal`, `Motion.emphasis` and `Motion.pageSwap` for something arriving or leaving, `Motion.scroll` and `Motion.dismiss` for what input drives, `Motion.lookChange` for the look itself changing, and `Motion.drift`, `Motion.sweep` and `Motion.ambient(periodMs)` for movement that goes on. A role is a `MotionRole`: a spec for any type through `of()`, usable as it is where a `FiniteAnimationSpec<Float>` is asked, with the `enter` and `exit` of something coming and going by it. A widget's arrival is `Motion.arrival(entrance)` and the order a slot's widgets arrive in `Motion.stagger(order)`.

Some movement only dresses a change already made. A widget that has some asks `Motion.decoration(role)`, and draws the change at once where it answers null. `Motion.lookChange` and `Motion.drift` answer null the same way. That is how a provider holds movement still where the person asked for less: reduce motion is the provider's own setting, not the launcher's, and an entrance or a delay a widget's node names is the node's and plays all the same.

A provider of motion answers with `MotionAnswers.build { ... }`. A role it leaves alone keeps its base answer, Compose's own 300 ms tween on its standard curve, a plain fade, and linear loops. The default pack names the `nx` family for motion.

### Shape

The shape axis, `shape`, comes with the launcher in `:look:shape-api` (`nexira.look.shape`). A widget asks it by the kind of thing it draws, a category every interface has, never by a radius: `Shape.control` for something pressed, typed into or picked, `Shape.card` for a region in the flow, `Shape.capsule` for what is round at its ends, `Shape.indicator` for a mark saying where something is, `Shape.menu`, `Shape.tooltip`, `Shape.sheet` and `Shape.dialog` for what floats, and `Shape.thumb` for a scrollbar's grip. Each answers a Compose `Shape`, so a provider may answer cut, smoothed or any other corners. A component that keeps a corner of its own beside its category's answer keeps it as its own, and is no new role. `Shape.plate` answers the outline of a widget's own plate where the widget's node leaves it unsaid: its corner, its smoothing and how round a star's or a polygon's points are. A value set on the node is stronger.

A provider of shape answers with `ShapeAnswers.build { ... }`. A role it leaves alone keeps its base answer: square, as Compose draws where nobody chose, with the capsule fully round and the thumb at Compose's own 4dp. The default pack names the `nx` family for shape.

### Stroke

The stroke axis, `stroke`, comes with the launcher in `:look:stroke-api` (`nexira.look.stroke`). A widget asks it by what a stroke is for, never by a width: `Stroke.edge` for the edge of something pressed, typed into or picked, `Stroke.mark` for a mark saying where something is or which is chosen, `Stroke.divider` for a rule between groups or rows, and `Stroke.placeholder` for the outline of a place that is empty or can take something. The edge of a plane, a card or a panel, is the plane's own and not asked here.

Each role answers a `StrokeRole`, which draws itself. The widget hands it what to draw along, the brush to draw in and the state it is in, and the role decides everything else: its width, its ends and joins, a dash, or a stroke of its own that is doubled, glows or moves. `Modifier.stroke(role, color, shape, state)` outlines a node the way `Modifier.border` does, and `drawStroke(role, ...)` draws an outline, a line or a path in a draw block the way `drawOutline`, `drawLine` and `drawPath` do with a `Stroke` style. The colour is the colour axis's, `Line` for most strokes. The state is a `StrokeState`, the six states Compose knows and no others: hovered, focused, pressed, dragged, enabled and selected. `interactionSource.collectStrokeState(enabled, selected)` collects it from an interaction source, and a widget that passes no state is drawn at rest.

A provider of stroke answers with `StrokeAnswers.build { ... }`, each role a `StrokeRole.solid(...)`, a `StrokeRole.byState { state -> ... }`, or a class of its own extending `StrokeRole`. A role it leaves alone keeps its base answer: a hairline, one device pixel with butt ends and no dash, as Compose strokes where nobody chose a width. The default pack names the `nx` family for stroke.

### Surface

The surface axis, `surface`, comes with the launcher in `:look:surface-api` (`nexira.look.surface`). A widget asks it for a plane by kind, never by a colour or a depth: `Surface.plane(kind, holder, floating)` with a `SurfaceKind` (`Page`, `Chrome`, `Panel`, `Card`, `Field`, `Popup`, `Dialog`, `Notice`), the `Plane` that holds it (`LocalPlane.current`) and the top step of the colour axis's ground. Depth is relative, so a card is placed by whatever holds it. The answer is a `PlaneAnswer`: the step of the ladder its body is drawn in and the step what it holds counts from, its opacity, the width of its edge, and its `PlaneMaterial`, an object that draws the body and makes the edge's colour from it. The colour of a step is the colour axis's. Whatever draws a plane provides a `Plane` to what it holds, and the colour under its content as `LocalGround`.

`Surface.plate` answers what a widget's own plate takes for a field its node leaves empty: its opacity and its edge. A value set on the node is stronger, and in the editor each field can be put back to empty on its own. A shadow and a backdrop blur are no part of the look: each is the widget's own, and none where the widget names none.

A provider of surface answers with `SurfaceAnswers.build { ... }`, one rule for every kind in `plane`, and the `plate`. Nothing it answers moves a layout: a plane's size and padding are the widget's. What it leaves alone keeps its base answer: a flat plane in the colour of what holds it, opaque, with no edge, as Compose draws a surface where nobody chose. The default pack names the `nx` family for surface.

### Timing

The timing axis, `timing`, comes with the launcher in `:look:timing-api` (`nexira.look.timing`). A widget asks it the delay before a tooltip appears under the pointer, by what the tooltip is for: `Timing.tooltipDelay(TooltipKind.Label)` for one that names the thing, `Explain` for one that is read, `Preview` for a picture. How a tooltip behaves once shown, whether it can be entered and when it leaves, is the widget's own, and nothing the axis answers moves a layout. A provider answers with `TimingAnswers.build { tooltipDelay = { kind -> ... } }`. Where it answers nothing, the delay is half a second, Compose's own default. The default pack names the `nx` family for timing.

### Feedback

The feedback axis, `feedback`, comes with the launcher in `:look:feedback-api` (`nexira.look.feedback`). A widget asks it how it answers what Compose calls an interaction, a pointer over it, the keyboard's focus, a press, a drag: `Feedback.indication(target, shape, tint)` with a `FeedbackTarget`, `Control` for something pressed, typed into or picked and `Plane` for a plane pressed as a whole. The answer is a Compose `Indication`, which the widget hands to its clickable or draws with `Modifier.indication`. `shape` is the widget's outline, null where its own clip shapes it, and `tint` the colour the colour axis gives its content, null for the content colour around it. `Feedback.disabledAlpha` is how opaque disabled content is. That something is chosen, wrong or a target for a drop is the colour and stroke axes' to show, and busy or open is the widget's own.

A provider of feedback answers with `FeedbackAnswers.build { indication = { target, shape, tint -> ... }; disabledAlpha = ... }`. Nothing it answers moves a layout. When no provider is chosen for the axis, every widget takes Compose's default indication, black at 0.1 under hover and focus and 0.3 under a press, and disabled content stands at Material's 0.38. The default pack names the `nx` family for feedback.

### Type

The type axis, `type`, comes with the launcher in `:look:type-api` (`nexira.look.type`). A widget asks it the face its text is drawn in, by what the text is: `Type.face(TextRole.Heading)`, and `Display`, `Title`, `Body` or `Label`. `Type.mono` is the face for code and values, and `Type.forText(text)` the face for a string in a script the other faces cannot draw, null where they can. The answer is a `TypeFace`: its `family()`, read in composition, and the weight and style the look gives it, null where the widget's own hold. The axis never answers a size, a line height or a tracking: those are the widget's, so a look that changes faces moves no layout by more than its letters. Material's type scale follows the axis through the bridge, each group in its role's face.

A provider of type answers with `TypeAnswers.build { face = { role -> ... }; mono = ...; forText = { text -> ... } }`, a face being `TypeFace.of(family)` or a class of its own extending `TypeFace`. When no provider is chosen for the axis, every role is Compose's default family, code its monospace, and no script gets a face of its own. The default pack names the `nx` family for type.

### Icons

The icon axis, `icon`, comes with the launcher in `:look:icon-api` (`nexira.look.icon`). An icon is asked by its Material Symbols name, any name fonts.google.com/icons shows: `IconKey.named("rocket_launch")`, and the ones the launcher itself draws are constants of `Icons`, `Icons.Delete` being `delete`. That set is the vocabulary of the axis, so nobody guesses what a library calls an icon: a provider of another library translates these names to its own. `Icon.Draw(key, modifier, tint, fill, weight)` draws one in the room the modifier gives it, and `drawIcon(drawing, tint, topLeft, size, fill, weight)` draws one into a widget's own drawing, the drawing had from `Icon.drawing(key)`. The size, the colour, how far it is filled and how heavy its strokes are, are the widget's, and no library moves a layout.

A library need not have a name. `IconKey.named(name, otherwise)` carries a drawing of the widget's own for that case, and `IconKey.of(drawing)` is an icon the widget draws itself under every look. Either is an `IconDrawing`, a class with one `DrawScope.draw(style)`, which draws in the room it is given and lays nothing out.

A provider of icons answers with `IconAnswers.build { library = { name -> ... } }`, the `IconDrawing` of a name and null where its library has none. When no provider is chosen for the axis, the icons a control cannot be found without (close, back, check, more, add and the chevrons) are drawn as plain strokes and every other is blank, in the same room. The default pack names `google-rounded` for icon, Material Symbols Rounded.

### Cursor

The cursor axis, `cursor`, comes with the launcher in `:look:cursor-api` (`nexira.look.cursor`). A widget asks it the pointer over what it draws, by what can be done there: `Modifier.cursor(CursorRole.Draggable)` with `Pressable`, `Disabled`, `Link`, `Text`, `Draggable`, `Dragging` or `Refused`, and `Modifier.cursor(CursorEdge.SouthEast)` for an edge that is pulled to resize. A pointer asked deeper, or later in the chain, wins. Where the look leaves a role alone, the pointer around stays. Compose's own `clickable` asks for no pointer, so a module that draws with `nx-ui` presses with the modifiers and buttons of `hivens.ui.press`, which are Compose's and Material's with the look's pointer and are called the same.

A provider of the cursor answers with `CursorAnswers.build { pointer = { role -> ... }; resize = { edge -> ... } }`, each a Compose `PointerIcon` or null to leave it alone. When no provider is chosen for the axis, what is pressed is under the arrow, text and links under Compose's own pointers, an edge under the platform's arrows, and dragging has none. The default pack names the `nx` family, which stands on `system`: what the person's system has for a role, on Linux by name from the cursor theme, and nothing where it has none.

## Catalogue sources

The catalogue is an engine over sources. Every source it lists packs from, and every source it searches mods, resource packs and shaders in, is a module's contribution: the Hivens mirror and Modrinth ship as two modules of their own, `nexira.source.mirror` and `nexira.source.modrinth`, and a source of yours is a third one beside them. Nothing in the launcher names a source.

To bring one, name `nexira.catalogue` in the manifest, compile against `:catalogue-api`, and implement `PackSourceContribution`:

```kotlin
class Shelf : PackSourceContribution {
    override val id = "Shelf"                   // what every pack record from it carries, for ever
    override val label = "shelf.name"           // a key into your words: shelf.name=The Shelf
    override val icon = SourceIcon.Named("inventory_2")        // a Material Symbols name
    override val paged = false                  // it answers with its whole listing
    override val filters = listOf(
        SourceFilter("era", "shelf.era", listOf(SourceFilterOption("dusty", "shelf.dusty"), SourceFilterOption("new", "shelf.new"))),
    )

    override fun open(services: LevelServices): PackListing = ShelfListing()
}

class ShelfListing : PackListing {
    override suspend fun search(query: String, page: Int) = listOf(
        CatalogueCard("Shelf", "old", "Old", "From the attic", facets = mapOf("era" to "dusty")),
    )
    override suspend fun details(packId: String) = PackDetails("Shelf", packId, "Old", "From the attic")
    override suspend fun versions(packId: String) = emptyList<ProjectBuild>()
}
```

and list it in `META-INF/services/hivens.catalogue.api.PackSourceContribution`. Browse offers it beside the others under your label and icon, lists its packs while it is picked, and opens a pack's page through `details`. `searchStream` and `detailsStream` default to the single answer: a source that keeps a copy hands the kept answer first and the fresh one behind it, and one that is watched can go on handing changes, which Browse collects only while somebody is shown the list.

`filters` are the ways your source splits its own list. Browse shows each as a block of switches, titled and worded by your labels, while your packs are listed, every option on until the person turns it off. A card says which option it falls under in `facets`, by the filter's id, and turning an option off hides those cards where the list is shown without asking your source again. What the person turned off is kept per window.

`id` is written into every pack installed from your source, so it never changes. A pack whose source module is off or gone still lists, opens and plays, and its badge says the source is not loaded. Installing, updating and reattaching a pack are still the launcher's own and only reach the mirror and Modrinth, so a third source lists and shows its packs but does not install them yet.

The catalogue refuses a source, and says why in the log, when its id is one the launcher keeps for itself (`Local`, `Smartycraft` and `Unknown`), one that a source compiled into the launcher declares, whether or not that module is switched on, or one a module listed before yours already brought. Of two modules bringing the same id the first keeps it, and the log names both. Ids are compared exactly, so `shelf` and `Shelf` are two sources.

A source whose own code throws, an exception or a class its jar cannot link, is left out and named in the log, and every other source goes on working.

A source that searches projects by the catalogue's project types, filters and sorts implements `ProjectSearchContribution`: `types` names the project types it searches (`mod`, `resourcepack`, `shader`, `modpack`), and its searcher answers a search and the lists the filters are built from. A pack source whose packs that search lists names the type in `packType`, the way Modrinth's modpacks are searched.

A project of a third project source is listed and searched, but the launcher installs projects from Modrinth alone, so its install dialog says it cannot install from that source and nothing else is asked.

`open` is called once for the app with the services as your own module sees them: the app's services and your module's own settings stores, never another module's. Browse, a pack's page and every install read the one listing it returns. A module loaded again brings a new instance of the source, which is opened again, and what read the old one reads the new one. A source that needs more than the services declares a service of its own through its `KeyContribution` and reads it there.

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
