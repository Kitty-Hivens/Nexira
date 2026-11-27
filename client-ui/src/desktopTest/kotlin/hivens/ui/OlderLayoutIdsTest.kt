package hivens.ui

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The widgets of the arrangement an older build saved keep their state: their names are read from its file as it is. */
@OptIn(ExperimentalPathApi::class)
class OlderLayoutIdsTest {

    private val dir = Files.createTempDirectory("older-layout")
    private val file = dir.resolve("layout-graph.json")

    @AfterTest
    fun tearDown() = dir.deleteRecursively()

    @Test
    fun `every widget the older file names, nested ones included`() {
        Files.writeString(
            file,
            """
            {"schema_version": 7, "surfaces": {"home.new": {"slots": {"main": {"widgets": [
              {"kind": "notes", "instance_id": "notes-1a2b"},
              {"kind": "container.group", "instance_id": "group-9", "children": {"body": {"widgets": [
                {"kind": "checklist", "instance_id": "checklist-7"}
              ]}}}
            ]}}}}}
            """.trimIndent(),
        )
        assertEquals(setOf("notes-1a2b", "group-9", "checklist-7"), olderLayoutIds(file, Json))
    }

    @Test
    fun `no file and a file that is not JSON name nothing`() {
        assertEquals(emptySet(), olderLayoutIds(file, Json))
        Files.writeString(file, "{ not json")
        assertEquals(emptySet(), olderLayoutIds(file, Json))
    }
}
