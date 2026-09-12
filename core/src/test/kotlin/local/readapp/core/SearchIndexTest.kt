package local.readapp.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader builds one [BookSearchIndex] per book and reuses it, which is what
 * makes a phrase lookup on a full-length novel feel instant. These tests pin
 * both the correctness of that flattening and the latency budget.
 */
class SearchIndexTest {

    private class FakeText(
        private val blocks: List<String>,
        private val offsets: List<Long>,
        override val length: Long,
    ) : TextContent {
        override val encoding = "UTF-8"
        override val blockCount get() = blocks.size
        override fun blockStart(index: Int) = offsets[index]
        override fun findBlock(position: Long) = offsets.indexOfLast { it <= position }.coerceAtLeast(0)
        override fun readBlock(index: Int) = TextBlock(offsets[index], blocks[index])
    }

    /** A book of [chars] characters laid out in realistic 4 KiB read windows. */
    private fun book(chars: Int, blockSize: Int = 4096): FakeText {
        val unit = "山川河流与晨光映入书页，阅读留在本机。测试词语在这里出现。\n"
        val source = StringBuilder(chars + unit.length)
        while (source.length < chars) source.append(unit)
        val text = source.substring(0, chars)
        val blocks = text.chunked(blockSize)
        var running = 0L
        val offsets = blocks.map { val start = running; running += it.length; start }
        return FakeText(blocks, offsets, text.length.toLong())
    }

    @Test
    fun aTwoMillionCharacterBookIsSearchedWellUnderTheLatencyBudget() = runBlocking {
        val content = book(2_000_000)
        val index = BookSearchIndex.buildText(content)
        assertEquals("the whole book must be indexed", 2_000_000, index.characterCount)

        // A high limit forces a full scan instead of stopping at the first page of hits.
        index.find("测试词语", limit = 100_000) // warm up the JIT

        val started = System.nanoTime()
        val hits = index.find("测试词语", limit = 100_000)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        println("SEARCH-LATENCY 2000000 chars, ${hits.size} hits: ${elapsedMs}ms")
        assertTrue("expected matches, got none", hits.isNotEmpty())
        assertTrue("a full 2M-character scan took ${elapsedMs}ms, budget is 500ms", elapsedMs < 500)
    }

    @Test
    fun hitsCarryTheAbsoluteOffsetOfTheMatch() = runBlocking {
        val source = "第一章 山间书页\n风从窗外吹进来，书页轻轻翻动。"
        val text = source.chunked(5).let { blocks ->
            var running = 0L
            val offsets = blocks.map { val s = running; running += it.length; s }
            FakeText(blocks, offsets, source.length.toLong())
        }
        val hits = BookSearchIndex.buildText(text).find("书页轻轻")

        assertEquals(1, hits.size)
        assertEquals("txt", hits[0].locator.format)
        assertEquals(source.indexOf("书页轻轻").toLong(), hits[0].locator.offset)
        assertEquals(4, hits[0].length)
    }

    @Test
    fun aQueryCannotMatchAcrossASpineBoundary() = runBlocking {
        val epub = object : EpubContent {
            override val title = "两章"
            override val author = ""
            override val chapters = listOf(EpubSpine("a.xhtml", "甲"), EpubSpine("b.xhtml", "乙"))
            override val toc = listOf(
                Chapter("甲", Locator(format = "epub", href = "a.xhtml")),
                Chapter("乙", Locator(format = "epub", href = "b.xhtml")),
            )
            override fun chapter(path: String) = when (path) {
                "a.xhtml" -> "<p><span data-read=\"0\">结尾就这样</span></p>"
                else -> "<p><span data-read=\"0\">开头接下去</span></p>"
            }
            override fun image(path: String): Pair<String, ByteArray>? = null
            override fun close() {}
        }
        val index = BookSearchIndex.buildEpub(epub)

        assertTrue("甲 must be findable", index.find("结尾就这样").isNotEmpty())
        assertTrue("乙 must be findable", index.find("开头接下去").isNotEmpty())
        assertTrue(
            "a phrase must not run from the end of one chapter into the next",
            index.find("就这样开头").isEmpty()
        )
    }

    @Test
    fun epubHitsPointAtTheirOwnSpineItemAndOffset() = runBlocking {
        val epub = object : EpubContent {
            override val title = "两章"
            override val author = ""
            override val chapters = listOf(EpubSpine("a.xhtml", "甲"), EpubSpine("b.xhtml", "乙"))
            override val toc = listOf(
                Chapter("第一章 甲", Locator(format = "epub", href = "a.xhtml")),
                Chapter("第二章 乙", Locator(format = "epub", href = "b.xhtml")),
            )
            override fun chapter(path: String) = when (path) {
                "a.xhtml" -> "<p><span data-read=\"0\">甲章正文</span></p>"
                else -> "<p><span data-read=\"0\">乙章正文</span></p>"
            }
            override fun image(path: String): Pair<String, ByteArray>? = null
            override fun close() {}
        }
        val hits = BookSearchIndex.buildEpub(epub).find("正文")

        assertEquals(2, hits.size)
        assertEquals(listOf("a.xhtml", "b.xhtml"), hits.map { it.locator.href })
        assertEquals(listOf("第一章 甲", "第二章 乙"), hits.map { it.chapter })
        // "正文" sits two characters into the run that starts at offset 0, so the
        // hit must carry the offset of the match itself, not of its run.
        assertEquals(listOf(2L, 2L), hits.map { it.locator.offset })
    }

    @Test
    fun buildingReportsProgressAndCompletion() = runBlocking {
        val events = mutableListOf<SearchProgress>()
        BookSearchIndex.buildText(book(200_000)) { events.add(it) }

        assertTrue(events.isNotEmpty())
        assertTrue("the last event must mark completion", events.last().done)
        assertEquals(events.last().total, events.last().scanned)
    }

    @Test
    fun repeatedQueriesReuseTheSameIndexCheaply() = runBlocking {
        val index = BookSearchIndex.buildText(book(400_000))
        val first = index.find("山川河流", limit = 50)
        val second = index.find("山川河流", limit = 50)
        assertEquals("a reused index must be deterministic", first.size, second.size)
        assertEquals(first.first().locator.offset, second.first().locator.offset)
    }
}
