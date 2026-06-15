package hivens.core.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private enum class Fruit { Apple, Banana, Unknown }

private object FruitSerializer : KSerializer<Fruit> by LenientEnumSerializer(
    Fruit.entries.toTypedArray(), Fruit.Unknown, Fruit.serializer(),
)

@Serializable
private enum class Veg {
    @SerialName("carrot_v2") Carrot,
    Potato,
    Unknown,
}

private object VegSerializer : KSerializer<Veg> by LenientEnumSerializer(
    Veg.entries.toTypedArray(), Veg.Unknown, Veg.serializer(),
)

@Serializable
private data class Basket(
    @Serializable(with = FruitSerializer::class) val fruit: Fruit = Fruit.Apple,
)

class LenientEnumSerializerTest {

    // Mirrors the production shared Json: coercion ON. The whole point is that
    // the serializer lands on Unknown rather than letting coerceInputValues
    // rewrite the field to its declared default.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun `known value round-trips`() {
        assertEquals(Fruit.Banana, json.decodeFromString(FruitSerializer, "\"Banana\""))
        assertEquals("\"Banana\"", json.encodeToString(FruitSerializer, Fruit.Banana))
    }

    @Test
    fun `unknown wire string folds to Unknown, not a wrong real constant`() {
        assertEquals(Fruit.Unknown, json.decodeFromString(FruitSerializer, "\"Durian\""))
    }

    @Test
    fun `a value decoded as Unknown re-serialises to the Unknown wire name -- lossy by design`() {
        val decoded = json.decodeFromString(FruitSerializer, "\"Durian\"")
        assertEquals("\"Unknown\"", json.encodeToString(FruitSerializer, decoded))
    }

    @Test
    fun `a SerialName-bearing constant maps by its serial name`() {
        assertEquals(Veg.Carrot, json.decodeFromString(VegSerializer, "\"carrot_v2\""))
        assertEquals("\"carrot_v2\"", json.encodeToString(VegSerializer, Veg.Carrot))
        // Once @SerialName pins the wire value, the constant's own name is no
        // longer a recognised input and folds to Unknown.
        assertEquals(Veg.Unknown, json.decodeFromString(VegSerializer, "\"Carrot\""))
    }

    @Test
    fun `field serializer beats coerceInputValues -- unknown lands on Unknown, not the field default`() {
        val basket = json.decodeFromString(Basket.serializer(), """{"fruit":"Durian"}""")
        assertEquals(Fruit.Unknown, basket.fruit)
    }
}
