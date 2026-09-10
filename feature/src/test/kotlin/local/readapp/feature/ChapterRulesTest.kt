package local.readapp.feature

import local.readapp.core.*
import org.junit.Test
import org.junit.Assert.*

class ChapterRulesTest {
    private val titles=listOf("第一章 暑假来了","第一节下课后我们回家","第二节 上课","第三章没有空格","尾声","Chapter 4 End")
    private val content=object:TextContent {
        override val length=1000L;override val encoding="UTF-8";override val blockCount=0
        override val toc=titles.mapIndexed { i,s->Chapter(s,Locator(offset=i*100L),rule=if(s.startsWith("Chapter"))"english" else "chinese") }
        override fun blockStart(index:Int)=0L;override fun findBlock(position:Long)=0;override fun readBlock(index:Int)=TextBlock(0,"")
    }
    @Test fun unitsAndSeparatorsRejectFalseHeadingWithoutChangingOffsets(){
        val list=selectedChapters(content,ReaderPreferences(chapterUnits="章",chapterSeparator=true))
        assertEquals(listOf(titles[0],titles[4],titles[5]),list.map{it.title})
        assertEquals(listOf(0L,400L,500L),list.map{it.locator.offset})
    }
    @Test fun manualExclusionsAndNoRecognition(){
        assertFalse(selectedChapters(content,ReaderPreferences(excludedTitles=titles[1])).any {it.title==titles[1]})
        assertTrue(selectedChapters(content,ReaderPreferences(chapterRule="none")).isEmpty())
    }
}
