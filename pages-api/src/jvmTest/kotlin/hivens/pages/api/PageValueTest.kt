package hivens.pages.api

import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageValueTest {

    @Serializable
    private data class Target(val project: String, val into: String? = null)

    private val name = PageValue("page.name", String.serializer())
    private val target = PageValue("page.target", Target.serializer())

    @Test
    fun `a value goes into an opening and is read from its entry`() {
        val show = Show(ConfigId("project"), mapOf(name.of("Sodium"), target.of(Target("sodium", "pack"))))
        assertEquals("Sodium", name.read(show.values))
        assertEquals(Target("sodium", "pack"), target.read(show.values))
    }

    @Test
    fun `a page opened without it has none`() {
        assertNull(name.read(emptyMap()))
    }

    @Test
    fun `what this build cannot read is no value`() {
        assertNull(target.read(mapOf(target.id to JsonPrimitive("text"))))
    }

    @Test
    fun `what a newer build wrote beside it is passed over`() {
        val newer = buildJsonObject {
            put("project", "sodium")
            put("later", true)
        }
        assertEquals(Target("sodium"), target.read(mapOf(target.id to newer)))
    }

    @Test
    fun `its source lives on the page`() {
        assertEquals(ScopeLevel.Page, name.key.level)
        assertEquals(name.id, name.key.id)
    }
}
