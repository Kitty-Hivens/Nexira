package hivens.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host joins the themes module, the language module and the look manager through their keys and
 * their APIs, and names nothing of how any is made: no class of theirs, no file in their packages, and
 * no engine of the launcher's own words of its own. Of the look it names the manager's API and nothing
 * else, no axis and no provider, which are the manager's business. The one place each module is named,
 * the look's axes and packs among them, is where the build lists the modules compiled into it, by its
 * CompiledModule, the way every compiled module is listed there, and the one engine of the launcher's own words the host makes is the recovery
 * surface's floor, which has to speak when every module failed. No window is drawn with the themes module's keys: what of them the host
 * still names is listed file by file, with what takes it out.
 *
 * Read as text, since an import the compiler drops leaves no trace in what it builds. The text is read
 * the way the compiler would join it: names in backticks without them, dots spaced or not, and string
 * literals put together with a plus as one string, so a name spelled out for reflection is seen too.
 */
class HostBoundaryTest {

    private val roots = listOf("commonMain", "jvmSharedMain", "desktopMain", "desktopPuppetMain", "androidMain").map { File("src/$it/kotlin") }

    /** Where the build lists the modules compiled into it. */
    private val listing = "hivens/ui/widgets/modules/CompiledModules.kt"

    /** What the listing may name of these modules: each one's CompiledModule, and nothing else of any. */
    private val registrations = setOf(
        "import hivens.themes.ThemesModule",
        "import hivens.i18n.I18nModule",
        "import hivens.look.manager.LookManagerModule",
        "import hivens.look.colour.api.ColourModule",
        "import hivens.look.packs.LookPacksModule",
        "import hivens.look.colour.nx.controls.ColourNxControls",
        "import hivens.look.motion.api.MotionModule",
        "import hivens.look.motion.nx.MotionNxModule",
        "import hivens.look.motion.nx.controls.MotionNxControls",
        "import hivens.look.shape.api.ShapeModule",
        "import hivens.look.shape.nx.ShapeNxModule",
        "import hivens.look.stroke.api.StrokeModule",
        "import hivens.look.stroke.nx.StrokeNxModule",
        "import hivens.look.surface.api.SurfaceModule",
        "import hivens.look.surface.nx.SurfaceNxModule",
        "import hivens.look.timing.api.TimingModule",
        "import hivens.look.timing.nx.TimingNxModule",
        "import hivens.look.feedback.api.FeedbackModule",
        "import hivens.look.feedback.nx.FeedbackNxModule",
        "import hivens.look.type.api.TypeModule",
        "import hivens.look.type.nx.TypeNxModule",
        "import hivens.look.icon.api.IconModule",
        "import hivens.look.icon.google.rounded.IconGoogleRoundedModule",
        "import hivens.look.cursor.api.CursorModule",
        "import hivens.look.cursor.system.CursorSystemModule",
        "import hivens.look.cursor.nx.CursorNxModule",
    )

    /** The recovery surface, and the one engine of the launcher's own words it may make. */
    private val floor = "hivens/ui/RecoverySurface.kt" to "NexiraStrings.translator(Locale.getDefault().language)"

    /**
     * The host sources that still name the themes module's keys or inputs, each with what it names and
     * what takes it out. Nothing here is read to draw: every window draws in the look the manager
     * answers, the module reads the system's colour scheme itself, and a first start's values are each
     * settings domain's own. What is left is the one input the host feeds it. The controls of the nx
     * colour provider are a module of their own, which the host draws by widget kind in the slots it
     * leaves for them.
     */
    private val themesAllowances: Map<String, Set<String>> = mapOf(
        // The tone of the wallpaper, fed by the host until the background is a widget module of its own.
        "hivens/ui/AppRoot.kt" to setOf("ThemeInputs"),
    )

    /** Every name of the themes module's keys and inputs [line] holds, a key by its member where it names one. */
    private fun themesNames(line: String): Set<String> =
        if (line.trimStart().startsWith("*") || line.trimStart().startsWith("//") || line.trimStart().startsWith("import ")) {
            emptySet()
        } else {
            THEMES.findAll(line).map { it.value }.toSet()
        }

    /** Every line of [text] that names what the host may not, with why, as `path:line: why: text`. */
    private fun findings(path: String, text: String): List<String> = text.lines().mapIndexedNotNull { index, raw ->
        val line = joined(raw)
        val why = when {
            PACKAGE.containsMatchIn(line) -> "a file in a module's own package"
            (IMPLEMENTATION.containsMatchIn(line) || LOOK.containsMatchIn(line)) && !(path == listing && line.trim() in registrations) ->
                "a name of a module that is not in its API"
            OWN_ENGINE.containsMatchIn(line) && !(path == floor.first && floor.second in line) -> "an engine of the launcher's own words"
            OWN_WORDS_SCOPE.containsMatchIn(line) -> "the launcher's own words as a scope, where an engine is made unseen"
            (themesNames(line) - themesAllowances[path].orEmpty()).isNotEmpty() -> "a key or an input of the themes module, which the host does not draw with"
            else -> return@mapIndexedNotNull null
        }
        "$path:${index + 1}: $why: ${raw.trim()}"
    }

    /** [raw] as the compiler joins it: no backticks, no space around a dot, adjacent string literals as one. */
    private fun joined(raw: String): String = raw
        .replace("`", "")
        .replace(Regex("""\s*\.\s*"""), ".")
        .replace(Regex(""""\s*\+\s*""""), "")

    private fun sources(): List<Pair<String, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(root).invariantSeparatorsPath to it.readText() }.toList()
    }

    @Test
    fun `the host's sources are where this test reads them`() {
        assertTrue(File("src/jvmSharedMain/kotlin", listing).isFile, "expected the shared sources under ${File("src").absolutePath}: this test reads them as text")
    }

    @Test
    fun `no host source names a class of the themes or the language module or of the look, or makes the launcher's words itself`() {
        val found = sources().flatMap { (path, text) -> findings(path, text) }
        assertEquals(emptyList(), found, "the host reaches these modules through their keys and APIs only")
    }

    @Test
    fun `the build lists the modules where it lists every compiled module, and recovery keeps its floor`() {
        val all = sources().toMap()
        val listed = all.getValue(listing).lines().map { it.trim() }.toSet()
        assertEquals(registrations, registrations.filter { it in listed }.toSet(), "the exception above names registrations that are gone")
        assertTrue(floor.second in all.getValue(floor.first), "the exception above names a floor that is gone")
    }

    @Test
    fun `the themes module's keys and inputs are named only where the plan still has them, and no window draws with them`() {
        val all = sources().toMap()
        themesAllowances.forEach { (path, names) ->
            val text = assertNotNull(all[path], "the allowance names $path, which is gone")
            val named = text.lines().flatMap(::themesNames).toSet()
            assertEquals(names, named, "the allowance for $path names what it no longer uses, or misses what it does")
        }
        val layout = assertNotNull(all["hivens/ui/AppLayout.kt"], "AppLayout is gone")
        assertTrue(layout.lines().flatMap(::themesNames).isEmpty(), "AppLayout names a key of the themes module, whose controls are a module of their own")
        val drawing = listOf("ThemesKeys.Active", "ThemesKeys.Dark", "ThemesKeys.ACTIVE_ID", "ThemesKeys.DARK_ID")
        val shells = listOf("hivens/ui/AppShell.kt", "hivens/ui/AndroidShell.kt", "hivens/ui/ShellHost.kt", "hivens/ui/ShellLook.kt", "hivens/ui/host/OpenedWindows.kt", "hivens/ui/LookValues.kt")
        shells.forEach { path ->
            val text = assertNotNull(all[path], "$path is gone")
            drawing.forEach { name -> assertTrue(name !in text, "$path names $name, which the windows no longer draw with") }
        }
        assertEquals(1, findings("hivens/ui/Somewhere.kt", "val dark = app.read(ThemesKeys.Dark)").size)
        assertEquals(1, findings("hivens/ui/AppShell.kt", "val dark = app.read(ThemesKeys.Dark)").size, "a shell may not read the dark flag")
        assertEquals(1, findings("hivens/ui/AppShell.kt", "val mode = app.readOrNull(ThemesKeys.Mode)").size, "nor the mode, which the module follows the system by itself")
        assertEquals(1, findings("hivens/ui/AndroidShell.kt", "inputs?.systemScheme(SystemScheme(readable = true, dark = dark))").size, "nor tell the module the system's scheme")
        assertEquals(1, findings("hivens/ui/bootstrap/FirstRunDefaults.kt", "themeMode = ThemeMode.Manual.name").size, "nor name a first start's mode")
        assertEquals(emptyList(), findings("hivens/ui/AppRoot.kt", "val inputs = remember(level) { level?.launcherServices?.find(ThemeInputs::class) }"))
        assertEquals(emptyList(), findings("hivens/ui/Somewhere.kt", " * the keys are in `hivens.themes.api.ThemesKeys`"), "a comment naming the keys is not a read")
    }

    @Test
    fun `no host source reads the person's reduce motion, which is the motion provider's own setting`() {
        val found = sources().flatMap { (path, text) ->
            text.lines().mapIndexedNotNull { index, line -> "$path:${index + 1}: ${line.trim()}".takeIf { REDUCE_MOTION.containsMatchIn(line) } }
        }
        assertEquals(emptyList(), found, "the host draws movement as the look answers it")
    }

    @Test
    fun `every way of naming a module's insides is seen`() {
        val holes = mapOf(
            "a wildcard import" to "import hivens.themes.*",
            "an import of a class" to "import hivens.i18n.I18nEngine",
            "an import under another name" to "import hivens.themes.ThemeManager as Manager",
            "a name in backticks" to "val m = `hivens`.`themes`.`ThemeManager`::class",
            "a name spaced around its dots" to "val m = hivens . themes . ThemeManager::class",
            "a name put together for reflection" to """Class.forName("hivens." + "themes.ThemeManager")""",
            "a name put together on more pluses" to """Class.forName("hivens.i18n" + "." + "I18nEngine")""",
            "a file in the module's package" to "package hivens.themes",
            "a file in its API's package" to "package hivens.i18n.api",
            "an engine taken by reference" to "val make = NexiraStrings::engine",
            "an engine called" to "NexiraStrings.engine(dir, \"en\")",
            "an engine member imported" to "import hivens.ui.i18n.NexiraStrings.translator",
            "the words object as a scope" to "with(NexiraStrings) { engine(dir, \"en\") }",
            "the words object run as a receiver" to "NexiraStrings.run { shipped(\"en\") }",
            "the words object imported under another name" to "import hivens.ui.i18n.NexiraStrings as Words",
            "the look manager's class" to "import hivens.look.manager.LookManager",
            "a look provider's package" to "import hivens.look.colour.nx.NxColourProvider",
            "an axis contract the host does not read" to "import hivens.look.colour.api.Colour",
            "a wildcard over the look" to "import hivens.look.*",
            "a look name put together for reflection" to """Class.forName("hivens.look." + "manager.LookManager")""",
            "a file in the look manager's API package" to "package hivens.look.manager.api",
            "a class of the colour provider's controls" to "import hivens.look.colour.nx.controls.DayNightWidgetKt",
            "a widget of the controls called by name" to "hivens.look.colour.nx.controls.ThemePickerGridWidget()",
        )
        holes.forEach { (what, line) ->
            assertEquals(1, findings("hivens/ui/Somewhere.kt", line).size, "$what is not seen: $line")
        }
        val allowed = listOf(
            "import hivens.themes.api.ThemesKeys",
            "val dark = NxTheme.isDark",
            "import hivens.i18n.api.I18nKeys",
            "val s = nexiraStrings()",
            "val n = NexiraStrings.NAMESPACE",
            "import hivens.ui.i18n.NexiraStrings",
            "import hivens.look.manager.api.LookKeys",
            "val p = hivens.look.manager.api.LookProvision.NONE",
        )
        allowed.forEach { line -> assertEquals(emptyList(), findings("hivens/ui/Somewhere.kt", line), "$line is the API, and not refused") }
        assertEquals(emptyList(), findings(listing, "import hivens.themes.ThemesModule"))
        assertEquals(1, findings(listing, "import hivens.themes.ThemeManager").size, "the listing names the CompiledModule only")
        assertEquals(emptyList(), findings(listing, "import hivens.look.manager.LookManagerModule"))
        assertEquals(1, findings(listing, "import hivens.look.manager.LookManager").size, "the listing names the manager's CompiledModule only")
        assertEquals(emptyList(), findings(listing, "import hivens.look.colour.api.ColourModule"))
        assertEquals(1, findings(listing, "import hivens.look.colour.api.Colour").size, "the listing names the colour axis's CompiledModule only, not the axis")
        assertEquals(emptyList(), findings(listing, "import hivens.look.colour.nx.controls.ColourNxControls"))
        assertEquals(1, findings(listing, "import hivens.look.colour.nx.controls.ThemePickerGridWidget").size, "the listing names the controls' CompiledModule only")
        assertEquals(emptyList(), findings(floor.first, "    boot[I18nKeys.Words] ?: ${floor.second}"))
        assertEquals(1, findings("hivens/ui/ShellHost.kt", floor.second).size, "the floor is the recovery surface's alone")
    }

    private companion object {
        /** A file whose package is one of the modules', their API's among them. */
        val PACKAGE = Regex("""^\s*package\s+hivens\.(themes|i18n|look)\b""")

        /** A name in the themes or the language module that is not in its API, a wildcard among them. */
        val IMPLEMENTATION = Regex("""\bhivens\.(themes|i18n)\.(?!api\b)(\w|\*)""")

        /**
         * A name of the look that is not the manager's API: the manager itself, an axis, a provider, a pack,
         * a wildcard. The host reads the look through the manager's one key and draws on whatever it answers.
         */
        val LOOK = Regex("""\bhivens\.look\.(?!manager\.api\b)(\w|\*)""")

        /** A key of the themes module, by its member where it names one, or one of the inputs the host feeds it. */
        val THEMES = Regex("""\bThemesKeys(\.[A-Za-z_]+)?|\bThemeInputs\b|\bSystemScheme\b|\bThemeMode\b""")

        /** The person's reduce motion, by any of the names it went by. */
        val REDUCE_MOTION = Regex("""\breduce[dD]?Motion\b|\bReduceMotion\b""")

        /** An engine of the launcher's own words, called, taken by reference or imported. */
        val OWN_ENGINE = Regex("""\bNexiraStrings(\.|::)(engine|translator|shipped)\b""")

        /** The launcher's own words used as a scope or under another name, where an engine can be made without its name. */
        val OWN_WORDS_SCOPE = Regex("""\b(with|run|apply|also|let)\(\s*NexiraStrings\b|\bNexiraStrings\.(run|apply|also|let)\b|import hivens\.ui\.i18n\.NexiraStrings as\b""")
    }
}
