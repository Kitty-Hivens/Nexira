package hivens.look.feedback.nx

import hivens.look.manager.api.LookContext
import hivens.look.feedback.api.Feedback
import hivens.look.feedback.api.FeedbackApi
import hivens.look.feedback.api.FeedbackAnswers
import hivens.look.feedback.api.FeedbackTarget
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.SettingsDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The module's feedback provider answers the feedback axis with nx's feedback, the state layers nx drew before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxFeedbackProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the feedback axis as the nx family, named in every language the module ships`() {
        assertEquals(Feedback.id, NxFeedbackProvider.axis)
        assertEquals("nx", NxFeedbackProvider.family)
        assertEquals(FeedbackApi.VERSION, NxFeedbackProvider.contract)
        assertEquals(listOf<Any>(NxFeedbackProvider), FeedbackNxModule.contributions)
        assertTrue(FeedbackNxModule.strings.tables.values.all { NxFeedbackProvider.label in it && FeedbackNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxFeedbackProvider.open(Context(services, boot)).answers.value
        val live = NxFeedbackProvider.open(Context(services, null)).answers.value
        assertSame(nxFeedbackAnswers(), atBoot)
        assertSame(nxFeedbackAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's feedback is the state layers nx drew before the feedback axis, and disabled content at 45 percent`() {
        val nx = nxFeedbackAnswers()
        assertEquals(0.45f, nx.disabledAlpha)
        assertNotSame(FeedbackAnswers.BASE.indication(FeedbackTarget.Control, null, null), nx.indication(FeedbackTarget.Control, null, null))
        assertEquals(nx.indication(FeedbackTarget.Plane, null, null), nx.indication(FeedbackTarget.Plane, null, null), "the same question gives the same indication")
    }

    private inner class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
    }

    /** Services that note what was asked of them and hand out nothing. */
    private class Services : LevelServices {
        val asked = ArrayList<KClass<*>>()

        override fun <T : Any> find(type: KClass<T>): T? {
            asked += type
            return null
        }

        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    /** A boot context that notes which domains were read and holds none. */
    private class Boot : BootContext {
        val asked = ArrayList<String>()
        override val dataDir: Path = Path.of("unused")

        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            asked += domain.id
            return domain.defaults()
        }

        override val services: LevelServices = Services()
    }
}
