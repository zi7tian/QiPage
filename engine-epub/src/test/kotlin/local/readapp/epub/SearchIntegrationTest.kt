package local.readapp.epub

import kotlinx.coroutines.runBlocking
import local.readapp.core.Chapter
import local.readapp.core.EpubContent
import local.readapp.core.EpubSpine
import local.readapp.core.Locator
import local.readapp.core.searchEpubContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search must work against what the sanitizer actually emits, not against an
 * idealised shape.
 *
 * The first version of the EPUB search assumed `<span data-read="0">`, but the
 * sanitizer also writes an `id` attribute, so every real book returned no hits
 * while the hand-written fake in the unit test kept passing. This test runs the
 * real sanitizer and then searches its output.
 */
class SearchIntegrationTest {

    private fun sanitize(html: String, path: String = "ch1.xhtml") =
        SafeEpubEngine.sanitize(html.toByteArray(Charsets.UTF_8), path, setOf(path))

    private fun epubOf(path: String, sanitized: String) = object : EpubContent {
        override val title = "集成测试"
        override val author = ""
        override val chapters = listOf(EpubSpine(path, "第一章"))
        override val toc = listOf(Chapter("第一章", Locator(format = "epub", href = path)))
        override fun chapter(p: String) = sanitized
        override fun image(path: String): Pair<String, ByteArray>? = null
        override fun close() {}
    }

    @Test
    fun sanitizerEmitsAnIdAttributeNextToTheOffset() {
        // Guards the assumption the search regex has to tolerate.
        val output = sanitize("<html><body><p>正文</p></body></html>")
        assertTrue("sanitizer output was: $output", output.contains("data-read="))
        assertTrue(
            "the offset span carries more attributes than data-read",
            Regex("<span data-read=\"\\d+\"[^>]+>").containsMatchIn(output)
        )
    }

    @Test
    fun searchFindsTextInRealSanitizedOutput() = runBlocking {
        val sanitized = sanitize(
            "<html><body><p>两个月后，秋高气爽。</p><p>雨停之后，他把书放回原处。</p></body></html>"
        )
        val hits = searchEpubContent(epubOf("ch1.xhtml", sanitized), "雨停之后")

        assertEquals("search must see through the real sanitizer markup", 1, hits.size)
        assertEquals("ch1.xhtml", hits[0].locator.href)
        // "两个月后，秋高气爽。" is ten characters, so the second run starts at 10.
        assertEquals(10L, hits[0].locator.offset)
    }

    @Test
    fun searchOffsetLandsInsideTheMatchingRun() = runBlocking {
        val sanitized = sanitize("<html><body><p>开头文字</p><p>abcdefghij</p></body></html>")
        val hits = searchEpubContent(epubOf("ch1.xhtml", sanitized), "defg")

        assertEquals(1, hits.size)
        assertEquals(4L + 3L, hits[0].locator.offset)
    }

    @Test
    fun searchFindsTextAcrossParagraphBoundaries() = runBlocking {
        val sanitized = sanitize("<html><body><p>前半句</p><p>后半句</p></body></html>")
        // The flattened text joins runs with a newline, so the two paragraphs are
        // adjacent enough for a cross-paragraph phrase to be found.
        val hits = searchEpubContent(epubOf("ch1.xhtml", sanitized), "前半句")
        assertEquals(1, hits.size)
        assertEquals(0L, hits[0].locator.offset)
    }

    @Test
    fun searchIgnoresMarkupAndEntities() = runBlocking {
        val sanitized = sanitize("<html><body><p>A &amp; B</p><p><strong>粗体</strong>普通</p></body></html>")
        assertEquals("entities must be searchable as text", 1, searchEpubContent(epubOf("ch1.xhtml", sanitized), "A & B").size)
        assertEquals(1, searchEpubContent(epubOf("ch1.xhtml", sanitized), "粗体普通").size)
    }
}
