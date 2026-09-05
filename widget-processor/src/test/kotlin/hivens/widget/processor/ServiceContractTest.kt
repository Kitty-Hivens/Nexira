package hivens.widget.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@ProvidesService` and `@InjectService` were documentation the build never
 * read: the processor contained no reference to either, so a widget could claim
 * a contract it never registers, or read one nothing offers, and compile clean.
 * The registry carries them now, and an unmet contract is reported.
 *
 * An unmet contract is not a failure by itself -- a consumer whose provider is
 * absent renders its disabled state, which is exactly right for a provider the
 * user has not placed yet. What matters is that the case is visible instead of
 * looking like a widget that silently does nothing.
 */
class ServiceContractTest {

    private val music = "hivens.ui.widgets.services.MusicPlayerService"
    private val packs = "hivens.ui.widgets.services.PackLifecycleService"

    private fun widget(
        id: String,
        provides: List<String> = emptyList(),
        injects: List<String> = emptyList(),
    ) = WidgetModel(
        id = id,
        displayName = "Display",
        removable = true,
        drawsOwnSurface = false,
        slots = emptyList(),
        propsClassFqn = null,
        functionFqn = "hivens.ui.widgets.$id",
        provides = provides,
        injects = injects,
    )

    @Test
    fun `a matched pair reports nothing`() {
        val unmet = injectorsWithoutProvider(
            listOf(
                widget("home.new.music", provides = listOf(music)),
                widget("home.new.playback.mini", injects = listOf(music)),
            ),
        )
        assertTrue(unmet.isEmpty(), "the provider is in the build, so the consumer can be satisfied")
    }

    @Test
    fun `an injector with no provider anywhere is reported`() {
        val unmet = injectorsWithoutProvider(listOf(widget("watcher", injects = listOf(music))))
        assertEquals(listOf(music), unmet.values.single())
        assertEquals("watcher", unmet.keys.single().id)
    }

    @Test
    fun `only the unmet half of a widget's contracts is reported`() {
        val unmet = injectorsWithoutProvider(
            listOf(
                widget("home.new.music", provides = listOf(music)),
                widget("watcher", injects = listOf(music, packs)),
            ),
        )
        assertEquals(listOf(packs), unmet.values.single(), "the satisfied contract must not be listed")
    }

    @Test
    fun `a provider nobody reads is fine`() {
        assertTrue(
            injectorsWithoutProvider(listOf(widget("home.new.music", provides = listOf(music)))).isEmpty(),
            "offering a contract early is how a provider ships before its consumers",
        )
    }

    // --- what the generated source carries ---

    @Test
    fun `declared contracts reach the descriptor`() {
        val src = renderRegistry(
            listOf(
                widget("home.new.music", provides = listOf(music)),
                widget("home.new.playback.mini", injects = listOf(music)),
            ),
        )
        assertContains(src, """override val provides: Set<String> = setOf("$music")""")
        assertContains(src, """override val injects: Set<String> = setOf("$music")""")
    }

    @Test
    fun `a widget with no contracts overrides neither`() {
        val src = renderRegistry(listOf(widget("home.new.clock")))
        assertFalse(src.contains("override val provides"), "the interface default already says empty")
        assertFalse(src.contains("override val injects"))
    }
}
