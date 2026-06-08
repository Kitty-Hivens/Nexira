package hivens.launcher.nbt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NbtTest {

    @Test
    fun `write then read round-trips every tag type`() {
        val payload = NbtCompound(linkedMapOf(
            "byte"     to NbtValue.Byte(0x7F),
            "short"    to NbtValue.Short(12345),
            "int"      to NbtValue.Int(0x01020304),
            "long"     to NbtValue.Long(0x0102030405060708L),
            "float"    to NbtValue.Float(3.14f),
            "double"   to NbtValue.Double(2.7182818284590452),
            "bytes"    to NbtValue.ByteArray(byteArrayOf(1, 2, 3, -1, -128)),
            "str"      to NbtValue.String("hello é 世界"),
            "ints"     to NbtValue.IntArray(intArrayOf(10, 20, 30)),
            "longs"    to NbtValue.LongArray(longArrayOf(100L, 200L)),
            "emptyList"   to NbtValue.List(Nbt.TYPE_END, emptyList()),
            "stringList"  to NbtValue.List(Nbt.TYPE_STRING, listOf(
                NbtValue.String("a"),
                NbtValue.String("b"),
            )),
            "nested" to NbtValue.Compound(NbtCompound(linkedMapOf(
                "x" to NbtValue.Int(42),
                "y" to NbtValue.Double(1.5),
            ))),
        ))
        val root = RootCompound(name = "ROOT", value = payload)

        val bytes = ByteArrayOutputStream().also { Nbt.write(it, root, gzipped = false) }.toByteArray()
        val readBack = Nbt.read(ByteArrayInputStream(bytes), gzipped = false)

        assertEquals("ROOT", readBack.name)
        assertEquals(0x7F.toByte(), readBack.value.byte("byte"))
        assertEquals(12345, (readBack.value["short"] as NbtValue.Short).value.toInt())
        assertEquals(0x01020304, readBack.value.int("int"))
        assertEquals(0x0102030405060708L, readBack.value.long("long"))
        assertEquals(3.14f, (readBack.value["float"] as NbtValue.Float).value)
        assertEquals(2.7182818284590452, (readBack.value["double"] as NbtValue.Double).value)
        assertTrue(byteArrayOf(1, 2, 3, -1, -128).contentEquals(readBack.value.byteArray("bytes")))
        assertEquals("hello é 世界", readBack.value.string("str"))
        assertEquals(2, (readBack.value["stringList"] as NbtValue.List).items.size)
        assertEquals(0, (readBack.value["emptyList"] as NbtValue.List).items.size)
        assertEquals(42, readBack.value.compound("nested")!!.int("x"))
    }

    @Test
    fun `gzip round-trip works -- the level dat path`() {
        val root = RootCompound(name = "", value = NbtCompound(linkedMapOf(
            "Data" to NbtValue.Compound(NbtCompound(linkedMapOf(
                "LevelName" to NbtValue.String("My World"),
                "LastPlayed" to NbtValue.Long(1716639082000L),
                "RandomSeed" to NbtValue.Long(-42L),
            ))),
        )))
        val bytes = ByteArrayOutputStream().also { Nbt.write(it, root, gzipped = true) }.toByteArray()
        // GZIP magic bytes
        assertEquals(0x1F.toByte(), bytes[0])
        assertEquals(0x8B.toByte(), bytes[1])

        val readBack = Nbt.read(ByteArrayInputStream(bytes), gzipped = true)
        val data = readBack.value.compound("Data")!!
        assertEquals("My World", data.string("LevelName"))
        assertEquals(1716639082000L, data.long("LastPlayed"))
        assertEquals(-42L, data.long("RandomSeed"))
    }

    @Test
    fun `non-compound root throws`() {
        // Bytes: type=8 (string), name length=0, payload "x"
        val bytes = byteArrayOf(
            Nbt.TYPE_STRING.toByte(),
            0, 0,
            0, 1, 'x'.code.toByte(),
        )
        assertFailsWith<NbtException> { Nbt.read(ByteArrayInputStream(bytes), gzipped = false) }
    }

    @Test
    fun `unknown tag type throws`() {
        // Root compound start, name="", inner entry with bogus type 99
        // and a valid empty name -- so the reader walks past name-reading
        // and reaches readPayload, where the unknown-type branch fires.
        val bytes = byteArrayOf(
            Nbt.TYPE_COMPOUND.toByte(),
            0, 0,
            99,
            0, 0,
        )
        assertFailsWith<NbtException> { Nbt.read(ByteArrayInputStream(bytes), gzipped = false) }
    }

    @Test
    fun `truncated stream throws on missing TAG_End`() {
        // Compound start, name="", first entry is byte=1, then stream cuts off (no TAG_End).
        val bytes = byteArrayOf(
            Nbt.TYPE_COMPOUND.toByte(),
            0, 0,
            Nbt.TYPE_BYTE.toByte(),
            0, 1, 'x'.code.toByte(),
            5,
        )
        // The 5 above is a partial type byte that the reader interprets as a tag type 5 (float),
        // then tries to read the name length (2 bytes) and runs out of stream. EOFException
        // gets caught and converted into NbtException by readCompoundPayload's loop.
        assertFailsWith<Exception> { Nbt.read(ByteArrayInputStream(bytes), gzipped = false) }
    }

    @Test
    fun `compound preserves insertion order across round-trip`() {
        val keys = listOf("z", "a", "m", "B", "1")
        val payload = NbtCompound(linkedMapOf(*keys.map { it to NbtValue.Int(it.hashCode()) }.toTypedArray()))
        val root = RootCompound(name = "", value = payload)

        val bytes = ByteArrayOutputStream().also { Nbt.write(it, root, gzipped = false) }.toByteArray()
        val readBack = Nbt.read(ByteArrayInputStream(bytes), gzipped = false)

        assertEquals(keys, readBack.value.entries.keys.toList())
    }

    @Test
    fun `corrupt byte-array length is rejected, not OOM`() {
        // Entry declares an Int.MAX_VALUE-long byte array but provides no payload.
        // The reader must treat this as malformed (NbtException, which the world /
        // servers scanners catch), not pre-allocate ByteArray(len) and throw
        // OutOfMemoryError straight past the catch (Exception) guard.
        val bytes = byteArrayOf(
            Nbt.TYPE_COMPOUND.toByte(),
            0, 0,                                                // root name ""
            Nbt.TYPE_BYTE_ARRAY.toByte(),
            0, 0,                                                // entry name ""
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),  // length = Int.MAX_VALUE
        )
        assertFailsWith<NbtException> { Nbt.read(ByteArrayInputStream(bytes), gzipped = false) }
    }

    @Test
    fun `corrupt int-array length is rejected, not OOM`() {
        // Same shape for TAG_Int_Array: a bogus length must EOF per-element into a
        // catchable Exception, never IntArray(len) -> OutOfMemoryError.
        val bytes = byteArrayOf(
            Nbt.TYPE_COMPOUND.toByte(),
            0, 0,
            Nbt.TYPE_INT_ARRAY.toByte(),
            0, 0,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),  // length = Int.MAX_VALUE
        )
        assertFailsWith<Exception> { Nbt.read(ByteArrayInputStream(bytes), gzipped = false) }
    }
}
