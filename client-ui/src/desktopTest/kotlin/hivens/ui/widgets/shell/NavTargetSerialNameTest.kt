package hivens.ui.widgets.shell

import hivens.widget.api.widgetPropsJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

// Guards a cross-module contract: the v3 -> v4 layout migration lives in
// :client-launcher, which cannot see NavTarget, so it emits nav.entry props
// as raw strings ("Home", "Console", ...). Those strings must equal
// NavTarget's serial names, and the migration's hardcoded set must stay the
// full target set. A NavTarget rename would otherwise break the migration
// silently -- this fails the build instead.
class NavTargetSerialNameTest {

    private val expected = setOf("Home", "Library", "Browse", "Profile", "Wardrobe", "Settings", "About", "Console", "Logout")

    @Test
    fun `NavTarget constant names match the migration's expected set`() {
        assertEquals(expected, NavTarget.entries.map { it.name }.toSet())
    }

    @Test
    fun `each NavTarget serializes to its own constant name`() {
        NavTarget.entries.forEach { t ->
            val json = widgetPropsJson.encodeToJsonElement(NavTarget.serializer(), t)
            assertEquals(t.name, json.jsonPrimitive.content)
        }
    }

    @Test
    fun `migration-style props decode into the matching NavTarget`() {
        NavTarget.entries.forEach { t ->
            val props = JsonObject(mapOf("target" to JsonPrimitive(t.name)))
            val decoded = widgetPropsJson.decodeFromJsonElement(NavEntryProps.serializer(), props)
            assertEquals(t, decoded.target)
        }
    }
}
