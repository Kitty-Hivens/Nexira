package hivens.launcher.runtime

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MojangArgumentsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun args(text: String): MojangArguments =
        json.decodeFromString(MojangArguments.serializer(), text)

    @Test
    fun `bare strings pass through and placeholders are preserved`() {
        val a = args("""{"jvm":["-Dfoo=bar","-cp","${'$'}{classpath}"]}""")
        assertEquals(listOf("-Dfoo=bar", "-cp", $$"${classpath}"), flattenArguments(a.jvm, "linux"))
    }

    @Test
    fun `os-gated entry is included only on a matching platform`() {
        val a = args("""{"jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":"-XstartOnFirstThread"}]}""")
        assertEquals(listOf("-XstartOnFirstThread"), flattenArguments(a.jvm, "osx"))
        assertEquals(emptyList<String>(), flattenArguments(a.jvm, "linux"))
    }

    @Test
    fun `a value array contributes every token`() {
        val a = args("""{"jvm":[{"rules":[{"action":"allow"}],"value":["-A","-B"]}]}""")
        assertEquals(listOf("-A", "-B"), flattenArguments(a.jvm, "linux"))
    }

    @Test
    fun `feature-gated entries are dropped (we enable no features)`() {
        val a = args("""{"game":[{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}]}""")
        assertEquals(emptyList<String>(), flattenArguments(a.game, "linux"))
    }
}
