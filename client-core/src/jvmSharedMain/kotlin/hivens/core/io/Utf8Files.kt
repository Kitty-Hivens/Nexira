package hivens.core.io

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.file.Files
import java.nio.file.Path

/** Whole-file UTF-8 read that rejects malformed input, as `Files.readString` does and Android's `java.nio.file.Files` does not offer. */
fun readUtf8(file: Path): String =
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()

/** Whole-file UTF-8 write that rejects unmappable input, as `Files.writeString` does and Android's `java.nio.file.Files` does not offer. */
fun writeUtf8(file: Path, content: String) {
    val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(content))
    Files.write(file, ByteArray(encoded.remaining()).also { encoded.get(it) })
}
