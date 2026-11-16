package hivens.ui.widgets.modules

import hivens.i18n.I18nModule
import hivens.i18n.api.I18nKeys
import hivens.i18n.wordsOf
import hivens.ui.ModuleAppLevel
import hivens.ui.i18n.NexiraStrings
import hivens.widget.api.ModuleStrings
import hivens.widget.api.TakenWords
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the host hands the language module of the words every owner ships, and when. */
class ModuleWordsTest {

    private val dataDir: Path = Files.createTempDirectory("module-words")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dataDir.deleteRecursively()
    }

    private fun snapshot(words: Map<String, ModuleStrings>) = ModuleAppLevel.noJars().copy(words = words)

    private val module = ModuleStrings(
        tables = mapOf("en" to mapOf("hello" to "Hello from the module")),
        namespaces = mapOf(NexiraStrings.NAMESPACE to mapOf("en" to mapOf("navBack" to "Back, says the module"))),
    )

    @Test
    fun `a module that leaves takes its words with it, those for other namespaces among them`() {
        ModuleAppLevel(dataDir, listOf(I18nModule), scope).use { shell ->
            val ownBack = shell.value(I18nKeys.Words).strings(NexiraStrings.NAMESPACE).text("navBack")
            shell.jars.value = snapshot(mapOf("mod" to module))
            assertEquals("Hello from the module", shell.value(I18nKeys.Words).strings("mod").text("hello"))
            assertEquals("Back, says the module", shell.value(I18nKeys.Words).strings(NexiraStrings.NAMESPACE).text("navBack"))

            shell.jars.value = snapshot(emptyMap())
            assertEquals("hello", shell.value(I18nKeys.Words).strings("mod").text("hello"))
            assertEquals(ownBack, shell.value(I18nKeys.Words).strings(NexiraStrings.NAMESPACE).text("navBack"), "the launcher's own word is back")
        }
    }

    /** Words that hold [first] the first time they are read and [then] ever after, as a jar loaded while the engine is made would leave them. */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class Arriving(val first: Map<String, ModuleStrings>, val then: Map<String, ModuleStrings>) : StateFlow<Map<String, ModuleStrings>> {
        private val reads = AtomicInteger()
        override val value: Map<String, ModuleStrings> get() = if (reads.getAndIncrement() == 0) first else then
        override val replayCache: List<Map<String, ModuleStrings>> get() = listOf(then)
        override suspend fun collect(collector: FlowCollector<Map<String, ModuleStrings>>): Nothing {
            collector.emit(then)
            awaitCancellation()
        }
    }

    @Test
    fun `a jar's words that arrive while the engine is made are taken`() {
        val launcher = mapOf(NexiraStrings.NAMESPACE to NexiraStrings.words())
        val arriving = Arriving(launcher, launcher + ("looks" to ModuleStrings(mapOf("en" to mapOf("night" to "Night")))))
        val words = object : TakenWords {
            override val all: StateFlow<Map<String, ModuleStrings>> = arriving
        }
        ModuleAppLevel(dataDir, listOf(I18nModule), scope, words = words).use { shell ->
            assertEquals("Night", shell.value(I18nKeys.Words).strings("looks").text("night"))
        }
    }

    @Test
    fun `a module's plain files win over the files it wrote under its own namespace by name`() {
        val strings = ModuleStrings(
            tables = mapOf("en" to mapOf("hello" to "plain")),
            namespaces = mapOf("mod" to mapOf("en" to mapOf("hello" to "named", "only" to "only named"))),
        )
        assertEquals(mapOf("hello" to "plain", "only" to "only named"), strings.wordsOf("mod").getValue("mod").getValue("en"))
    }
}
