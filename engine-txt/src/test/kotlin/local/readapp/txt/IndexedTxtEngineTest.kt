package local.readapp.txt
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class IndexedTxtEngineTest {
    @Test fun chapterCacheSurvivesReopenAndIsInvalidatedOnRebuild(){
        val dir=Files.createTempDirectory("qipage-toc").toFile()
        try {
            val source=File(dir,"book.txt").apply {writeText("第一章 开始\n正文\n第二章 结束\n正文")}
            val cache=File(dir,"cache");val engine=IndexedTxtEngine();val content=engine.prepare(source,cache)
            val first=content.toc;assertEquals(2,first.size);assertTrue(File(cache,"chapters.bin").exists())
            assertEquals(first,engine.reopen(cache,content.encoding).toc)
            File(cache,"chapters.bin").writeBytes(byteArrayOf(1,2,3))
            assertEquals(first,engine.reopen(cache,content.encoding).toc)
            source.writeText("Chapter 1 New\nnew text")
            val rebuilt=engine.prepare(source,cache)
            assertEquals(1,rebuilt.toc.size);assertEquals("Chapter 1 New",rebuilt.toc.first().title)
        }finally{dir.deleteRecursively()}
    }
    @Test fun hundredMiBIndexUnder64MiBHeap() {
        val dir=Files.createTempDirectory("readapp-large-index").toFile()
        try {
            val source=File(dir,"large.txt")
            val line=("甲😀".repeat(1000)+"\n").toByteArray()
            source.outputStream().buffered().use { out -> repeat((100L*1024*1024/line.size+1).toInt()) { out.write(line) } }
            val started=System.nanoTime()
            val content=IndexedTxtEngine().prepare(source,File(dir,"index"))
            assertTrue(source.length()>=100L*1024*1024)
            val middle=content.findBlock(content.length*4/5)
            assertTrue(content.readBlock(middle).text.contains("😀"))
            assertTrue(content.readBlock(content.blockCount-1).text.endsWith("\n"))
            println("P1_INDEX_BENCH bytes=${source.length()} blocks=${content.blockCount} prepareMs=${(System.nanoTime()-started)/1e6} heap=${Runtime.getRuntime().maxMemory()}")
        } finally { dir.deleteRecursively() }
    }
    @Test fun indexReassemblesExactlyAndReopensAtCharacterAnchor() {
        val dir = Files.createTempDirectory("readapp-index").toFile()
        try {
            val original = ("第一章\r\n" + "甲".repeat(2040) + "😀𠀀\n" + "\n".repeat(1000)).repeat(20)
            val file = File(dir, "book.txt").apply { writeText(original) }
            val cache = File(dir, "cache"); val engine = IndexedTxtEngine()
            val content = engine.prepare(file, cache)
            assertEquals(original, (0 until content.blockCount).joinToString("") { content.readBlock(it).text })
            val reopened = engine.reopen(cache, content.encoding)
            for (offset in 0L until reopened.length step 311) {
                val i = reopened.findBlock(offset); val block = reopened.readBlock(i)
                assertTrue(block.start <= offset && offset < block.start + block.text.length)
                assertFalse(block.text.first().isLowSurrogate()); assertFalse(block.text.last().isHighSurrogate())
            }
        } finally { dir.deleteRecursively() }
    }
    @Test fun emptyFileHasOneEmptyRow() {
        val dir = Files.createTempDirectory("readapp-empty").toFile()
        try {
            val source = File(dir,"empty.txt").apply { writeText("") }
            val content = IndexedTxtEngine().prepare(source, File(dir,"cache"))
            assertEquals(1, content.blockCount); assertEquals("", content.readBlock(0).text)
        } finally { dir.deleteRecursively() }
    }
}
