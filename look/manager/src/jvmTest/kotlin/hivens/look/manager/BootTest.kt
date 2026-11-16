@file:OptIn(HostApi::class)

package hivens.look.manager

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookSource
import hivens.widget.api.BootContext
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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The look the boot window opens in is the look the shell draws once it is up, over the same choice and the same modules. */
class BootTest {

    private class Host(compiled: List<TakenContribution<ModuleContribution>>) : TakenContributions {
        override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = MutableStateFlow(emptyList())
        override val compiledIn: List<TakenContribution<ModuleContribution>> = compiled
    }

    private class Boot(private val choice: LookChoice, taken: TakenContributions) : BootContext {
        override val dataDir: Path = Path.of("unused")

        @Suppress("UNCHECKED_CAST")
        // The manager asks for its own domain only, whose value is a choice.
        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            check(domain.id == LookSettingsDomain.id) { "asked for ${domain.id}" }
            return choice as T
        }

        override val services: LevelServices = object : LevelServices {
            @Suppress("UNCHECKED_CAST")
            // One service, of the one type it is handed out for.
            override fun <T : Any> find(type: KClass<T>): T? = if (type == TakenContributions::class) taken as T else null

            override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
        }
    }

    private val shade = TestProvider("shade", "material")
    private val motion = TestProvider("motion", "standard")
    private val idle = TestProvider("shade", "standard")
    private val compiled = taken(
        TestAxis("shade"), TestAxis("motion"), TestAxis("sound"), shade, motion, idle,
        TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard", "motion" to "standard")),
        TestPack("material", mapOf("shade" to "material")),
    )
    private val choice = choice(pack = "material")

    @Test
    fun `the boot answers the choice, the state and the answers the live manager answers`() {
        val boot = LookManagerModule.boot.answer(Boot(choice, Host(compiled)))
        val scope = eagerScope()
        val live = LookManager(MutableStateFlow(choice), lookTaken(Host(compiled), scope), scope, setOf("test.look"), Dispatchers.Unconfined, Dispatchers.Unconfined)

        assertEquals(choice, boot[LookKeys.Settings])
        assertEquals(live.state.value, boot[LookKeys.State])
        val bootAnswers = (boot[LookKeys.Provide] as FixedProvision).answers
        assertEquals(live.answers.value, bootAnswers)
        assertEquals(mapOf("shade" to "shade/material", "motion" to "motion/standard", "sound" to "base"), bootAnswers.associate { it.axis.id to (it.value as Shade).name })
        live.close()
    }

    @Test
    fun `the providers the boot opened are closed again before the boot window draws`() {
        LookManagerModule.boot.answer(Boot(choice, Host(compiled)))
        assertEquals(1, shade.opened)
        assertFalse(shade.isOpen)
        assertFalse(motion.isOpen)
        assertEquals(0, idle.opened, "only the chosen ones are opened before the boot too")
    }

    @Test
    fun `without the host's contributions the boot answers base answers for nothing, and still the choice`() {
        val boot = LookManagerModule.boot.answer(
            object : BootContext by Boot(choice, Host(emptyList())) {
                override val services: LevelServices = object : LevelServices {
                    override fun <T : Any> find(type: KClass<T>): T? = null
                    override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
                }
            },
        )
        assertEquals(choice, boot[LookKeys.Settings])
        assertTrue((boot[LookKeys.Provide] as FixedProvision).answers.isEmpty())
    }

    @Test
    fun `the providers the boot opens run their work off the thread that asks`() {
        var dispatcher: Any? = null
        val recording = object : LookProvider<Shade> {
            override val label = "recording"
            override val axis = "shade"
            override val family = "material"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Shade> {
                dispatcher = context.coroutineScope.coroutineContext[ContinuationInterceptor]
                return LookAnswers.of(Shade("recorded"))
            }
        }
        LookManagerModule.boot.answer(Boot(choice, Host(taken(TestAxis("shade"), recording, TestPack("material", mapOf("shade" to "material"))))))
        assertEquals(Dispatchers.Default, dispatcher, "a provider's own work is not run inline on the boot thread")
    }

    @Test
    fun `a choice naming a pack no module compiled in brings opens the boot window on the default pack`() {
        val boot = LookManagerModule.boot.answer(Boot(choice(pack = "someone.jars.pack"), Host(compiled)))
        val state = requireNotNull(boot[LookKeys.State])
        assertEquals("standard", state.axis("shade")?.family)
        assertEquals(LookSource.DefaultPack, state.axis("shade")?.source)
    }
}
