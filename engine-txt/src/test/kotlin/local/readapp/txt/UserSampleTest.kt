package local.readapp.txt
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
class UserSampleTest {
    @Test fun userNovelRoundTripsAndChaptersPointAtTitles(){
        val source=File("../novel_test").listFiles()?.firstOrNull {it.extension=="txt"}
        assumeTrue(source!=null)
        val dir=Files.createTempDirectory("p4-user-txt").toFile()
        try {
            val book=IndexedTxtEngine().prepare(source!!,dir)
            val text=(0 until book.blockCount).joinToString(""){book.readBlock(it).text}
            assertTrue(text.length>1_000_000)
            assertTrue(book.toc.size>100)
            assertEquals(book.length,text.length.toLong())
            book.toc.forEach {assertTrue(text.substring(it.locator.offset.toInt()).trimStart().startsWith(it.title))}
        }finally{dir.deleteRecursively()}
    }
}

