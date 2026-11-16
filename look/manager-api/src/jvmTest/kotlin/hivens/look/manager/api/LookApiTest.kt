package hivens.look.manager.api

import androidx.compose.runtime.ProvidedValue
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class LookApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookManagerApi")) { "the build did not pass nexira.lookManagerApi" }
        assertEquals(LookApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `every typed key carries the id its constant names, at the app level`() {
        val pairs = listOf<Pair<String, Any>>(
            LookKeys.SETTINGS_ID to LookKeys.Settings,
            LookKeys.UPDATE_SETTINGS_ID to LookKeys.UpdateSettings,
            LookKeys.PROVIDE_ID to LookKeys.Provide,
            LookKeys.STATE_ID to LookKeys.State,
        )
        pairs.forEach { (id, key) ->
            val (keyId, level) = when (key) {
                is SourceKey<*> -> key.id to key.level
                is CommandKey<*> -> key.id to key.level
                else -> error("not a key: $key")
            }
            assertEquals(id, keyId)
            assertEquals(ScopeLevel.App, level, id)
        }
        assertEquals("look.provide", LookKeys.PROVIDE_ID, "the one key a host reads the look through")
    }

    @Test
    fun `nothing chosen is the default pack with no axis apart and every feature left to the providers`() {
        val nothing = LookChoice.DEFAULT
        assertEquals(null, nothing.pack)
        assertEquals(emptyMap(), nothing.overrides)
        assertEquals(null, nothing.variant)
        assertEquals(null, nothing.blur)
        assertEquals(nothing, LookChoice.build {})
    }

    @Test
    fun `a choice is made and changed through the builder and the with functions, and reads back as written`() {
        val chosen = LookChoice.build {
            pack = "material"
            override("colour", "nx")
            variant = LookVariant.Light
            blur = false
        }
        assertEquals(chosen, LookChoice.DEFAULT.withPack("material").withOverride("colour", "nx").withVariant(LookVariant.Light).withBlur(false))
        assertEquals(mapOf("colour" to "nx"), chosen.overrides)
        assertEquals(emptyMap(), chosen.withOverride("colour", null).overrides, "a null family clears the axis")
        assertEquals(chosen, Json.decodeFromString(LookChoice.serializer(), Json.encodeToString(LookChoice.serializer(), chosen)))
        assertEquals(LookChoice.DEFAULT, Json.decodeFromString(LookChoice.serializer(), "{}"))
    }

    @Test
    fun `a choice has no public constructor a module could be built against`() {
        val constructors = LookChoice::class.java.constructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertEquals(emptyList(), constructors.map { it.toGenericString() }, "a field added later would break a module that called it")
    }

    @Test
    fun `a subtree's choice laid over the person's keeps what it does not name`() {
        val person = LookChoice.build {
            pack = "fluent"
            override("shade", "own")
            variant = LookVariant.Dark
        }
        val subtree = LookChoice.build {
            pack = "material"
            override("motion", "calm")
        }
        val merged = person.overlaidWith(subtree)
        assertEquals("material", merged.pack)
        assertEquals(mapOf("shade" to "own", "motion" to "calm"), merged.overrides)
        assertEquals(LookVariant.Dark, merged.variant)
        assertEquals("fluent", person.overlaidWith(LookChoice.build { override("shade", "x") }).pack, "a subtree naming no pack keeps the person's")
    }

    private object Wobble : LookAxis<Int>("test.wobble", ApiVersion(1, 0), Int::class, 0) {
        override val label = "wobble"
    }

    @Test
    fun `an axis provides its own local and can name no other`() {
        assertTrue(Modifier.isFinal(LookAxis::class.java.getMethod("getLocal").modifiers), "an axis cannot hand over a local of the host's")
        val contribution = ModuleContribution::class.java.methods.map { it.name }.toSet()
        val hooks = LookAxis::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.name !in contribution }
            .filter { ProvidedValue::class.java.isAssignableFrom(it.returnType) || it.returnType == List::class.java }
        assertEquals(emptyList(), hooks.map { it.name }, "no axis code says what the composition is given")
        assertNotSame<Any>(Wobble.local, object : LookAxis<Int>("test.other", ApiVersion(1, 0), Int::class, 0) {
            override val label = "other"
        }.local, "each axis has a local of its own")
    }

    @Test
    fun `a provider's id is made of its axis and family unless it gives one`() {
        val provider = object : LookProvider<Int> {
            override val axis = "test.wobble"
            override val family = "jelly"
            override val contract = ApiVersion(1, 0)
            override val label = "jelly"
            override fun open(context: LookContext) = LookAnswers.of(1)
        }
        assertEquals("test.wobble:jelly", provider.id)
    }

    @Test
    fun `a feature is offered only where the provider answering the axis declares it`() {
        val state = LookState(
            choice    = LookChoice.DEFAULT,
            axes      = listOf(
                LookAxisState("surface", ApiVersion(1, 0), "nx", LookSource.DefaultPack, setOf(LookCapability.BLUR)),
                LookAxisState("colour", ApiVersion(1, 0), null, LookSource.Base, emptySet()),
            ),
            providers = emptyList(),
            packs     = emptyList(),
        )
        assertTrue(state.offers("surface", LookCapability.BLUR))
        assertFalse(state.offers("surface", LookCapability.VARIANTS))
        assertFalse(state.offers("colour", LookCapability.BLUR), "base answers declare nothing")
        assertFalse(state.offers("motion", LookCapability.BLUR), "an axis no module brings offers nothing")
        assertTrue(state.offers(LookCapability.BLUR), "the blur setting exists while one provider in use has a blur")
        assertFalse(state.offers(LookCapability.VARIANTS))
    }
}
