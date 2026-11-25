package hivens.themes

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.Translator
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.colour.api.ColourApi
import hivens.look.colour.api.Fill
import hivens.look.colour.api.Ink
import hivens.look.colour.api.Paint
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.Status
import hivens.themes.api.ThemeSettings
import hivens.ui.theme.Backdrop
import hivens.ui.theme.ColorCategory
import hivens.ui.theme.LocalPlane
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Plane
import hivens.themes.api.SchemeColours
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.SettingsDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import hivens.ui.theme.Status as SchemeStatus

/**
 * The module's colour provider answers the colour axis from the theme and the mode in use: the same
 * before the boot as once the host is up, following a switch of either while it stays open, from the
 * module's own settings alone, and with every role nx-ui drew from the scheme before it read the axis
 * answered and drawn exactly as it was, the bridge's Material scheme and locals among them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxColourProviderTest {

    private val dir: Path = Files.createTempDirectory("nx-colour")
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `it answers the colour axis as the nx family, in the module that keeps the themes`() {
        assertEquals(Colour.id, NxColourProvider.axis)
        assertEquals("nx", NxColourProvider.family)
        assertEquals(ColourApi.VERSION, NxColourProvider.contract)
        assertTrue(NxColourProvider in ThemesModule.contributions)
        assertTrue(ThemesModule.strings.tables.values.all { NxColourProvider.label in it }, "it is named in every language the module ships")
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, for every theme that ships in either mode`() {
        assertEquals(roles(), sample(ColourAnswers.BASE).keys, "the sample below asks every role")
        Themes.all.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val settings = ThemeSettings(theme = theme.id, dark = dark)
                val atBoot = NxColourProvider.open(context(boot = Boot(settings))).answers.value
                val live = NxColourProvider.open(context(manager = manager(settings))).answers.value
                val case = "${theme.id}, dark $dark"
                assertEquals(sample(live), sample(atBoot), case)
                assertEquals(theme.scheme(dark).inks[0], live.inkMain, "$case is the theme chosen")
            }
        }
    }

    @Test
    fun `a switch of the theme or the mode moves the answers of the provider while it stays open`() {
        val manager = manager(ThemeSettings(theme = Themes.Celestia.id, dark = true))
        val open = NxColourProvider.open(context(manager = manager))
        val answers = open.answers
        val before = answers.value
        assertEquals(Themes.Celestia.dark.inks[0], before.inkMain)

        manager.choose(Themes.Lotus.id)
        assertSame(answers, open.answers)
        assertEquals(Themes.Lotus.dark.inks[0], answers.value.inkMain, "the theme switched")
        assertEquals(Themes.Lotus.dark.steps[0], answers.value.groundPage)

        manager.setDark(false)
        assertEquals(Themes.Lotus.light!!.inks[0], answers.value.inkMain, "the mode switched")
        assertEquals(false, answers.value.groundDark)

        val settled = answers.value
        manager.choose(Themes.Lotus.id)
        assertSame(settled, answers.value, "the same theme chosen again hands no new answers")
        assertNotEquals(sample(before), sample(settled))
    }

    @Test
    fun `it reads its own module's settings and services and nothing of another module's`() {
        val boot = Boot(ThemeSettings(theme = Themes.Signal.id))
        NxColourProvider.open(context(boot = boot))
        assertEquals(listOf(ThemesSettingsDomain.id), boot.asked, "before the boot, the themes domain alone")

        val services = Services(manager(ThemeSettings()))
        NxColourProvider.open(Context(services, null))
        assertEquals(listOf<KClass<*>>(ThemeManager::class), services.asked, "once up, the module's own manager alone")
    }

    private enum class Twelve : ColorCategory { A, B, C, D, E, F, G, H, I, J, K, L }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `every role nx-ui answered before the colour axis is answered exactly as the scheme answered it, and drawn so, for every theme that ships in either mode`() {
        val fills = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFCC3355), Color(0x80FFFFFF))
        val keys = listOf("", "Ann", "Celestia", "a pack with a long name")
        Themes.all.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val answers = NxColourProvider.open(context(manager = manager(ThemeSettings(theme = theme.id, dark = dark)))).answers.value
                val scheme = theme.scheme(dark)
                // What nx-ui drew from the scheme before it read the colour axis, worked out the way it did.
                val colours = SchemeColours(scheme)
                val before = LinkedHashMap<String, Any>()
                before["main"] = colours.inkMain
                before["quiet"] = colours.inkQuiet
                before["off"] = colours.inkQuiet.copy(alpha = 0.45f)
                before["lead"] = colours.lead
                before["page"] = colours.step(0)
                before["dark"] = colours.isDark
                before["floating"] = colours.topStep
                before["partner"] = scheme.colors.getOrElse(1) { colours.lead }
                before["third"] = scheme.colors.getOrElse(2) { scheme.colors.getOrElse(1) { scheme.colors.first() } }
                before["palette"] = scheme.colors
                before["inverse fill"] = colours.inkMain
                before["inverse ink"] = colours.step(0)
                before["line on the page"] = colours.line(colours.step(0))
                (-3..10).forEach { before["step $it"] = colours.step(it) }
                SchemeStatus.entries.forEach { before["status $it"] = colours.status(it) }
                Twelve.entries.forEach { before["category $it"] = colours.distinct(Twelve.entries.size)[it.ordinal] }
                keys.forEach { before["hashed '$it'"] = colours.hashed(it) }
                fills.forEach { before["on $it"] = colours.on(it) }
                (fills.filter { it.alpha == 1f } + colours.step(2)).forEach { before["line on $it"] = colours.line(it) }

                // What nx-ui draws now, inside the bridge, over the provider's answers.
                val drawn = LinkedHashMap<String, Any>()
                val scene = ImageComposeScene(8, 8, Density(1f)) {
                    CompositionLocalProvider(Colour.local provides answers) {
                        NxTheme {
                            drawn["main"] = NxInk.main
                            drawn["quiet"] = NxInk.quiet
                            drawn["off"] = NxInk.off
                            drawn["lead"] = NxColor.lead()
                            drawn["page"] = NxColor.page
                            drawn["dark"] = NxTheme.isDark
                            drawn["floating"] = NxColor.floating
                            drawn["partner"] = MaterialTheme.colorScheme.secondary
                            drawn["third"] = NxColor.third
                            drawn["palette"] = Paint.palette
                            drawn["inverse fill"] = Fill.inverse
                            drawn["inverse ink"] = Ink.inverse
                            drawn["line on the page"] = NxInk.line
                            (-3..10).forEach { drawn["step $it"] = NxColor.depth(it) }
                            SchemeStatus.entries.forEach { drawn["status $it"] = NxColor.status(it) }
                            Twelve.entries.forEach { drawn["category $it"] = NxColor.of(it) }
                            keys.forEach { drawn["hashed '$it'"] = NxColor.hashed(it) }
                            fills.forEach { drawn["on $it"] = NxColor.on(it) }
                            (fills.filter { it.alpha == 1f } + colours.step(2)).forEach { ground ->
                                CompositionLocalProvider(LocalPlane provides Plane(0, ground, Backdrop.Plane)) {
                                    drawn["line on $ground"] = NxInk.line
                                }
                            }
                        }
                    }
                }
                try {
                    scene.render()
                } finally {
                    scene.close()
                }
                assertEquals(before, drawn, "${theme.id}, dark $dark: drawn")
                val asked = LinkedHashMap<String, Any>()
                asked["main"] = answers.inkMain
                asked["quiet"] = answers.inkQuiet
                asked["off"] = answers.inkOff
                asked["lead"] = answers.paintLead
                asked["page"] = answers.groundPage
                asked["dark"] = answers.groundDark
                asked["floating"] = answers.groundFloating
                asked["partner"] = answers.paintPartner
                asked["third"] = answers.paintThird
                asked["palette"] = answers.paintPalette
                asked["inverse fill"] = answers.fillInverse(answers.groundPage)
                asked["inverse ink"] = answers.inkInverse
                asked["line on the page"] = answers.lineDivider(answers.groundPage)
                (-3..10).forEach { asked["step $it"] = answers.groundDepth(it) }
                SchemeStatus.entries.forEach { asked["status $it"] = answers.paintStatus(Status.valueOf(it.name)) }
                Twelve.entries.forEach { asked["category $it"] = answers.paintCategory(it.ordinal, Twelve.entries.size) }
                keys.forEach { asked["hashed '$it'"] = answers.paintHashed(it) }
                fills.forEach { asked["on $it"] = answers.inkOn(it) }
                (fills.filter { it.alpha == 1f } + answers.groundDepth(2)).forEach { asked["line on $it"] = answers.lineDivider(it) }
                assertEquals(before, asked, "${theme.id}, dark $dark: answered")
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the bridge sets what NxTheme set from the scheme, for every theme that ships in either mode`() {
        Themes.all.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val answers = NxColourProvider.open(context(manager = manager(ThemeSettings(theme = theme.id, dark = dark)))).answers.value
                val colours = SchemeColours(theme.scheme(dark))
                val before = LinkedHashMap<String, Any>()
                before["colorScheme"] = schemeBefore(colours).toString()
                before["content"] = colours.inkMain
                before["selection"] = colours.lead to colours.lead.copy(alpha = 0.4f)
                before["plane"] = Plane(0, colours.step(0), Backdrop.Page)
                val drawn = LinkedHashMap<String, Any>()
                val scene = ImageComposeScene(8, 8, Density(1f)) {
                    CompositionLocalProvider(Colour.local provides answers) {
                        NxTheme {
                            drawn["colorScheme"] = MaterialTheme.colorScheme.toString()
                            drawn["content"] = LocalContentColor.current
                            val selection = LocalTextSelectionColors.current
                            drawn["selection"] = selection.handleColor to selection.backgroundColor
                            drawn["plane"] = LocalPlane.current as Any
                        }
                    }
                }
                try {
                    scene.render()
                } finally {
                    scene.close()
                }
                assertEquals(before, drawn, "${theme.id}, dark $dark")
            }
        }
    }

    /** Material's scheme as NxTheme cut it from the scheme before it read the colour axis. */
    private fun schemeBefore(c: SchemeColours): ColorScheme {
        val page = c.step(0)
        val lead = c.lead
        val second = c.scheme.colors.getOrElse(1) { lead }
        val third = c.scheme.colors.getOrElse(2) { second }
        val error = c.status(SchemeStatus.Error)
        val ink = c.inkMain
        val quiet = c.inkQuiet
        return if (c.isDark) {
            darkColorScheme(
                primary = lead, onPrimary = c.on(lead),
                primaryContainer = lerp(c.step(2), lead, 0.3f), onPrimaryContainer = ink,
                secondary = second, onSecondary = c.on(second),
                secondaryContainer = lerp(c.step(2), second, 0.3f), onSecondaryContainer = ink,
                tertiary = third, onTertiary = c.on(third),
                background = page, onBackground = ink,
                surface = c.step(1), onSurface = ink,
                surfaceVariant = c.step(2), onSurfaceVariant = quiet,
                surfaceTint = lead,
                inverseSurface = ink, inverseOnSurface = page,
                error = error, onError = c.on(error),
                outline = c.line(c.step(1)), outlineVariant = c.line(page),
                scrim = page,
                surfaceDim = page, surfaceBright = c.step(4),
                surfaceContainerLowest = page, surfaceContainerLow = c.step(1),
                surfaceContainer = c.step(2), surfaceContainerHigh = c.step(3), surfaceContainerHighest = c.step(4),
            )
        } else {
            lightColorScheme(
                primary = lead, onPrimary = c.on(lead),
                primaryContainer = lerp(c.step(2), lead, 0.2f), onPrimaryContainer = ink,
                secondary = second, onSecondary = c.on(second),
                secondaryContainer = lerp(c.step(2), second, 0.2f), onSecondaryContainer = ink,
                tertiary = third, onTertiary = c.on(third),
                background = page, onBackground = ink,
                surface = c.step(1), onSurface = ink,
                surfaceVariant = c.step(2), onSurfaceVariant = quiet,
                surfaceTint = lead,
                inverseSurface = ink, inverseOnSurface = page,
                error = error, onError = c.on(error),
                outline = c.line(c.step(1)), outlineVariant = c.line(page),
                scrim = ink,
                surfaceDim = c.step(4), surfaceBright = page,
                surfaceContainerLowest = page, surfaceContainerLow = c.step(1),
                surfaceContainer = c.step(2), surfaceContainerHigh = c.step(3), surfaceContainerHighest = c.step(4),
            )
        }
    }

    private fun manager(settings: ThemeSettings) =
        ThemeManager(MemoryStore(settings), ThemeLibraryFile(dir) { path, content -> Files.writeString(path, content) }, FixedTranslations(Translator.KEYS), scope)

    private fun context(manager: ThemeManager? = null, boot: BootContext? = null): LookContext = Context(Services(manager), boot)

    private inner class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
    }

    /** The services a provider of this module is handed once the host is up, which note what was asked of them. */
    private class Services(private val manager: ThemeManager?) : LevelServices {
        val asked = ArrayList<KClass<*>>()

        @Suppress("UNCHECKED_CAST")
        // The manager is handed out for its own type only.
        override fun <T : Any> find(type: KClass<T>): T? {
            asked += type
            return if (type == ThemeManager::class) manager as T? else null
        }

        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    /** This module's boot context over [settings] and the test's data directory, which notes which domains were read. */
    private inner class Boot(private val settings: ThemeSettings) : BootContext {
        val asked = ArrayList<String>()
        override val dataDir: Path = dir

        @Suppress("UNCHECKED_CAST")
        // The themes domain holds theme settings, and no other domain is answered.
        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            asked += domain.id
            check(domain.id == ThemesSettingsDomain.id) { "asked for another module's domain ${domain.id}" }
            return settings as T
        }

        override val services: LevelServices = Services(null)
    }

    /** The names of the roles of the answers, by their public members. */
    private fun roles(): Set<String> = ColourAnswers::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { method ->
            val name = method.name.substringBefore('-')
            if (name.startsWith("get") && method.parameterCount == 0) name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) else name
        }
        .toSet()

    /**
     * Every role of [answers], each over a spread of what it is asked over: grounds dark, light, grey and
     * coloured, fills opaque and translucent, every status, members of sets of several sizes, names,
     * data colours single and per mode, and the ladder past its ends.
     */
    private fun sample(answers: ColourAnswers): Map<String, List<Any>> {
        val grounds = listOf(answers.groundPage, answers.groundDepth(3), Color(0xFF808080), Color.White, Color.Black, Color(0xFF1E5AA8))
        val fills = grounds + listOf(Color(0x80FFFFFF), Color(0x40CC3355))
        fun over(role: (Color) -> Color) = grounds.map(role)
        fun byStatus(role: (Status) -> Color) = Status.entries.map(role)
        fun byStatusOver(role: (Status, Color) -> Color) = Status.entries.flatMap { status -> grounds.map { role(status, it) } }
        return mapOf(
            "inkMain" to listOf(answers.inkMain),
            "inkQuiet" to listOf(answers.inkQuiet),
            "inkFaint" to over(answers::inkFaint),
            "inkOff" to listOf(answers.inkOff),
            "inkLead" to listOf(answers.inkLead),
            "inkLink" to listOf(answers.inkLink),
            "inkProse" to listOf(answers.inkProse),
            "inkCaret" to listOf(answers.inkCaret),
            "inkOn" to fills.map(answers::inkOn),
            "inkInverse" to listOf(answers.inkInverse),
            "inkMatch" to listOf(answers.inkMatch),
            "paintLead" to listOf(answers.paintLead),
            "paintMark" to listOf(answers.paintMark),
            "paintPartner" to listOf(answers.paintPartner),
            "paintThird" to listOf(answers.paintThird),
            "paintPalette" to answers.paintPalette,
            "paintStatus" to byStatus(answers::paintStatus),
            "paintCategory" to listOf(1, 3, 8, 13).flatMap { count -> (0 until count).map { answers.paintCategory(it, count) } },
            "paintHashed" to listOf("", "Ann", "Celestia", "a pack with a long name").map(answers::paintHashed),
            "paintData" to listOf(answers.paintData(Color(0xFF4CAF50)), answers.paintData(Color(0xFF123456), Color(0xFFABCDEF))),
            "paintShadow" to listOf(answers.paintShadow),
            "paintShadowOn" to fills.map(answers::paintShadowOn),
            "fillLead" to over(answers::fillLead),
            "fillSelected" to over(answers::fillSelected),
            "fillQuiet" to over(answers::fillQuiet),
            "fillTrack" to over(answers::fillTrack),
            "fillStatus" to byStatusOver(answers::fillStatus),
            "fillOff" to over(answers::fillOff),
            "fillInverse" to over(answers::fillInverse),
            "fillSelection" to over(answers::fillSelection),
            "fillMatch" to over(answers::fillMatch),
            "fillPlaceholder" to over(answers::fillPlaceholder),
            "lineDivider" to over(answers::lineDivider),
            "lineFaint" to over(answers::lineFaint),
            "lineControl" to over(answers::lineControl),
            "lineActive" to over(answers::lineActive),
            "lineFocus" to over(answers::lineFocus),
            "lineStatus" to byStatusOver(answers::lineStatus),
            "lineOff" to over(answers::lineOff),
            "groundPage" to listOf(answers.groundPage),
            "groundDepth" to (-3..10).map(answers::groundDepth),
            "groundFloating" to listOf(answers.groundFloating),
            "groundDark" to listOf(answers.groundDark),
            "mediaInk" to listOf(answers.mediaInk),
            "mediaQuiet" to listOf(answers.mediaQuiet),
            "mediaFill" to listOf(answers.mediaFill),
            "mediaLine" to listOf(answers.mediaLine),
            "mediaScrim" to listOf(answers.mediaScrim),
            "mediaStatus" to byStatus(answers::mediaStatus),
        )
    }
}
