package local.readapp.epub

import local.readapp.core.*
import java.io.*
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.*
import org.xml.sax.InputSource

/** Structural allowlist: book CSS and executable content never enter the renderer. */
class SafeEpubEngine:EpubEngine {
    override fun open(source:File):EpubContent {
        val book=LocalEpub.open(source)
        return object:EpubContent {
            private val cached=object:LinkedHashMap<String,String>(3,0.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,String>)=size>2}
            override val title=book.title
            override val author=book.author
            override val chapters=book.chapters.mapIndexed { i,item -> EpubSpine(item.path,book.toc.firstOrNull { it.path==item.path }?.title ?: "第 ${i+1} 节") }
            override val toc=book.toc.filter { item -> chapters.any { it.path==item.path } }.map {
                Chapter(it.title,Locator(format="epub",href=it.path,anchor=if(it.fragment.isEmpty())"" else "book-${it.fragment}"),it.depth)
            }
            @Synchronized override fun chapter(path:String):String {
                require(chapters.any { it.path==path }) { "章节不在书籍正文中" }
                val headings=book.toc.filter { it.path==path }
                return cached[path] ?: sanitize(book.resource(path)?.second ?: error("章节文件缺失"),path,chapters.map { it.path }.toSet(),headings.map {it.title}.toSet(),headings.map {it.fragment}.filter {it.isNotEmpty()}.toSet()).also { if(it.length<=1_000_000)cached[path]=it }
            }
            override fun image(path:String):Pair<String,ByteArray>? = book.resource(path)?.takeIf { it.first.startsWith("image/") }
            override fun cover()=book.coverResource()
            @Synchronized override fun close(){cached.clear();book.close()}
        }
    }
    companion object {
        private val allowed=setOf("p","div","span","h1","h2","h3","h4","h5","h6","blockquote","ul","ol","li","dl","dt","dd","strong","b","em","i","u","s","small","sub","sup","br","hr","pre","code","table","thead","tbody","tr","th","td","a","img","figure","figcaption","section","article")
        private val removed=setOf("script","style","iframe","object","embed","form","input","button","textarea","select","svg","math","audio","video","link","meta","base","noscript")
        private fun escape(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
        fun resourceUrl(path:String)="https://reader.invalid/"+path.split('/').joinToString("/"){URLEncoder.encode(it,"UTF-8").replace("+","%20")}
        internal fun sanitize(bytes:ByteArray,path:String,spine:Set<String>,headingTitles:Set<String> = emptySet(),headingIds:Set<String> = emptySet()):String {
            var text=bytes.toString(Charsets.UTF_8)
            require(!text.contains('\u0000') && !Regex("<!\\s*ENTITY|<!DOCTYPE[^>]*\\[",RegexOption.IGNORE_CASE).containsMatchIn(text)) { "章节包含不支持的实体声明" }
            text=text.replace(Regex("<!DOCTYPE[^>]*>",RegexOption.IGNORE_CASE),"").replace("&nbsp;","&#160;")
            require(text.count { it=='<' }<=100_000) { "章节结构过于复杂" }
            var nesting=0
            Regex("<(/?)[A-Za-z][^>]*>").findAll(text).forEach { match ->
                if(match.groupValues[1]=="/")nesting-- else if(!match.value.endsWith("/>"))nesting++
                require(nesting<=128) { "章节层级过深" }
            }
            val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true;isExpandEntityReferences=false }
            val parser=factory.newDocumentBuilder();parser.setEntityResolver { _,_ -> InputSource(StringReader("")) }
            val document=parser.parse(ByteArrayInputStream(text.toByteArray()))
            val body=document.getElementsByTagNameNS("*","body").item(0) ?: error("章节缺少正文")
            var nodes=0;var offset=0L
            val output=StringBuilder()
            fun visit(node:Node,depth:Int) {
                require(depth<=128 && ++nodes<=100_000) { "章节结构过于复杂" }
                if(Thread.currentThread().isInterrupted)throw InterruptedIOException("Cancelled")
                if(node.nodeType==Node.TEXT_NODE || node.nodeType==Node.CDATA_SECTION_NODE){
                    val value=node.nodeValue.orEmpty()
                    // Formatting whitespace between blocks is not a paragraph. Keep
                    // source offsets stable while removing its phantom line box.
                    val parent=(node.parentNode as? Element)?.let{(it.localName?:it.tagName).lowercase()}
                    if(value.isBlank() && parent in setOf("body","div","section","article","ul","ol","table","tbody","tr")){offset+=value.length;return}
                    if(value.isNotEmpty()){output.append("<span data-read=\"").append(offset).append("\" id=\"read-").append(offset).append("\">").append(escape(value)).append("</span>");offset+=value.length}
                    return
                }
                if(node !is Element)return
                var tag=(node.localName?:node.tagName).lowercase()
                if(tag=="p" && node.textContent.isBlank() && node.getElementsByTagNameNS("*","img").length==0 && node.getElementsByTagNameNS("*","svg").length==0){offset+=node.textContent.length;return}
                if(tag=="p" && (node.getAttribute("id") in headingIds || node.textContent.trim() in headingTitles))tag="h2"
                // Calibre commonly wraps a raster cover in SVG. Keep only local raster references,
                // never SVG markup, scripts, foreignObject or event attributes.
                if(tag=="svg"){
                    val images=node.getElementsByTagNameNS("*","image")
                    for(i in 0 until images.length.coerceAtMost(100)){
                        val image=images.item(i) as Element
                        val ref=image.getAttributeNS("http://www.w3.org/1999/xlink","href").ifBlank { image.getAttribute("href") }
                        val target=runCatching { LocalEpub.resolve(path,ref) }.getOrNull()
                        if(ref.isNotBlank() && target!=null)output.append("<img src=\"").append(escape(resourceUrl(target))).append("\" alt=\"插图\"/>")
                    }
                    return
                }
                if(tag in removed)return
                if(tag !in allowed){for(i in 0 until node.childNodes.length)visit(node.childNodes.item(i),depth+1);return}
                val originalId=node.getAttribute("id")
                val heading=tag in setOf("h1","h2","h3","h4","h5","h6")
                if(originalId.isNotEmpty() && !heading)output.append("<span id=\"book-").append(escape(originalId)).append("\"></span>")
                if(tag=="img"){
                    val target=runCatching { LocalEpub.resolve(path,node.getAttribute("src")) }.getOrNull()
                    if(target!=null && node.getAttribute("src").isNotBlank())output.append("<img src=\"").append(escape(resourceUrl(target))).append("\" alt=\"").append(escape(node.getAttribute("alt"))).append("\"/>")
                    else output.append("<span>[外部图片未加载]</span>")
                    return
                }
                output.append('<').append(tag)
                if(heading && originalId.isNotEmpty())output.append(" id=\"book-").append(escape(originalId)).append('"')
                if(tag=="a"){
                    val ref=node.getAttribute("href");val target=runCatching { LocalEpub.resolve(path,ref) }.getOrNull()
                    if(target!=null && target in spine){
                        val fragment=if('#' in ref)"#book-"+ref.substringAfter('#') else ""
                        output.append(" href=\"").append(escape(resourceUrl(target)+fragment)).append('"')
                    } else output.append(" title=\"外部链接不可用\"")
                }
                if(tag in setOf("td","th"))for(attr in listOf("colspan","rowspan"))node.getAttribute(attr).toIntOrNull()?.coerceIn(1,20)?.let { output.append(' ').append(attr).append("=\"").append(it).append('"') }
                output.append('>')
                if(tag !in setOf("br","hr")){for(i in 0 until node.childNodes.length)visit(node.childNodes.item(i),depth+1);output.append("</").append(tag).append('>')}
            }
            for(i in 0 until body.childNodes.length)visit(body.childNodes.item(i),0)
            return output.toString().ifBlank { "<p>这一节暂无可显示正文</p>" }
        }
    }
}
