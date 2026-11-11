package hivens.ui.util

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/** Whole-file UTF-8 read that rejects malformed input, as `Files.readString` does and Android's `java.nio.file.Files` does not offer. */
internal fun readUtf8(file: Path): String =
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString()
