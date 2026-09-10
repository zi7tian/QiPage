package local.readapp.epub

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

class UserSampleTest {
    @Test fun parsesEveryChapterOfLocalSampleWithoutExportingText(){
        val samples=File("../novel_test").listFiles { f->f.extension.equals("epub",true) }.orEmpty()
        assumeTrue("User sample is local and is not distributed",samples.isNotEmpty())
        samples.forEach { sample -> SafeEpubEngine().open(sample).use { book ->
            assertTrue(book.chapters.isNotEmpty());assertTrue(book.toc.isNotEmpty())
            book.chapters.forEach { chapter ->
                val html=book.chapter(chapter.path)
                assertTrue("Chapter must have sanitized content",html.isNotBlank())
                assertFalse(html.contains("<script",true))
            }
        }}
    }
}

