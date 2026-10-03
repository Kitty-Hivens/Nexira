package hivens.widget

import hivens.widget.generated.GeneratedWidgetRegistry
import hivens.widget.model.BundledPresets
import hivens.widget.model.walkInstances
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * What a shipped preset puts on a surface is widgets this build has, configured in
 * words those widgets read. A preset naming a widget that was renamed draws a hole,
 * and one carrying a misspelt prop draws the widget at its default, both silently,
 * so both are caught here instead.
 */
class BundledPresetWidgetsTest {

    private val strict = Json { ignoreUnknownKeys = false }

    @Test
    fun `every widget a shipped preset places exists and reads its props`() {
        val registry = GeneratedWidgetRegistry.all()
        BundledPresets.IDS.forEach { id ->
            BundledPresets.load(id).walkInstances().forEach { instance ->
                val descriptor = assertNotNull(registry[instance.kind], "$id places ${instance.kind.value}, which this build does not have")
                if (instance.props.isEmpty()) return@forEach
                val serializer = descriptor.propsSerializer
                    ?: fail("$id gives props to ${instance.kind.value}, which takes none")
                runCatching { strict.decodeFromJsonElement(serializer, JsonObject(descriptor.defaultPropsJson + instance.props)) }
                    .onFailure { fail("$id: props for ${instance.kind.value} do not read: ${it.message}") }
            }
        }
    }
}
