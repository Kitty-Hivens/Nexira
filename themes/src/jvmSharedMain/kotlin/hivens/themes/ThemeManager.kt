package hivens.themes

import hivens.i18n.api.Translations
import hivens.i18n.api.Translator
import hivens.themes.api.SystemScheme
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeInputs
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemeScheme
import hivens.themes.api.ThemeSettings
import hivens.ui.theme.Scheme
import hivens.ui.theme.Theme
import hivens.ui.theme.WallpaperTone
import hivens.widget.api.ModuleContribution
import hivens.widget.api.SettingsStore
import hivens.widget.api.TakenContribution
import hivens.widget.api.ofKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The theme and the scheme a window opens on, for one drawn before the manager exists: the
 * boot window. Worked out from the same files the manager opens on, the same way.
 */
data class ThemeOpening(val theme: Theme, val dark: Boolean)

/**
 * The library of themes and the choice: which theme the launcher is drawn in, whether dark,
 * and what decides that. One for the app, which the themes module makes as an app service and
 * answers its keys over. The host draws its windows with what those keys answer, and the window
 * the launcher boots in, drawn before the manager exists, with what [opening] works out.
 *
 * What the person chose is [settings], the themes settings domain, which also writes the dark
 * flag and the mode into the launcher's former settings file for a build from before the move.
 * Their own themes and the last wallpaper's colours stay in [file], which also gets a copy of the
 * chosen theme for such a build. The settings record which theme the file last took
 * ([ThemeSettings.copied]), so a theme found there that is not that one is what an older build
 * chose since, and is taken back when this one opens. That holds for a file of the first version
 * too, which only a build from before the theme file's second version writes. A file that could
 * not be written leaves the record as it was, and a choice made since stays the choice.
 *
 * The automatic sources of the dark flag are inputs only the host feeds in ([ThemeInputs]): the
 * wallpaper through [wallpaper] and the system's scheme through [systemScheme]. Only the source the
 * mode names is listened to, and a source that agrees with the current flag writes nothing (see
 * [darkThemeFor]).
 *
 * Themes modules bring come in through [contribute] and leave through [withdraw], which [follow]
 * calls as the modules brought in change. They are named
 * in the language [translations] are in now and never stored, so a choice of one whose module is
 * gone lands on the default theme and comes back with the module.
 *
 * [write] runs a save of [file] where the host wants it, off the event thread say.
 */
class ThemeManager(
    private val store: SettingsStore<ThemeSettings>,
    private val file: ThemeLibraryFile,
    private val translations: Translations,
    scope: CoroutineScope,
    private val write: (what: String, block: () -> Unit) -> Unit = { _, block -> block() },
) : ThemeInputs {
    private val log = LoggerFactory.getLogger(ThemeManager::class.java)
    private val lock = Any()

    // What the theme file holds, its selection kept equal to the choice before every save.
    private var stored: ThemeLibrary

    // The themes each module brought, by module id, in the order the modules came.
    private val modules = LinkedHashMap<String, List<ThemeContribution>>()

    // The last brightness the wallpaper reported, which a change of mode reads.
    private var luminance: Float? = null

    // The settings as last acted on, to tell what a change through the settings key changed.
    private var seen: ThemeSettings

    init {
        val loaded = file.load()
        stored = loaded.library
        val chosen = store.value.value.theme
        val opening = openingSelection(store.value.value, loaded)
        if (chosen != null && opening != chosen) {
            log.info("The theme was chosen by an older build since this one last wrote it, and is taken from there")
            store.update { it.copy(theme = opening, copied = opening) }
        }
        seen = store.value.value
    }

    /** What the person chose, as stored. */
    val settings: StateFlow<ThemeSettings> = store.value

    private val _library = MutableStateFlow(compose(translations.current.value))

    /** Every theme on offer and the one in use, the modules' among them. */
    val library: StateFlow<ThemeLibrary> = _library.asStateFlow()

    private val _active = MutableStateFlow(_library.value.active)

    /** The theme the launcher is drawn in. */
    val active: StateFlow<Theme> = _active.asStateFlow()

    private val _offered = MutableStateFlow(_library.value.all)

    /** Every theme on offer. */
    val offered: StateFlow<List<Theme>> = _offered.asStateFlow()

    private val _dark = MutableStateFlow(seen.dark)

    /** Whether the launcher is drawn in the dark scheme of [active]. */
    val dark: StateFlow<Boolean> = _dark.asStateFlow()

    private val _mode = MutableStateFlow(seen.mode)

    /** What decides [dark]. */
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    private val _systemSchemeAvailable = MutableStateFlow(false)

    /** Whether the host can read the system's colour scheme, so following it can be offered. */
    val systemSchemeAvailable: StateFlow<Boolean> = _systemSchemeAvailable.asStateFlow()

    /** Whether the theme file belongs to a newer build, so nothing is written to it this session. */
    val readOnly: Boolean get() = file.readOnly

    init {
        // A change made through the settings key rather than through the calls below is acted
        // on here: a new theme is drawn and copied to the file, a new mode applies its source.
        scope.launch { store.value.collect { changed(it) } }
        // The module themes are named in the language, so a new language names them anew.
        scope.launch { translations.current.collect { sync() } }
    }

    /** Puts the theme of [id] in use. */
    fun choose(id: String) {
        store.update { it.copy(theme = id) }
        changed(store.value.value)
    }

    /** Draws dark or light as the person chose. A choice made by hand always wins, so an automatic mode drops back to the person's own. */
    fun setDark(dark: Boolean) {
        store.update { it.copy(mode = ThemeMode.Manual, dark = dark) }
        changed(store.value.value)
    }

    /** Hands the dark flag to [mode], and applies at once what the wallpaper last said where that is the mode. */
    fun setMode(mode: ThemeMode) {
        store.update { it.copy(mode = mode) }
        changed(store.value.value)
    }

    /**
     * What the wallpaper now shows. Its colours are kept, so the theme made from it opens in
     * itself next time, and written only when the picture changed, which is not every frame of
     * a crossfade. Its brightness decides [dark] in the wallpaper mode.
     */
    override fun wallpaper(tone: WallpaperTone) {
        val coloursChanged = synchronized(lock) {
            (tone.colours != stored.wallpaper).also { if (it) stored = stored.copy(wallpaper = tone.colours) }
        }
        if (coloursChanged) {
            sync()
            save("the wallpaper colours")
        }
        val brightnessChanged = synchronized(lock) {
            (tone.avgLuminance != luminance).also { if (it) luminance = tone.avgLuminance }
        }
        if (brightnessChanged) follow { current -> darkThemeFor(current.mode, current.dark, wallpaperLuminance = tone.avgLuminance) }
    }

    /** What the system's scheme says. Decides [dark] in the system mode. */
    override fun systemScheme(scheme: SystemScheme) {
        _systemSchemeAvailable.value = scheme.readable
        follow { current -> darkThemeFor(current.mode, current.dark, systemDark = scheme.dark) }
    }

    /**
     * Takes the themes the module [moduleId] brings, in place of what it brought before. Each is
     * offered as the module's id, a slash, then its own id, so a module cannot take the id of a
     * theme that ships or of another module's.
     */
    fun contribute(moduleId: String, themes: List<ThemeContribution>) {
        synchronized(lock) { modules[moduleId] = themes }
        sync()
    }

    /** Lets go of the themes the module [moduleId] brought, which leaves a choice of one of them on the default theme. */
    fun withdraw(moduleId: String) {
        val had = synchronized(lock) { modules.remove(moduleId) != null }
        if (had) sync()
    }

    /**
     * Takes the themes among what the modules brought in contribute, [taken], as it changes, on
     * [scope]: a module that comes brings its themes, one loaded again brings its new ones, and one
     * that leaves takes its own with it. What [taken] holds now is taken before this returns.
     */
    fun follow(taken: StateFlow<List<TakenContribution<ModuleContribution>>>, scope: CoroutineScope) {
        var handed = emptySet<String>()
        fun apply(all: List<TakenContribution<ModuleContribution>>) {
            val byModule = all.ofKind<ThemeContribution>().groupBy({ it.moduleId }, { it.item })
            byModule.forEach { (id, themes) -> contribute(id, themes) }
            (handed - byModule.keys).forEach(::withdraw)
            handed = byModule.keys
        }
        apply(taken.value)
        scope.launch { taken.collect(::apply) }
    }

    // Acts on what changed since the settings were last acted on, then publishes the result.
    private fun changed(now: ThemeSettings) {
        val before = synchronized(lock) { seen.also { seen = now } }
        if (now.mode != before.mode) {
            follow { current -> darkThemeFor(current.mode, current.dark, wallpaperLuminance = luminance) }
        }
        sync()
        if (now.theme != before.theme) save("the theme")
    }

    // Sets the flag to what [wanted] says from the current settings, and saves it, so the next
    // start opens on what was last seen instead of flashing the old scheme.
    private fun follow(wanted: (current: ThemeSettings) -> Boolean?) {
        store.update { current -> wanted(current)?.let { current.copy(dark = it) } ?: current }
        sync()
    }

    private fun sync() {
        val now = store.value.value
        val library = compose(translations.current.value)
        synchronized(lock) {
            _library.value = library
            _active.value = library.active
            _offered.value = library.all
            _dark.value = now.dark
            _mode.value = now.mode
        }
    }

    // The theme file as the choice stands, saved where the host saves. Its selection is the
    // choice, so a build from before the move opens on the same theme, and the settings record
    // it once the file holds it.
    private fun save(what: String) {
        val snapshot = synchronized(lock) {
            stored = stored.copy(selected = selection())
            stored
        }
        write(what) {
            if (file.save(snapshot)) store.update { if (it.copied == snapshot.selected) it else it.copy(copied = snapshot.selected) }
        }
    }

    private fun selection(): String = store.value.value.theme ?: stored.selected

    private fun compose(words: Translator): ThemeLibrary = synchronized(lock) {
        stored.copy(selected = selection(), contributed = modules.flatMap { (id, themes) -> themes.toThemes(id, words) })
    }

    // A theme that cannot make a scheme, or whose module throws while being read, is left out
    // with a line in the log, so it costs only itself.
    private fun List<ThemeContribution>.toThemes(moduleId: String, words: Translator): List<Theme> = mapNotNull { item ->
        runCatching {
            Theme(
                id = "$moduleId/${item.id}",
                name = words.strings(moduleId).text(item.label),
                dark = item.dark.toScheme(Themes.Celestia.dark),
                light = item.light?.toScheme(Themes.Celestia.light!!),
            )
        }.onFailure { log.warn("A theme of module '{}' could not be used: {}", moduleId, it.message) }.getOrNull()
    }

    companion object {
        /** What a window drawn before the manager exists opens on, from what [settings] and the theme file hold. */
        fun opening(settings: ThemeSettings, stored: StoredThemes): ThemeOpening =
            ThemeOpening(stored.library.copy(selected = openingSelection(settings, stored)).active, settings.dark)

        /**
         * The theme chosen as the manager opens: the one the settings hold, unless they hold
         * none yet, or the theme file holds another than the one the settings record it last
         * took, which an older build chose since.
         */
        internal fun openingSelection(settings: ThemeSettings, stored: StoredThemes): String {
            val chosen = settings.theme
            val copied = settings.copied
            val changedSince = stored.selectionRead && copied != null && stored.library.selected != copied
            return if (chosen == null || changedSince) stored.library.selected else chosen
        }
    }
}

/** A module's colours as a scheme, reading in the launcher's own inks when it gave none. */
private fun ThemeScheme.toScheme(fallback: Scheme): Scheme = Scheme(
    steps = steps,
    inks = inks.ifEmpty { fallback.inks },
    colors = colors,
)
