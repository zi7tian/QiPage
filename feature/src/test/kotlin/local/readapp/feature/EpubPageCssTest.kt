package local.readapp.feature

import local.readapp.core.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSS and page-count invariants for the EPUB renderer. */
class EpubPageCssTest {

    private fun document(letterSpacing: Float = 0f, justify: Boolean = true) = document(
        body = "<p><span data-read=\"0\">正文</span></p>",
        prefs = ReaderPreferences(letterSpacing = letterSpacing, justify = justify),
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

    // -----------------------------------------------------------------------
    // Typography and the blank-page guard
    // -----------------------------------------------------------------------

    @Test
    fun paragraphsAreJustifiedSoABrokenLineIsNotLeftRagged() {
        // A punctuation prohibition can still push a character down a line; with
        // justification the line before it stretches instead of ending short.
        assertTrue(document().contains("text-align:justify"))
    }

    @Test
    fun justificationCanBeTurnedOff() {
        val ragged = document(justify = false)
        assertTrue(ragged.contains("text-align:start"))
        assertFalse(ragged.contains("text-align:justify"))
    }

    @Test
    fun letterSpacingIsApplied() {
        assertTrue(document(letterSpacing = 0.05f).contains("letter-spacing:0.05em"))
    }

    @Test
    fun cjkLineBreakingUsesStrictRules() {
        assertTrue(document().contains("line-break:strict"))
        assertTrue(document().contains("overflow-wrap:break-word"))
    }

    @Test
    fun matchesAreHighlighted() {
        assertTrue(document().contains("mark.qp-hl"))
        val script = highlightScript(120, 4)
        assertTrue(script.contains("createRange"))
        assertTrue(script.contains("qp-hl"))
        assertTrue("the start offset must be searched for", script.contains("=120"))
    }

    @Test
    fun navigationSnapsAwayFromAColumnWithNoContent() {
        val script = nearestContentPageJs("raw")
        assertTrue("must measure real fragments", script.contains("getClientRects"))
        assertTrue("must fall back to a neighbouring column", script.contains("has[t+d]"))
        assertTrue("and prefer the target when it has content", script.contains("if(has[t])return t"))
    }

    @Test
    fun theRestoreScriptIsSyntacticallyWellFormed() {
        // Regression: the highlight-clearing IIFE used to be concatenated straight
        // onto the next statement, producing "})()var container" - a SyntaxError
        // that silently broke every jump, so pages always opened at page one.
        val script = restoreScript(local.readapp.core.Locator(format = "epub", href = "a.xhtml", offset = 12))
        assertFalse("an IIFE must be terminated before the next statement", script.contains(")()var"))
        assertTrue(script.contains("})();var container"))
        assertEquals("braces must balance", script.count { it == '{' }, script.count { it == '}' })
        assertEquals("parentheses must balance", script.count { it == '(' }, script.count { it == ')' })
    }

    @Test
    fun pageIndexIsTheLastColumnThatHoldsContent() {
        // ceil((right - 1) / width) - 1 maps a right edge to its column index,
        // including when content ends exactly on a column boundary.
        assertTrue(LAST_CONTENT_PAGE_JS.contains("Math.ceil((right-1)/innerWidth)-1"))
    }
}
