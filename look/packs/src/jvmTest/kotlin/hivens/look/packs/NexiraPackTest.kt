@file:OptIn(HostApi::class)

package hivens.look.packs

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import hivens.look.colour.api.Colour
import hivens.look.colour.api.ColourAnswers
import hivens.look.colour.api.ColourApi
import hivens.look.colour.api.ColourModule
import hivens.look.colour.api.Ink
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorModule
import hivens.look.manager.LookManagerModule
import hivens.look.motion.api.Motion
import hivens.look.motion.api.MotionModule
import hivens.look.shape.api.Shape
import hivens.look.shape.api.ShapeModule
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeModule
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceModule
import hivens.look.timing.api.Timing
import hivens.look.timing.api.TimingModule
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackModule
import hivens.look.type.api.Type
import hivens.look.type.api.TypeModule
import hivens.look.icon.api.Icon
import hivens.look.icon.api.IconModule
import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookAxis
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookSource
import hivens.widget.api.BootContext
import hivens.widget.api.BootValues
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.SettingsDomain
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The default pack names nx for colour, motion, shape, stroke, surface, timing, feedback, type and cursor and
 * Google's icons for icon, which is every axis that exists, and the look manager takes the colour axis and the pack from the modules compiled in, read
 * here the way the boot window reads them.
 */
class NexiraPackTest {

    @Test
    fun `the default pack names a family for every axis that exists, and no axis that does not exist`() {
        assertEquals(LookApi.DEFAULT_PACK, NexiraPack.id)
        assertEquals(mapOf(Colour.id to "nx", Motion.id to "nx", Shape.id to "nx", Stroke.id to "nx", Surface.id to "nx", Timing.id to "nx", Feedback.id to "nx", Type.id to "nx", Cursor.id to "nx", Icon.id to "google-rounded"), NexiraPack.providers)
        val axes = (ColourModule.contributions + MotionModule.contributions + ShapeModule.contributions + StrokeModule.contributions +
            SurfaceModule.contributions + TimingModule.contributions + FeedbackModule.contributions +
            TypeModule.contributions + IconModule.contributions + CursorModule.contributions)
            .filterIsInstance<LookAxis<*>>().map { it.id }.toSet()
        assertTrue(axes.containsAll(NexiraPack.providers.keys), "the pack names an axis no look module brings: ${NexiraPack.providers.keys - axes}")
        assertEquals(listOf<Any>(NexiraPack), LookPacksModule.contributions)
        assertTrue(LookPacksModule.strings.tables.values.all { NexiraPack.label in it }, "the pack is named in every language the module ships")
    }

    @Test
    fun `the manager takes the colour axis and the pack, and colour draws its base answers while no nx provider is there`() {
        val boot = bootOf(listOf(LookManagerModule, ColourModule, LookPacksModule))
        val state = requireNotNull(boot[LookKeys.State])
        val colour = requireNotNull(state.axis(Colour.id)) { "the manager has no colour axis" }
        assertEquals(ColourApi.VERSION, colour.contract)
        assertNull(colour.family)
        assertEquals(LookSource.Base, colour.source)
        assertEquals(listOf(LookPacksModule.ID to LookApi.DEFAULT_PACK), state.packs.map { it.moduleId to it.id })

        assertSame(ColourAnswers.BASE, requireNotNull(boot[LookKeys.Answers]).of(Colour))
        var main: Color? = null
        composeOnce { requireNotNull(boot[LookKeys.Provide]).Provide { main = Ink.main } }
        assertEquals(ColourAnswers.BASE.inkMain, main, "a widget inside the look reads the base answers")
    }

    @Test
    fun `an nx provider of colour answers it through the default pack`() {
        val answers = ColourAnswers.build { inkMain = Color(0xFFFFEEDD) }
        val nx = object : LookProvider<ColourAnswers> {
            override val label = "nx"
            override val axis = Colour.id
            override val family = "nx"
            override val contract = ColourApi.VERSION
            override fun open(context: LookContext): LookAnswers<ColourAnswers> = LookAnswers.of(answers)
        }
        val provider = object : CompiledModule {
            override val id = "test.colour.nx"
            override val name = id
            override val contributions: List<ModuleContribution> = listOf(nx)
        }
        val boot = bootOf(listOf(LookManagerModule, ColourModule, LookPacksModule, provider))
        val colour = requireNotNull(requireNotNull(boot[LookKeys.State]).axis(Colour.id))
        assertEquals("nx", colour.family)
        assertEquals(LookSource.DefaultPack, colour.source)
        assertSame(answers, requireNotNull(boot[LookKeys.Answers]).of(Colour))
    }

    /** What the look manager answers before the boot, with [compiled] compiled in and nothing chosen. */
    private fun bootOf(compiled: List<CompiledModule>): BootValues {
        val kinds = compiled.flatMap { it.contributionKinds }
        val carried = compiled.flatMap { module ->
            module.contributions.filter { item -> kinds.any { it.isInstance(item) } }.map { TakenContribution(module.id, it) }
        }
        val taken = object : TakenContributions {
            override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = MutableStateFlow(emptyList())
            override val compiledIn: List<TakenContribution<ModuleContribution>> = carried
        }
        val context = object : BootContext {
            override val dataDir: Path = Path.of("unused")
            override fun <T : Any> settings(domain: SettingsDomain<T>): T = domain.defaults()
            override fun ofModule(moduleId: String): BootContext? = null
            override val services: LevelServices = object : LevelServices {
                @Suppress("UNCHECKED_CAST")
                // One service, handed out for its own type only.
                override fun <T : Any> find(type: KClass<T>): T? = if (type == TakenContributions::class) taken as T else null

                override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
            }
        }
        return LookManagerModule.boot.answer(context)
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
