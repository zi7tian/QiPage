package local.readapp.feature

import local.readapp.core.ReaderPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSS and page-count invariants for the EPUB renderer. */
class EpubPageCssTest {

    private fun document() = document(
        body = "<p><span data-read=\"0\">正文</span></p>",
        prefs = ReaderPreferences(),
        bg = 0xFFFFFFFF.toInt(),
        fg = 0xFF000000.toInt(),
        accent = 0xFF336699.toInt(),
        scale = 1f,
        viewportWidth = 400f,
        viewportHeight = 800f,
        dark = false,
    )

    @Test
    fun paragraphsGetAFirstLineIndent() {
        assertTrue(
            "EPUB paragraphs must start with a two-character indent",
            document().contains("p{margin:0 0")
        )
        assertTrue(document().contains("text-indent:2em"))
    }

    @Test
    fun headingsAreNotIndented() {
        // Only `p` carries the indent, so chapter headings stay flush left.
        val css = document().substringAfter("<style>").substringBefore("</style>")
        for (heading in listOf("h1", "h2", "h3", "h4", "h5", "h6")) {
            assertFalse("$heading must not be indented", css.contains("$heading{text-indent"))
        }
    }

    @Test
    fun imageLedParagraphsCancelTheIndent() {
        // Otherwise a full-width illustration would be pushed right by 2em.
        assertTrue(document().contains("p>img:first-child{margin-left:-2em}"))
    }

    @Test
    fun trailingBottomMarginIsDroppedSoNoBlankColumnIsEmitted() {
        // A final paragraph's bottom margin used to spill into an extra column,
        // which surfaced as a blank page at the end of a chapter.
        assertTrue(document().contains("#pages>:last-child{margin-bottom:0}"))
    }

    @Test
    fun pageCountComesFromRenderedContentRatherThanScrollWidth() {
        assertTrue(
            "page count must measure real content extents",
            LAST_CONTENT_PAGE_JS.contains("getBoundingClientRect")
        )
        assertFalse(
            "scrollWidth over-reports by one column and reintroduces the blank page",
            LAST_CONTENT_PAGE_JS.contains("scrollWidth")
        )
        assertTrue(
            "content extent must be measured in the scrolled coordinate space",
            LAST_CONTENT_PAGE_JS.contains("scrollLeft")
        )
    }

    @Test
    fun pageIndexIsTheLastColumnThatHoldsContent() {
        // ceil((right - 1) / width) - 1 maps a right edge to its column index,
        // including when content ends exactly on a column boundary.
        assertTrue(LAST_CONTENT_PAGE_JS.contains("Math.ceil((right-1)/innerWidth)-1"))
    }
}
