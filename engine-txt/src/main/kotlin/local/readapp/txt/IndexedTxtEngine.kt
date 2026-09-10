package local.readapp.txt

import local.readapp.core.*
import java.io.*
import java.nio.charset.Charset

/** Versioned disk index. Every block begins at a character boundary; no paragraph is dropped. */
class IndexedTxtEngine : TextEngine {
    override fun prepare(source: File, directory: File, encoding: String?): TextContent {
        directory.mkdirs()
        File(directory,"chapters.bin").delete()
        val document = if (encoding == null) TxtDocument.prepare(source, File(directory, "text.bin"))
            else TxtDocument.prepare(source, File(directory, "text.bin"), Charset.forName(encoding))
        val offsets = ArrayList<Long>()
        var cursor = 0L
        while (cursor < document.charCount) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
            offsets.add(cursor)
            val window = document.readWindow(cursor, 2048)
            // Prefer a paragraph end, but never create unbounded or thousands of tiny rows.
            val newline = window.text.lastIndexOf('\n')
            cursor += if (newline >= 512 && window.next < document.charCount) newline + 1 else window.text.length
            require(offsets.size <= 1_000_000) { "文本段落数量超过支持范围" }
        }
        offsets.add(document.charCount)
        DataOutputStream(File(directory, "index.bin").outputStream().buffered()).use { out ->
            out.writeInt(0x54585431); out.writeLong(document.charCount); out.writeInt(offsets.size)
            offsets.forEach(out::writeLong)
        }
        File(directory, "encoding").writeText(document.encoding)
        return reopen(directory, document.encoding)
    }
    override fun reopen(directory: File, encoding: String): TextContent {
        val text = File(directory, "text.bin")
        return DataInputStream(File(directory, "index.bin").inputStream().buffered()).use { input ->
            require(input.readInt() == 0x54585431) { "需要重建文本索引" }
            val length = input.readLong(); val count = input.readInt()
            require(length >= 0 && text.length() == length * 2 && count in 1..1_000_001)
            val offsets = LongArray(count) { input.readLong() }
            require(offsets.first() == 0L && offsets.last() == length)
            for (i in 1 until count) require(offsets[i] > offsets[i - 1] && offsets[i] - offsets[i - 1] <= 2049)
            object : TextContent {
                override val length = length
                override val encoding = encoding
                override val blockCount = (count - 1).coerceAtLeast(1)
                override val toc:List<Chapter> by lazy {
                    val cache=File(directory,"chapters.bin")
                    runCatching { DataInputStream(cache.inputStream().buffered()).use { input ->
                        require(input.readInt()==1 && input.readLong()==length)
                        val size=input.readInt();require(size in 0..10000)
                        List(size){Chapter(input.readUTF(),Locator(offset=input.readLong()),rule=input.readUTF())}
                    } }.getOrElse {
                        TxtChapters.scan(this).also { chapters ->
                            val temporary=File(directory,"chapters.tmp")
                            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                                output.writeInt(1);output.writeLong(length);output.writeInt(chapters.size)
                                chapters.forEach { output.writeUTF(it.title);output.writeLong(it.locator.offset);output.writeUTF(it.rule) }
                            }
                            temporary.renameTo(cache)
                        }
                    }
                }
                override fun blockStart(index: Int) = offsets[index.coerceIn(0, offsets.lastIndex)]
                override fun findBlock(position: Long): Int {
                    val found = offsets.binarySearch(position.coerceIn(0, (length - 1).coerceAtLeast(0)))
                    return (if (found >= 0) found else -found - 2).coerceIn(0, blockCount - 1)
                }
                override fun readBlock(index: Int): TextBlock {
                    if (length == 0L) return TextBlock(0, "")
                    require(index in 0 until blockCount)
                    val bytes = ByteArray(((offsets[index + 1] - offsets[index]) * 2).toInt())
                    RandomAccessFile(text, "r").use { file -> file.seek(offsets[index] * 2); file.readFully(bytes) }
                    return TextBlock(offsets[index], String(bytes, Charsets.UTF_16LE))
                }
            }
        }
    }
}
