package local.readapp.txt
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
class TxtChaptersTest {
    @Test fun mixedLinesAndLongParagraphsDoNotDropText(){
        val dir=Files.createTempDirectory("txt-toc").toFile()
        try {
            val text="前言\r\n"+"无章节的正文".repeat(10000)+"\r第二章 山河\nChapter 3 Spring\n尾声"
            val source=File(dir,"a.txt").apply { writeText(text) }
            val book=IndexedTxtEngine().prepare(source,File(dir,"index"))
            assertEquals(listOf("前言","第二章 山河","Chapter 3 Spring","尾声"),book.toc.map { it.title })
            for(entry in book.toc)assertTrue(text.substring(entry.locator.offset.toInt()).startsWith(entry.title))
            assertEquals(text,(0 until book.blockCount).joinToString(""){book.readBlock(it).text})
        }finally{dir.deleteRecursively()}
    }
}
