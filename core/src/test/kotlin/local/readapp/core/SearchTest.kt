package local.readapp.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full-text search over the TXT and EPUB content interfaces. */
class SearchTest {

    // -----------------------------------------------------------------------
    // Fakes
    // -----------------------------------------------------------------------

    private class FakeText(
        private val blocks: List<String>,
        private val offsets: List<Long>,
        override val length: Long,
    ) : TextContent {
        override val encoding = "UTF-8"
        override val blockCount get() = blocks.size
        override fun blockStart(index: Int) = offsets[index]
        override fun findBlock(position: Long) =
            offsets.indexOfLast { it <= position }.coerceAtLeast(0)
        override fun readBlock(index: Int) = TextBlock(offsets[index], blocks[index])
    }

    /** Split [source] into fixed-size blocks so boundary handling can be tested. */
    private fun textOf(source: String, chunk: Int): FakeText {
        val blocks = source.chunked(chunk)
        var running = 0L
        val offsets = blocks.map { val start = running; running += it.length; start }
        return FakeText(blocks, offsets, source.length.toLong())
    }

    private class FakeEpub(
        private val items: Map<String, String>,
        override val toc: List<Chapter>,
    ) : EpubContent {
        override val title = "测试书"
        override val author = "作者"
        override val chapters = items.keys.map { EpubSpine(it, it) }
        override fun chapter(path: String) = items.getValue(path)
        override fun image(path: String): Pair<String, ByteArray>? = null
        override fun close() {}
    }

    /** Mirror the sanitizer's output shape: one `data-read` span per text run. */
    private fun sanitized(vararg paragraphs: String): String {
        var offset = 0
        return paragraphs.joinToString("") { text ->
            val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val html = "<p><span data-read=\"$offset\">$escaped</span></p>"
            offset += text.length
            html
        }
    }

    // -----------------------------------------------------------------------
    // TXT
    // -----------------------------------------------------------------------

    @Test
    fun textSearchReportsTheAbsoluteOffsetOfAMatch() = runBlocking {
        val source = "第一章 山间书页\n风从窗外吹进来，书页轻轻翻动。"
        val hits = searchTextContent(textOf(source, chunk = 5), "书页轻轻")

        assertEquals(1, hits.size)
        assertEquals("txt", hits[0].locator.format)
        assertEquals(source.indexOf("书页轻轻").toLong(), hits[0].locator.offset)
    }

    @Test
    fun textSearchIgnoresCase() = runBlocking {
        val hits = searchTextContent(textOf("Hello World", chunk = 4), "hello world")
        assertEquals(1, hits.size)
        assertEquals(0L, hits[0].locator.offset)
    }

    @Test
    fun textSearchFindsAPhraseThatSpansABlockBoundary() = runBlocking {
        // "abcdefghij" cut into 4-char blocks; "defg" straddles blocks 0 and 1.
        val hits = searchTextContent(textOf("abcdefghij", chunk = 4), "defg")
        assertEquals("a boundary-spanning phrase must still be found", 1, hits.size)
        assertEquals(3L, hits[0].locator.offset)
    }

    @Test
    fun textSearchDoesNotReportTheSameMatchTwice() = runBlocking {
        val hits = searchTextContent(textOf("abcdefghij", chunk = 4), "defg")
        assertEquals(1, hits.map { it.locator.offset }.distinct().size)
    }

    @Test
    fun textSearchFindsEveryOccurrenceInOrder() = runBlocking {
        val source = "风来了。风停了。风又起了。"
        val hits = searchTextContent(textOf(source, chunk = 3), "风")
        assertEquals(listOf(0L, 4L, 8L), hits.map { it.locator.offset })
    }

    @Test
    fun textSearchHonoursTheLimit() = runBlocking {
        val hits = searchTextContent(textOf("风".repeat(50), chunk = 4), "风", limit = 5)
        assertEquals(5, hits.size)
    }

    @Test
    fun textSearchReportsProgressAndCompletion() = runBlocking {
        val events = mutableListOf<SearchProgress>()
        searchTextContent(textOf("abcdefghij", chunk = 4), "defg") { events.add(it) }

        assertTrue("progress must be reported", events.isNotEmpty())
        assertTrue("final event must be marked done", events.last().done)
        assertEquals(events.last().total, events.last().scanned)
    }

    @Test
    fun blankQueryFindsNothing() = runBlocking {
        assertTrue(searchTextContent(textOf("正文正文", chunk = 2), "   ").isEmpty())
        assertTrue(searchTextContent(textOf("正文正文", chunk = 2), "").isEmpty())
    }

    @Test
    fun textSearchReturnsEmptyWhenThereIsNoMatch() = runBlocking {
        assertTrue(searchTextContent(textOf("山间的风", chunk = 2), "大海").isEmpty())
    }

    @Test
    fun textSnippetsIncludeSurroundingContextAndClipIt() = runBlocking {
        // One block, so the snippet window covers the whole scanned text.
        val source = "前".repeat(60) + "目标词语" + "后".repeat(60)
        val hits = searchTextContent(textOf(source, chunk = 200), "目标词语")

        assertEquals(1, hits.size)
        assertTrue("snippet must contain the match", hits[0].snippet.contains("目标词语"))
        assertTrue("snippet must keep leading context", hits[0].snippet.contains("前"))
        assertTrue("snippet must keep trailing context", hits[0].snippet.contains("后"))
        assertTrue("long context must be clipped", hits[0].snippet.startsWith("…"))
        assertTrue("long context must be clipped", hits[0].snippet.endsWith("…"))
    }

    // -----------------------------------------------------------------------
    // EPUB
    // -----------------------------------------------------------------------

    private fun epubWithTwoChapters() = FakeEpub(
        items = linkedMapOf(
            "ch1.xhtml" to sanitized("第一章 寻仙", "两个月后，秋高气爽。"),
            "ch2.xhtml" to sanitized("第二章 雨停", "雨停之后，他把书放回原处。"),
        ),
        toc = listOf(
            Chapter("第一章 寻仙", Locator(format = "epub", href = "ch1.xhtml")),
            Chapter("第二章 雨停", Locator(format = "epub", href = "ch2.xhtml")),
        ),
    )

    @Test
    fun epubSearchMapsAHitToItsChapterAndOffset() = runBlocking {
        val hits = searchEpubContent(epubWithTwoChapters(), "雨停之后")

        assertEquals(1, hits.size)
        assertEquals("epub", hits[0].locator.format)
        assertEquals("ch2.xhtml", hits[0].locator.href)
        assertEquals("第二章 雨停", hits[0].chapter)
        // "第二章 雨停" is 6 chars, so the second run starts at offset 6.
        assertEquals(6L, hits[0].locator.offset)
    }

    @Test
    fun epubSearchOffsetPointsInsideTheMatchedRun() = runBlocking {
        val epub = FakeEpub(
            items = linkedMapOf("ch1.xhtml" to sanitized("开头文字", "abcdefghij")),
            toc = listOf(Chapter("章", Locator(format = "epub", href = "ch1.xhtml"))),
        )
        val hits = searchEpubContent(epub, "defg")

        assertEquals(1, hits.size)
        // "开头文字" is 4 chars, then "defg" starts 3 chars into the second run.
        assertEquals(4L + 3L, hits[0].locator.offset)
    }

    @Test
    fun epubSearchIgnoresCase() = runBlocking {
        val epub = FakeEpub(
            items = linkedMapOf("ch1.xhtml" to sanitized("Chapter One")),
            toc = listOf(Chapter("Chapter One", Locator(format = "epub", href = "ch1.xhtml"))),
        )
        assertEquals(1, searchEpubContent(epub, "chapter one").size)
    }

    @Test
    fun epubSearchUnescapesEntitiesBeforeMatching() = runBlocking {
        val epub = FakeEpub(
            items = linkedMapOf("ch1.xhtml" to sanitized("A & B")),
            toc = listOf(Chapter("章", Locator(format = "epub", href = "ch1.xhtml"))),
        )
        assertEquals("the &amp; entity must be searchable as &", 1, searchEpubContent(epub, "A & B").size)
        assertEquals(0L, searchEpubContent(epub, "A & B")[0].locator.offset)
    }

    @Test
    fun epubSearchFindsMatchesInEveryChapter() = runBlocking {
        val hits = searchEpubContent(epubWithTwoChapters(), "第")
        assertEquals(2, hits.size)
        assertEquals(setOf("ch1.xhtml", "ch2.xhtml"), hits.map { it.locator.href }.toSet())
    }

    @Test
    fun epubSearchReportsProgressPerSpineItem() = runBlocking {
        val events = mutableListOf<SearchProgress>()
        searchEpubContent(epubWithTwoChapters(), "雨停") { events.add(it) }

        assertTrue(events.isNotEmpty())
        assertEquals(2, events.last().total)
        assertEquals(2, events.last().scanned)
        assertTrue(events.last().done)
    }

    @Test
    fun epubSearchHonoursTheLimit() = runBlocking {
        val epub = FakeEpub(
            items = linkedMapOf("ch1.xhtml" to sanitized("风".repeat(20))),
            toc = listOf(Chapter("章", Locator(format = "epub", href = "ch1.xhtml"))),
        )
        assertEquals(3, searchEpubContent(epub, "风", limit = 3).size)
    }

    @Test
    fun epubSearchReturnsEmptyForABlankQuery() = runBlocking {
        assertTrue(searchEpubContent(epubWithTwoChapters(), "  ").isEmpty())
    }

    @Test
    fun epubSearchSurvivesAChapterThatFailsToParse() = runBlocking {
        val epub = object : EpubContent {
            override val title = "坏书"
            override val author = ""
            override val chapters = listOf(EpubSpine("bad.xhtml", "坏"), EpubSpine("good.xhtml", "好"))
            override val toc = listOf(Chapter("好", Locator(format = "epub", href = "good.xhtml")))
            override fun chapter(path: String) =
                if (path == "bad.xhtml") error("这一节解析失败") else sanitized("可以搜索到的正文")
            override fun image(path: String): Pair<String, ByteArray>? = null
            override fun close() {}
        }
        val hits = searchEpubContent(epub, "可以搜索")
        assertEquals("a broken chapter must not abort the whole search", 1, hits.size)
        assertEquals("good.xhtml", hits[0].locator.href)
    }

    @Test
    fun snippetsCollapseWhitespace() {
        val snippet = snippetAround("第一行\n\n第二行   目标   第三行", start = 10, length = 2)
        assertFalse("newlines must be collapsed for display", snippet.contains('\n'))
    }
}
