package local.readapp.feature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.readapp.core.TextContent
internal data class PageText(val text:String,val offsets:LongArray)

/** A run of book text to paint with a highlight, in source coordinates. */
internal data class Highlight(val start:Long,val length:Int)
/** Translate a native selection back through compacted whitespace to source characters. */
internal fun sourceSelection(offsets:LongArray,start:Int,end:Int,fallback:Long):Highlight {
    require(start>=0 && end>start)
    val from=offsets.getOrNull(start)?:fallback
    val until=offsets.getOrNull(end-1)?.plus(1)?:from+(end-start)
    return Highlight(from,(until-from).coerceIn(1,Int.MAX_VALUE.toLong()).toInt())
}
internal fun compactParagraphs(source:String,start:Long):PageText {
    val text=StringBuilder();val map=ArrayList<Long>();var i=0
    while(i<source.length){
        if(source[i]=='\r'||source[i]=='\n'){
            map.add(start+i);text.append('\n');i++
            while(i<source.length){
                var next=i
                while(next<source.length && (source[next]==' '||source[next]=='\t'||source[next]=='\u3000'))next++
                if(next<source.length && (source[next]=='\r'||source[next]=='\n'))i=next+1 else break
            }
        }else{map.add(start+i);text.append(source[i++])}
    }
    map.add(start+source.length);return PageText(text.toString(),map.toLongArray())
}
internal suspend fun textWindow(content:TextContent,start:Long,end:Long):PageText=withContext(Dispatchers.IO){
    val out=StringBuilder();var i=content.findBlock(start)
    while(i<content.blockCount && out.length<end-start){
        val block=content.readBlock(i++);val from=(start-block.start).coerceAtLeast(0).toInt();val until=(end-block.start).coerceAtMost(block.text.length.toLong()).toInt()
        if(until>from)out.append(block.text,from,until)
        if(block.start+block.text.length>=end)break
    }
    var text=out.toString();var aligned=start
    if(text.isNotEmpty() && text.first().isLowSurrogate()){text=text.drop(1);aligned++}
    if(text.isNotEmpty() && text.last().isHighSurrogate())text=text.dropLast(1)
    compactParagraphs(text,aligned)
}
