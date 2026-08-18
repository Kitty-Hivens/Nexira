package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the composite owes its callers: precedence that a contribution cannot
 * argue with, and a record of what it refused.
 */
class CompositeWidgetRegistryTest {

    private fun descriptor(id: String, label: String) = object : WidgetDescriptor {
        override val kind = WidgetKind(id)
        override val displayName = label
        override val removable = true
        @Composable override fun Render(instance: WidgetInstance) = Unit
    }

    private fun registryOf(vararg entries: Pair<String, String>) = object : WidgetRegistry {
        private val map = entries.associate { (id, label) -> WidgetKind(id) to descriptor(id, label) }
        override fun all() = map
        override fun get(kind: WidgetKind) = map[kind]
    }

    @Test
    fun `kinds from every source are reachable`() {
        val composite = CompositeWidgetRegistry(
            listOf(registryOf("clock" to "Clock"), registryOf("notes" to "Notes")),
        )
        assertEquals(setOf(WidgetKind("clock"), WidgetKind("notes")), composite.all().keys)
        assertEquals("Notes", composite[WidgetKind("notes")]?.displayName)
    }

    @Test
    fun `the first source wins a collision`() {
        // The built-in registry is passed first so a contribution cannot take
        // over a kind the application depends on -- the shell regions and the
        // sign-in panel are non-removable for exactly that reason, and shadowing
        // them by id would be the same removal through a side door.
        val composite = CompositeWidgetRegistry(
            listOf(registryOf("appshell.left" to "built-in"), registryOf("appshell.left" to "contributed")),
        )
        assertEquals("built-in", composite[WidgetKind("appshell.left")]?.displayName)
    }

    @Test
    fun `a refused kind is reported rather than dropped in silence`() {
        val composite = CompositeWidgetRegistry(
            listOf(
                registryOf("appshell.left" to "built-in", "clock" to "Clock"),
                registryOf("appshell.left" to "contributed", "notes" to "Notes"),
            ),
        )
        assertEquals(listOf(ShadowedKind(WidgetKind("appshell.left"), heldBy = 0, bySource = 1)), composite.shadowed)
    }

    @Test
    fun `nothing is shadowed when the sources are disjoint`() {
        val composite = CompositeWidgetRegistry(
            listOf(registryOf("clock" to "Clock"), registryOf("notes" to "Notes")),
        )
        assertTrue(composite.shadowed.isEmpty())
    }

    @Test
    fun `an absent kind is null, not an error`() {
        // A layout can name a kind no source offers: the renderer keeps it on
        // disk behind the unknown-widget decorator, so the lookup has to answer
        // rather than throw.
        assertNull(CompositeWidgetRegistry(listOf(registryOf("clock" to "Clock")))[WidgetKind("gone")])
    }

    @Test
    fun `no sources is a registry with nothing in it`() {
        val composite = CompositeWidgetRegistry(emptyList())
        assertTrue(composite.all().isEmpty())
        assertTrue(composite.shadowed.isEmpty())
    }
}
