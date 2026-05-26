package hivens.launcher.instance

import hivens.launcher.nbt.Nbt
import hivens.launcher.nbt.NbtCompound
import hivens.launcher.nbt.NbtValue
import hivens.launcher.nbt.RootCompound
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServersDatReaderTest {

    private lateinit var instanceDir: Path
    private val reader = ServersDatReader()

    @BeforeTest
    fun setUp() {
        instanceDir = Files.createTempDirectory("servers-dat-test-")
        instanceDir.toFile().deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        instanceDir.toFile().deleteRecursively()
    }

    @Test
    fun `missing file returns empty list`() = runBlocking {
        assertEquals(emptyList(), reader.read(instanceDir))
    }

    @Test
    fun `empty servers list returns empty result`() = runBlocking {
        writeServersDat(emptyList())
        assertEquals(emptyList(), reader.read(instanceDir))
    }

    @Test
    fun `full entry decodes every field`() = runBlocking {
        writeServersDat(listOf(linkedMapOf(
            "name"            to NbtValue.String("My Server"),
            "ip"              to NbtValue.String("play.example.org:25566"),
            "icon"            to NbtValue.String("BASE64ENCODED_PNG_PLACEHOLDER"),
            "acceptTextures"  to NbtValue.Byte(1),
            "hidden"          to NbtValue.Byte(1),
        )))
        val entries = reader.read(instanceDir)
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("My Server", e.name)
        assertEquals("play.example.org:25566", e.ip)
        assertEquals("BASE64ENCODED_PNG_PLACEHOLDER", e.iconBase64)
        assertEquals(1.toByte(), e.acceptTexturesMode)
        assertTrue(e.hidden)
    }

    @Test
    fun `blank name falls back to ip`() = runBlocking {
        writeServersDat(listOf(linkedMapOf(
            "name" to NbtValue.String(""),
            "ip"   to NbtValue.String("10.0.0.1"),
        )))
        val e = reader.read(instanceDir).single()
        assertEquals("10.0.0.1", e.name)
        assertEquals("10.0.0.1", e.ip)
        assertNull(e.iconBase64)
        assertNull(e.acceptTexturesMode)
        assertFalse(e.hidden)
    }

    @Test
    fun `corrupt file returns empty list, does not throw`() = runBlocking {
        Files.writeString(instanceDir.resolve("servers.dat"), "this is not NBT")
        assertEquals(emptyList(), reader.read(instanceDir))
    }

    @Test
    fun `multiple entries preserve list order`() = runBlocking {
        writeServersDat(listOf(
            linkedMapOf("name" to NbtValue.String("A"), "ip" to NbtValue.String("a")),
            linkedMapOf("name" to NbtValue.String("B"), "ip" to NbtValue.String("b")),
            linkedMapOf("name" to NbtValue.String("C"), "ip" to NbtValue.String("c")),
        ))
        val names = reader.read(instanceDir).map { it.name }
        assertEquals(listOf("A", "B", "C"), names)
    }

    private fun writeServersDat(entries: List<LinkedHashMap<String, NbtValue>>) {
        val list = entries.map { NbtValue.Compound(NbtCompound(it)) }
        val root = RootCompound(
            name = "",
            value = NbtCompound(linkedMapOf(
                "servers" to NbtValue.List(Nbt.TYPE_COMPOUND, list),
            )),
        )
        Files.newOutputStream(instanceDir.resolve("servers.dat")).use { Nbt.write(it, root, gzipped = false) }
    }
}
