package hivens.look.colour.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import hivens.look.manager.api.Status
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ColourApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookColourApi")) { "the build did not pass nexira.lookColourApi" }
        assertEquals(ColourApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the colour axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Colour), ColourModule.contributions)
        assertEquals("colour", Colour.id)
        assertEquals(ColourApi.VERSION, Colour.contract)
        assertEquals(ColourAnswers::class, Colour.type)
        assertSame(ColourAnswers.BASE, Colour.base)
        assertEquals(mapOf(ColourApi.ID to ColourApi.VERSION), ColourModule.provides)
        assertEquals(mapOf(ColourApi.ID to setOf("hivens.look.colour.api")), ColourModule.apiPackages)
    }

    @Test
    fun `every role of the answers is listed here, and a provider can fill every one`() {
        val roles = publicMembers(ColourAnswers::class.java)
        assertEquals(roles, sample(ColourAnswers.BASE).keys, "a role this test does not list goes unchecked below")
        assertEquals(roles, fillable(), "a role a provider cannot fill can only ever hold its base answer")
    }

    @Test
    fun `every role has a base answer, a colour that draws`() {
        sample(ColourAnswers.BASE).forEach { (role, answers) ->
            answers.filterIsInstance<Color>().forEach { assertTrue(it.isSpecified, "$role answers no colour") }
        }
    }

    @Test
    fun `the base answers are colourless`() {
        sample(ColourAnswers.BASE).forEach { (role, answers) ->
            answers.filterIsInstance<Color>().forEach { colour ->
                assertTrue(colour.red == colour.green && colour.green == colour.blue, "$role answers $colour, which is not a grey")
            }
        }
    }

    @Test
    fun `nothing filled is the base answers, role by role`() {
        assertEquals(sample(ColourAnswers.BASE), sample(ColourAnswers.build {}))
    }

    @Test
    fun `the roles made of others follow what a provider fills, and a role it fills is its own`() {
        val lead = Color(0xFFCC3355)
        val quiet = Color(0xFF3355CC)
        val rule = Color(0xFF22AA44)
        val answers = ColourAnswers.build {
            paintLead = lead
            inkQuiet = quiet
            lineDivider = { rule }
        }
        val ground = Color(0xFF202020)
        assertEquals(lead, answers.inkLead)
        assertEquals(lead, answers.inkCaret)
        assertEquals(lead, answers.paintMark)
        assertEquals(lead, answers.lineFocus(ground))
        assertEquals(lerp(ground, lead, 0.16f), answers.fillLead(ground))
        assertEquals(lead.copy(alpha = 0.4f), answers.fillSelection(ground))
        assertEquals(quiet.copy(alpha = 0.45f), answers.inkOff)
        assertEquals(lerp(ground, quiet, 0.12f), answers.lineFaint(ground))
        assertEquals(rule, answers.lineDivider(ground))
        assertEquals(ColourAnswers.BASE.inkMain, answers.inkMain, "a role nobody filled keeps its base answer")
    }

    @Test
    fun `the ladder answers every step, carried on past its ends`() {
        val base = ColourAnswers.BASE
        val tones = (-3..12).map { base.groundDepth(it).red }
        assertEquals(tones.sorted(), tones, "the ladder runs one way, the page darkest")
        assertEquals(base.groundPage, base.groundDepth(0))
        assertEquals(Color.White, base.groundDepth(10_000))
        assertEquals(Color.Black, base.groundDepth(-10_000))
    }

    @Test
    fun `ink on a fill is the main ink or the page, whichever stands further from it`() {
        val base = ColourAnswers.BASE
        assertEquals(base.groundPage, base.inkOn(Color.White))
        assertEquals(base.inkMain, base.inkOn(Color.Black))
    }

    private enum class Phase : ColourCategory { Before, During, After }

    @Test
    fun `outside any look a widget reads the base answers, over the page or the ground it is given`() {
        val base = ColourAnswers.BASE
        val ground = Color(0xFF303030)
        val seen = HashMap<String, Any>()
        composeOnce {
            seen["main"] = Ink.main
            seen["current"] = Ground.current
            seen["divider"] = Line.divider
            seen["phase"] = Paint.category(Phase.After)
            CompositionLocalProvider(LocalGround provides ground) {
                seen["onGround"] = Ground.current
                seen["lead"] = Fill.lead
                seen["status"] = Fill.status(Status.Error)
                seen["mix"] = Ground.mix(Color.White, 0.5f)
            }
        }
        assertEquals(base.inkMain, seen["main"])
        assertEquals(base.groundPage, seen["current"], "outside any plane the content sits on the page")
        assertEquals(base.lineDivider(base.groundPage), seen["divider"])
        assertEquals(base.paintCategory(2, 3), seen["phase"])
        assertEquals(ground, seen["onGround"])
        assertEquals(base.fillLead(ground), seen["lead"])
        assertEquals(base.fillStatus(Status.Error, ground), seen["status"])
        assertEquals(lerp(ground, Color.White, 0.5f), seen["mix"])
    }

    /** The names of [type]'s public members, a getter by its property's name, with the suffix a value class parameter adds cut off. */
    private fun publicMembers(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { method ->
            val name = method.name.substringBefore('-')
            if (name.startsWith("get") && method.parameterCount == 0) name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) else name
        }
        .toSet()

    /** The roles the builder has a field for. */
    private fun fillable(): Set<String> = ColourAnswers.Builder::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("set") }
        .map { it.name.substringBefore('-').removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** Every role of [answers] asked over a spread of what it is asked over, by role name. */
    private fun sample(answers: ColourAnswers): Map<String, List<Any>> {
        val grounds = listOf(answers.groundPage, Color(0xFF808080), Color.White, Color.Black)
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
            "inkOn" to over(answers::inkOn),
            "inkInverse" to listOf(answers.inkInverse),
            "inkMatch" to listOf(answers.inkMatch),
            "paintLead" to listOf(answers.paintLead),
            "paintMark" to listOf(answers.paintMark),
            "paintPartner" to listOf(answers.paintPartner),
            "paintStatus" to byStatus(answers::paintStatus),
            "paintCategory" to (0..9).map { answers.paintCategory(it, 10) },
            "paintHashed" to listOf("", "Ann", "a very long name of a pack").map(answers::paintHashed),
            "paintData" to listOf(answers.paintData(Color(0xFF777777)), answers.paintData(Color(0xFF555555), Color(0xFF999999))),
            "paintTerminal" to (-1..16).map(answers::paintTerminal),
            "paintShadow" to listOf(answers.paintShadow),
            "paintShadowOn" to over(answers::paintShadowOn),
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
            "groundDepth" to (-2..8).map(answers::groundDepth),
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

/** Composes [content] once, with nothing drawn, and disposes it. */
private fun composeOnce(content: @Composable () -> Unit) {
    val recomposer = Recomposer(Dispatchers.Unconfined)
    val composition = Composition(NothingApplier, recomposer)
    try {
        composition.setContent(content)
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}

private object NothingApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
