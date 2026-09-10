package local.readapp.feature
import org.junit.Test
import org.junit.Assert.*
class PageTextTest {
    @Test fun blankParagraphsCollapseWithoutLosingSourceOffsets(){
        val original="甲\r\n \t\r\n\u3000\n  乙😀\n\n丙"
        val mapped=compactParagraphs(original,900)
        assertEquals("甲\n  乙😀\n丙",mapped.text)
        for(i in mapped.text.indices)if(mapped.text[i]!='\n')assertEquals(mapped.text[i],original[(mapped.offsets[i]-900).toInt()])
        assertEquals(900L+original.length,mapped.offsets.last())
        assertTrue(mapped.offsets.toList().zipWithNext().all {it.first<it.second})
    }
    @Test fun unbrokenTextAndSurrogatesKeepExactMap(){
        val text="甲😀𠀀".repeat(3000);val mapped=compactParagraphs(text,25000000)
        assertEquals(text,mapped.text)
        assertEquals((25000000L..25000000L+text.length).toList(),mapped.offsets.toList())
    }
    @Test fun emptyAndLineEndOnlyAreFinite(){
        assertEquals(listOf(9L),compactParagraphs("",9).offsets.toList())
        val mapped=compactParagraphs("\r\n\n\r\n",9)
        assertEquals("\n",mapped.text);assertEquals(listOf(9L,14L),mapped.offsets.toList())
    }
}

