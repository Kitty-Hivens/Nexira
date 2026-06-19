package hivens.launcher.nbt

import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Minimal in-house NBT reader / writer.
 *
 * Scoped to what the launcher actually needs from Minecraft data
 * files: `level.dat` (GZIP-compressed compound) and `servers.dat`
 * (uncompressed compound). Covers every tag type in the spec for
 * round-trip safety, but the reader's public API is intentionally
 * small -- callers walk a [NbtCompound] tree directly rather than
 * registering visitors or building POJO bindings.
 *
 * Why not Querz: Querz NBT is the standard reference impl, but it's
 * JitPack-only and the surface we need is < 300 LOC of binary IO.
 * The cost of taking on an external dep (build-time fetch, version
 * pinning, potential security patches we'd need to track) exceeds
 * the cost of maintaining this. See [[feedback_custom_tooling_on_aura]]
 * for the project's bias here.
 *
 * Spec reference: https://minecraft.wiki/w/NBT_format
 */
object Nbt {

    /**
     * Reads a root compound from [stream]. The MC convention is that
     * the outermost tag is always a compound with a (usually empty)
     * name; callers receive just the compound payload and the root
     * name as a pair.
     *
     * If [gzipped] is true (level.dat), the stream is wrapped in
     * [GZIPInputStream] first. servers.dat passes false.
     */
    fun read(stream: InputStream, gzipped: Boolean): RootCompound {
        val input = DataInputStream(if (gzipped) GZIPInputStream(stream) else stream)
        val typeId = input.readByte().toInt()
        if (typeId != TYPE_COMPOUND) {
            throw NbtException("Root tag is not a compound (got typeId=$typeId)")
        }
        val rootName = input.readUtf()
        val payload = input.readCompoundPayload()
        return RootCompound(name = rootName, value = payload)
    }

    /**
     * Writes a root compound to [stream]. Mirrors [read]: caller passes
     * the compound payload and the root name; the writer emits the
     * compound type byte, the name, the payload, and the terminating
     * end-tag. If [gzipped] is true, output is wrapped in
     * [GZIPOutputStream].
     */
    fun write(stream: OutputStream, root: RootCompound, gzipped: Boolean) {
        val output = DataOutputStream(if (gzipped) GZIPOutputStream(stream) else stream)
        output.writeByte(TYPE_COMPOUND)
        output.writeUtf(root.name)
        output.writeCompoundPayload(root.value)
        output.flush()
        // Close GZIP wrapper so the trailer (CRC + ISIZE) is flushed.
        if (gzipped) output.close()
    }

    // ── Internal: reader ────────────────────────────────────────────────────

    // NBT strings are Java modified UTF-8 (2-byte length, CESU-8 surrogate pairs
    // for supplementary code points, 0xC0 0x80 for NUL) -- exactly the wire format
    // DataInput.readUTF/writeUTF implement. Plain String(bytes, UTF_8) mis-decodes
    // both edge cases, so a world/server name with an emoji or NUL would corrupt.
    private fun DataInputStream.readUtf(): String = readUTF()

    private fun DataInputStream.readCompoundPayload(): NbtCompound {
        val entries = linkedMapOf<String, NbtValue>()
        while (true) {
            val typeId = try {
                readByte().toInt()
            } catch (_: EOFException) {
                throw NbtException("Unexpected EOF before TAG_End")
            }
            if (typeId == TYPE_END) return NbtCompound(entries)
            val name = readUtf()
            entries[name] = readPayload(typeId)
        }
    }

    private fun DataInputStream.readPayload(typeId: Int): NbtValue = when (typeId) {
        TYPE_BYTE       -> NbtValue.Byte(readByte())
        TYPE_SHORT      -> NbtValue.Short(readShort())
        TYPE_INT        -> NbtValue.Int(readInt())
        TYPE_LONG       -> NbtValue.Long(readLong())
        TYPE_FLOAT      -> NbtValue.Float(readFloat())
        TYPE_DOUBLE     -> NbtValue.Double(readDouble())
        TYPE_BYTE_ARRAY -> {
            val len = readInt()
            if (len < 0) throw NbtException("Negative byte-array length=$len")
            // readNBytes reads in bounded chunks, so a corrupt/oversized length
            // can't pre-allocate gigabytes: a short stream yields fewer bytes,
            // which we reject rather than letting ByteArray(len) OOM.
            val bytes = readNBytes(len)
            if (bytes.size != len) throw NbtException("Truncated byte array: expected $len, got ${bytes.size}")
            NbtValue.ByteArray(bytes)
        }
        TYPE_STRING     -> NbtValue.String(readUtf())
        TYPE_LIST       -> {
            val elementType = readByte().toInt()
            val len = readInt()
            if (len < 0) throw NbtException("Negative list length=$len")
            // No len-sized pre-alloc: a bogus length must not reserve memory up
            // front; each element below reads from the stream and EOFs cleanly.
            val items = ArrayList<NbtValue>()
            // List<TAG_End> with len > 0 is malformed; spec says
            // TAG_End is only valid as an empty-list element marker.
            if (elementType == TYPE_END && len > 0) {
                throw NbtException("Invalid TAG_List of TAG_End with length=$len")
            }
            repeat(len) { items += readPayload(elementType) }
            NbtValue.List(elementType, items)
        }
        TYPE_COMPOUND   -> NbtValue.Compound(readCompoundPayload())
        TYPE_INT_ARRAY  -> {
            val len = readInt()
            if (len < 0) throw NbtException("Negative int-array length=$len")
            // Grow as elements arrive instead of IntArray(len): a corrupt length
            // EOFs mid-read (caught upstream) rather than pre-allocating the array.
            val ints = ArrayList<Int>()
            repeat(len) { ints += readInt() }
            NbtValue.IntArray(ints.toIntArray())
        }
        TYPE_LONG_ARRAY -> {
            val len = readInt()
            if (len < 0) throw NbtException("Negative long-array length=$len")
            val longs = ArrayList<Long>()
            repeat(len) { longs += readLong() }
            NbtValue.LongArray(longs.toLongArray())
        }
        else -> throw NbtException("Unknown NBT tag typeId=$typeId")
    }

    // ── Internal: writer ────────────────────────────────────────────────────

    private fun DataOutputStream.writeUtf(s: String) = writeUTF(s)

    private fun DataOutputStream.writeCompoundPayload(c: NbtCompound) {
        for ((name, value) in c.entries) {
            writeByte(value.typeId)
            writeUtf(name)
            writePayload(value)
        }
        writeByte(TYPE_END)
    }

    private fun DataOutputStream.writePayload(v: NbtValue): Unit = when (v) {
        is NbtValue.Byte      -> writeByte(v.value.toInt())
        is NbtValue.Short     -> writeShort(v.value.toInt())
        is NbtValue.Int       -> writeInt(v.value)
        is NbtValue.Long      -> writeLong(v.value)
        is NbtValue.Float     -> writeFloat(v.value)
        is NbtValue.Double    -> writeDouble(v.value)
        is NbtValue.ByteArray -> { writeInt(v.value.size); write(v.value) }
        is NbtValue.String    -> writeUtf(v.value)
        is NbtValue.List      -> {
            writeByte(v.elementType)
            writeInt(v.items.size)
            v.items.forEach { writePayload(it) }
        }
        is NbtValue.Compound  -> writeCompoundPayload(v.value)
        is NbtValue.IntArray  -> { writeInt(v.value.size); v.value.forEach { writeInt(it) } }
        is NbtValue.LongArray -> { writeInt(v.value.size); v.value.forEach { writeLong(it) } }
    }

    // ── Tag type IDs ────────────────────────────────────────────────────────

    const val TYPE_END        = 0
    const val TYPE_BYTE       = 1
    const val TYPE_SHORT      = 2
    const val TYPE_INT        = 3
    const val TYPE_LONG       = 4
    const val TYPE_FLOAT      = 5
    const val TYPE_DOUBLE     = 6
    const val TYPE_BYTE_ARRAY = 7
    const val TYPE_STRING     = 8
    const val TYPE_LIST       = 9
    const val TYPE_COMPOUND   = 10
    const val TYPE_INT_ARRAY  = 11
    const val TYPE_LONG_ARRAY = 12
}

/** Root compound = name + value. MC always wraps real data in a named outer compound. */
data class RootCompound(val name: String, val value: NbtCompound)

/**
 * Ordered name-to-value map. Order matters when round-tripping --
 * MC sometimes relies on field order even though the spec doesn't
 * mandate it, and tests are easier when the writer preserves the
 * order it observed.
 */
data class NbtCompound(val entries: Map<String, NbtValue>) {

    operator fun get(name: String): NbtValue? = entries[name]

    fun int(name: String): Int? = (entries[name] as? NbtValue.Int)?.value
    fun long(name: String): Long? = (entries[name] as? NbtValue.Long)?.value
    fun string(name: String): String? = (entries[name] as? NbtValue.String)?.value
    fun byte(name: String): Byte? = (entries[name] as? NbtValue.Byte)?.value
    fun compound(name: String): NbtCompound? = (entries[name] as? NbtValue.Compound)?.value
    fun list(name: String): NbtValue.List? = entries[name] as? NbtValue.List
    fun byteArray(name: String): ByteArray? = (entries[name] as? NbtValue.ByteArray)?.value
}

/**
 * Sealed hierarchy mirroring NBT tag types. Type IDs match
 * [Nbt.TYPE_BYTE] / [Nbt.TYPE_SHORT] / etc so the writer can
 * emit them without a separate lookup table.
 */
sealed class NbtValue {
    abstract val typeId: kotlin.Int

    data class Byte(val value: kotlin.Byte) : NbtValue()          { override val typeId = Nbt.TYPE_BYTE }
    data class Short(val value: kotlin.Short) : NbtValue()        { override val typeId = Nbt.TYPE_SHORT }
    data class Int(val value: kotlin.Int) : NbtValue()            { override val typeId = Nbt.TYPE_INT }
    data class Long(val value: kotlin.Long) : NbtValue()          { override val typeId = Nbt.TYPE_LONG }
    data class Float(val value: kotlin.Float) : NbtValue()        { override val typeId = Nbt.TYPE_FLOAT }
    data class Double(val value: kotlin.Double) : NbtValue()      { override val typeId = Nbt.TYPE_DOUBLE }
    data class ByteArray(val value: kotlin.ByteArray) : NbtValue() {
        override val typeId = Nbt.TYPE_BYTE_ARRAY
        override fun equals(other: Any?): Boolean =
            this === other || (other is ByteArray && value.contentEquals(other.value))
        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }
    data class String(val value: kotlin.String) : NbtValue()      { override val typeId = Nbt.TYPE_STRING }
    /** elementType is the NBT type-id of the items; needed for round-trip even when items is empty. */
    data class List(val elementType: kotlin.Int, val items: kotlin.collections.List<NbtValue>) : NbtValue() {
        override val typeId = Nbt.TYPE_LIST
    }
    data class Compound(val value: NbtCompound) : NbtValue()      { override val typeId = Nbt.TYPE_COMPOUND }
    data class IntArray(val value: kotlin.IntArray) : NbtValue() {
        override val typeId = Nbt.TYPE_INT_ARRAY
        override fun equals(other: Any?): Boolean =
            this === other || (other is IntArray && value.contentEquals(other.value))
        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }
    data class LongArray(val value: kotlin.LongArray) : NbtValue() {
        override val typeId = Nbt.TYPE_LONG_ARRAY
        override fun equals(other: Any?): Boolean =
            this === other || (other is LongArray && value.contentEquals(other.value))
        override fun hashCode(): kotlin.Int = value.contentHashCode()
    }
}

/** Thrown when the byte stream doesn't conform to the NBT spec. Always recoverable from the caller's perspective -- treat as "this file isn't NBT" and skip. */
class NbtException(message: String) : RuntimeException(message)
