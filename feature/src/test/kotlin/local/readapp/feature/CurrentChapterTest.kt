package local.readapp.feature

import local.readapp.core.*
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Opening the directory has to land on the chapter being read, otherwise a
 * thousand-chapter book always starts the list back at chapter one.
 */
class CurrentChapterTest {

    private fun book(position: Long = 0, locator: Locator = Locator()) =
        Book(
            id = "b", title = "书", encoding = "UTF-8", chars = 10_000, bytes = 10_000,
            position = position, lastRead = 0, importedAt = 0, localCopy = false,
            locator = locator,
        )

    private class NoEpub : EpubContent {
        override val title = ""
        override val author = ""
        override val chapters = emptyList<EpubSpine>()
        override val toc = emptyList<Chapter>()
        override fun chapter(path: String) = ""
        override fun image(path: String): Pair<String, ByteArray>? = null
        override fun close() {}
    }

    private class NoText(private val blocks: List<String>) : TextContent {
        override val encoding = "UTF-8"
        override val length = blocks.sumOf { it.length }.toLong()
        override val blockCount get() = blocks.size
        override fun blockStart(index: Int) = blocks.take(index).sumOf { it.length }.toLong()
        override fun findBlock(position: Long) = 0
        override fun readBlock(index: Int) = TextBlock(blockStart(index), blocks[index])
    }

    private val txtToc = listOf(
        Chapter("第一章", Locator(format = "txt", offset = 0)),
        Chapter("第二章", Locator(format = "txt", offset = 1_000)),
        Chapter("第三章", Locator(format = "txt", offset = 5_000)),
    )

    @Test
    fun txtPicksTheChapterContainingTheReadingPosition() {
        fun at(position: Long) =
            currentChapterIndex(OpenBook(book(position = position), content = NoText(listOf("x"))), txtToc)

        assertEquals(0, at(0))
        assertEquals(0, at(999))
        assertEquals(1, at(1_000))
        assertEquals(1, at(4_999))
        assertEquals(2, at(5_000))
        assertEquals(2, at(9_999))
    }

    @Test
    fun epubPrefersTheHeadingAtOrBeforeTheCurrentOffset() {
        val toc = listOf(
            Chapter("甲", Locator(format = "epub", href = "a.xhtml", offset = 0)),
            Chapter("乙", Locator(format = "epub", href = "a.xhtml", offset = 500)),
            Chapter("丙", Locator(format = "epub", href = "b.xhtml", offset = 0)),
        )
        fun at(href: String, offset: Long) = currentChapterIndex(
            OpenBook(book(locator = Locator(format = "epub", href = href, offset = offset)), epub = NoEpub()),
            toc,
        )

        assertEquals("start of the shared file", 0, at("a.xhtml", 0))
        assertEquals("past the second heading", 1, at("a.xhtml", 700))
        assertEquals("other spine item", 2, at("b.xhtml", 0))
    }

    @Test
    fun anEmptyTableOfContentsReportsNoChapter() {
        assertEquals(-1, currentChapterIndex(OpenBook(book(), content = NoText(listOf("x"))), emptyList()))
    }
}
