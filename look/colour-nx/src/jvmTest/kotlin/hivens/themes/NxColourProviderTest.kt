package hivens.themes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.i18n.api.FixedTranslations
import hivens.i18n.api.Translator
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.colour.api.ColourApi
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
 * module's own settings alone, and with every role nx-ui answers today equal to what nx-ui draws.
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
    fun `every role nx-ui answers today is answered exactly as NxInk, NxColor and NxTheme draw it, for every theme that ships in either mode`() {
        val fills = listOf(Color.White, Color.Black, Color(0xFF808080), Color(0xFFCC3355), Color(0x80FFFFFF))
        val keys = listOf("", "Ann", "Celestia", "a pack with a long name")
        Themes.all.forEach { theme ->
            listOf(true, false).forEach { dark ->
                val answers = NxColourProvider.open(context(manager = manager(ThemeSettings(theme = theme.id, dark = dark)))).answers.value
                val drawn = LinkedHashMap<String, Any>()
                val asked = LinkedHashMap<String, Any>()
                val scene = ImageComposeScene(8, 8, Density(1f)) {
                    NxTheme(theme, dark) {
                        val colours = NxTheme.colours
                        drawn["main"] = NxInk.main
                        drawn["quiet"] = NxInk.quiet
                        drawn["off"] = NxInk.off
                        drawn["lead"] = NxColor.lead()
                        drawn["page"] = NxColor.page
                        drawn["dark"] = NxTheme.isDark
                        drawn["floating"] = colours.topStep
                        drawn["partner"] = MaterialTheme.colorScheme.secondary
                        drawn["inverse fill"] = colours.inkMain
                        drawn["inverse ink"] = colours.step(0)
                        drawn["line on the page"] = NxInk.line
                        (-3..10).forEach { drawn["step $it"] = colours.step(it) }
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
                try {
                    scene.render()
                } finally {
                    scene.close()
                }
                asked["main"] = answers.inkMain
                asked["quiet"] = answers.inkQuiet
                asked["off"] = answers.inkOff
                asked["lead"] = answers.paintLead
                asked["page"] = answers.groundPage
                asked["dark"] = answers.groundDark
                asked["floating"] = answers.groundFloating
                asked["partner"] = answers.paintPartner
                asked["inverse fill"] = answers.fillInverse(answers.groundPage)
                asked["inverse ink"] = answers.inkInverse
                asked["line on the page"] = answers.lineDivider(answers.groundPage)
                (-3..10).forEach { asked["step $it"] = answers.groundDepth(it) }
                SchemeStatus.entries.forEach { asked["status $it"] = answers.paintStatus(Status.valueOf(it.name)) }
                Twelve.entries.forEach { asked["category $it"] = answers.paintCategory(it.ordinal, Twelve.entries.size) }
                keys.forEach { asked["hashed '$it'"] = answers.paintHashed(it) }
                fills.forEach { asked["on $it"] = answers.inkOn(it) }
                (fills.filter { it.alpha == 1f } + answers.groundDepth(2)).forEach { asked["line on $it"] = answers.lineDivider(it) }
                assertEquals(drawn, asked, "${theme.id}, dark $dark")
            }
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
