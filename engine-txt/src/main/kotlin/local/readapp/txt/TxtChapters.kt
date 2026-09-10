package local.readapp.txt
import local.readapp.core.*

internal object TxtChapters {
    private val chinese=Regex("^(第[〇零一二三四五六七八九十百千万两0-9０-９]{1,16}[章回节卷部篇].{0,100}|序章|序言|楔子|前言|后记|尾声|终章)$")
    private val english=Regex("^(chapter|part|book)\\s+([0-9ivxlcdm]+)(\\b.*)$",RegexOption.IGNORE_CASE)
    fun scan(content:TextContent):List<Chapter> {
        val result=ArrayList<Chapter>();val line=StringBuilder();var start=0L;var overlong=false
        fun finish(){
            if(!overlong && result.size<10000){val title=line.toString().trim();val rule=when{chinese.matches(title)->"chinese";english.matches(title)->"english";else->null}
                if(rule!=null)result.add(Chapter(title,Locator(offset=start),rule=rule))}
            line.setLength(0);overlong=false
        }
        for(i in 0 until content.blockCount){
            if(Thread.currentThread().isInterrupted)throw java.io.InterruptedIOException("Cancelled")
            val block=content.readBlock(i)
            block.text.forEachIndexed { j,c ->
                if(c=='\n'||c=='\r'){finish();start=block.start+j+1}
                else if(line.length<160)line.append(c) else overlong=true
            }
        }
        finish();return result
    }
}
