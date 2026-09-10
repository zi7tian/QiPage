package local.readapp.txt

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import kotlin.system.measureNanoTime

class TxtDocumentTest {
    private fun withDirectory(action: (File) -> Unit) {
        val dir = Files.createTempDirectory("readapp-txt-test").toFile()
        try { action(dir) } finally { dir.deleteRecursively() }
    }
    @Test fun encodingsAndSurrogateBoundaries() = withDirectory { dir ->
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE, Charset.forName("GB18030"))) {
            val expected = "第一章\r\n" + "字".repeat(4089) + "😀𠀀\n末尾"
            val source = File(dir, "source")
            val prefix = if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) "\uFEFF" else ""
            source.writeBytes((prefix + expected).toByteArray(charset))
            val doc = TxtDocument.prepare(source, File(dir, "cache"))
            val result = StringBuilder(); var offset = 0L
            while (offset < doc.charCount) { val w = doc.readWindow(offset, 7); result.append(w.text); offset = w.next }
            assertEquals(expected, result.toString())
            val emoji = expected.indexOf("😀").toLong()
            assertEquals(emoji, doc.readWindow(emoji + 1, 1).start)
        }
    }
    @Test fun emptyBomAndMalformedInput() = withDirectory { dir ->
        val source = File(dir, "source"); source.writeText("")
        assertEquals("", TxtDocument.prepare(source, File(dir, "cache")).readWindow(0).text)
        source.writeText("\uFEFF正文")
        assertEquals("正文", TxtDocument.prepare(source, File(dir, "cache")).readWindow(0).text)
        source.writeBytes(byteArrayOf(0xFF.toByte()))
        val target = File(dir, "existing"); target.writeText("preserve")
        try { TxtDocument.prepare(source, target, Charsets.UTF_8); fail("Malformed UTF-8 accepted") } catch (_: java.nio.charset.CharacterCodingException) {}
        assertEquals("preserve", target.readText())
        assertFalse(dir.listFiles()!!.any { it.extension == "part" })
    }
    @Test fun hundredMiBUnder64MiBHeap() = withDirectory { dir ->
        val source = File(dir, "large.txt")
        val block = ("第一章 本地阅读\r\n" + "山风吹过书页。😀".repeat(1000)).toByteArray()
        source.outputStream().buffered().use { output ->
            var size = 0L
            while (size < 100L * 1024 * 1024) { output.write(block); size += block.size }
        }
        lateinit var doc: TxtDocument
        val elapsed = measureNanoTime { doc = TxtDocument.prepare(source, File(dir, "cache")) } / 1e6
        val reads = (1..100).map { n -> measureNanoTime {
            val w = doc.readWindow((doc.charCount - 5000) * n / 100)
            assertTrue(w.text.isNotEmpty()); assertFalse(w.text.first().isLowSurrogate()); assertFalse(w.text.last().isHighSurrogate())
        } / 1e6 }.sorted()
        println("TXT_BENCH bytes=${source.length()} cacheBytes=${doc.file.length()} prepareMs=$elapsed randomReadP95Ms=${reads[94]} maxHeap=${Runtime.getRuntime().maxMemory()}")
        assertTrue(source.length() >= 100L * 1024 * 1024)
    }
}
