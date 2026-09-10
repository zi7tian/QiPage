package local.readapp.txt

import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** P0 disk-backed decoded text. Locators are UTF-16 code units, not layout pixels. */
class TxtDocument(val file: File, val charCount: Long, val encoding: String) {
    data class Window(val start: Long, val text: String) { val next: Long get() = start + text.length }

    fun readWindow(offset: Long, length: Int = 4096): Window {
        require(offset in 0..charCount && length in 1..65536)
        if (offset == charCount) return Window(offset, "")
        RandomAccessFile(file, "r").use { input ->
            var start = offset
            fun charAt(index: Long): Char { input.seek(index * 2); return (input.readUnsignedByte() or (input.readUnsignedByte() shl 8)).toChar() }
            if (start > 0 && charAt(start).isLowSurrogate()) start--
            var end = minOf(start + length, charCount)
            if (end < charCount && charAt(end - 1).isHighSurrogate()) end++
            val bytes = ByteArray(((end - start) * 2).toInt())
            input.seek(start * 2); input.readFully(bytes)
            return Window(start, String(bytes, Charsets.UTF_16LE))
        }
    }

    companion object {
        fun prepare(source: File, output: File, charset: Charset = detect(source)): TxtDocument {
            output.parentFile.mkdirs()
            val temp = File.createTempFile("txt-", ".part", output.parentFile)
            var count = 0L
            try {
                source.inputStream().buffered().use { stream ->
                    val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    InputStreamReader(stream, decoder).use { reader ->
                        OutputStreamWriter(temp.outputStream().buffered(), Charsets.UTF_16LE).use { writer ->
                            val buffer = CharArray(8192)
                            var first = true
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
                                val n = reader.read(buffer)
                                if (n < 0) break
                                val from = if (first && n > 0 && buffer[0] == '\uFEFF') 1 else 0
                                first = false
                                writer.write(buffer, from, n - from); count += n - from
                            }
                        }
                    }
                }
                Files.move(temp.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return TxtDocument(output, count, charset.name())
            } finally { temp.delete() }
        }

        fun detect(source: File): Charset {
            val head = source.inputStream().use { input -> ByteArray(3).also { input.read(it) } }
            if (head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
            // Strict streaming validation avoids misdetecting a multi-byte sequence at a sample boundary.
            return try {
                InputStreamReader(source.inputStream(), Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)).use { reader ->
                    val buffer = CharArray(8192)
                    while (reader.read(buffer) >= 0) { if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled") }
                }
                Charsets.UTF_8
            } catch (e: java.nio.charset.CharacterCodingException) { Charset.forName("GB18030") }
        }
    }
}
