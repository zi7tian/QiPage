package local.readapp.epub

import java.io.*
import java.net.URLDecoder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Deliberately restricted P0 EPUB adapter. No extraction, sockets, scripting or DRM. */
class LocalEpub private constructor(
    private val zip: ZipFile,
    val title: String,
    val author:String,
    val chapters: List<Item>,
    val toc: List<TocEntry>,
    private val manifest: Map<String, Item>,private val coverPath:String?
) : Closeable {
    data class Item(val path: String, val mediaType: String)
    data class TocEntry(val title: String, val path: String,val fragment:String="",val depth:Int=0)
    fun coverResource():Pair<String,ByteArray>?=coverPath?.let(::resource)?.takeIf { it.first.startsWith("image/") }
    fun resource(path: String): Pair<String, ByteArray>? {
        val item = manifest[path] ?: return null
        // SVG can contain scripting or external references. Defer it until a sanitizer exists.
        if (item.mediaType !in ALLOWED_TYPES) return null
        val entry = zip.getEntry(path) ?: return null
        return item.mediaType to zip.getInputStream(entry).use { boundedRead(it, MAX_ENTRY) }
    }
    override fun close() = zip.close()

    companion object {
        const val MAX_ENTRY = 32 * 1024 * 1024
        const val MAX_TOTAL = 1024L * 1024 * 1024
        private val ALLOWED_TYPES = setOf("application/xhtml+xml", "text/html", "text/css", "image/png", "image/jpeg", "image/gif", "image/webp")

        fun resolve(baseFile: String, reference: String): String {
            require(!reference.startsWith("//") && !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(reference)) { "External URI rejected" }
            val plain = URLDecoder.decode(reference.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
            require(!plain.startsWith('/') && !plain.contains('\\') && !plain.contains('%') && plain.none { it.code < 32 }) { "Invalid resource path" }
            if (plain.isEmpty()) return baseFile
            val result = baseFile.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            plain.split('/').forEach {
                when (it) {
                    "", "." -> Unit
                    ".." -> { require(result.isNotEmpty()) { "Path escapes book" }; result.removeAt(result.lastIndex) }
                    else -> { require(!it.contains(':')); result.add(it) }
                }
            }
            return result.joinToString("/")
        }

        fun open(file: File): LocalEpub {
            val zip = ZipFile(file)
            try {
                require(file.length() <= 512L * 1024 * 1024) { "Archive too large for P0" }
                val entries = zip.entries(); val names = HashSet<String>(); var total = 0L
                val scratch = ByteArray(8192)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(names.add(entry.name) && names.size <= 20000) { "Duplicate or excessive entries" }
                    require(!entry.name.startsWith('/') && !entry.name.contains('\\') && !entry.name.contains(':') && entry.name.none { it.code < 32 }) { "Unsafe ZIP path" }
                    require(entry.name.split('/').none { it == ".." || it == "." }) { "Unsafe ZIP path" }
                    if (entry.isDirectory) continue
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
                            val read = input.read(scratch); if (read < 0) break
                            size += read; total += read
                            require(size <= MAX_ENTRY && total <= MAX_TOTAL) { "Decompression limit exceeded" }
                        }
                    }
                }
                require(zip.getEntry("META-INF/encryption.xml") == null) { "Encrypted/font-obfuscated EPUB deferred" }
                fun bytes(path: String) = zip.getInputStream(zip.getEntry(path) ?: error("Missing $path")).use { boundedRead(it, 8 * 1024 * 1024) }
                require(String(bytes("mimetype"), Charsets.US_ASCII).trim() == "application/epub+zip") { "Not an EPUB" }
                val container = xml(bytes("META-INF/container.xml"))
                val opfPath = resolve("", container.elements("rootfile").first().getAttribute("full-path"))
                val opf = xml(bytes(opfPath))
                require(opf.elements("meta").none { it.getAttribute("property") == "rendition:layout" && it.textContent.trim() == "pre-paginated" }) { "Fixed layout deferred" }
                val ids = opf.elements("item").associate { e ->
                    e.getAttribute("id") to Item(resolve(opfPath, e.getAttribute("href")), e.getAttribute("media-type"))
                }
                val chapters = opf.elements("itemref").filter { it.getAttribute("linear") != "no" }.map { ids[it.getAttribute("idref")] ?: error("Broken spine") }
                require(chapters.isNotEmpty() && chapters.all { it.mediaType in setOf("application/xhtml+xml", "text/html") && zip.getEntry(it.path) != null }) { "Unsupported or missing spine" }
                val nav = opf.elements("item").firstOrNull { it.getAttribute("properties").split(' ').contains("nav") }?.let { ids[it.getAttribute("id")] }
                val ncx = ids.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
                val toc = when {
                    nav != null -> {
                        val document = xml(bytes(nav.path))
                        val tocNav = document.elements("nav").firstOrNull { it.getAttributeNS("http://www.idpf.org/2007/ops", "type").split(' ').contains("toc") }
                        val anchors = tocNav?.getElementsByTagNameNS("*", "a")
                        (0 until (anchors?.length ?: 0)).map { anchors!!.item(it) as Element }.map { anchor ->
                            val ref=anchor.getAttribute("href"); var depth=0;var parent=anchor.parentNode
                            while(parent!=null && parent!=tocNav){if(parent is Element && parent.localName=="ol")depth++;parent=parent.parentNode}
                            TocEntry(anchor.textContent.trim(),resolve(nav.path,ref),fragment(ref),(depth-1).coerceAtLeast(0))
                        }
                    }
                    ncx != null -> xml(bytes(ncx.path)).elements("navPoint").map { point ->
                        val label = point.getElementsByTagNameNS("*", "text").item(0).textContent
                        val ref = (point.getElementsByTagNameNS("*", "content").item(0) as Element).getAttribute("src")
                        var depth=0;var parent=point.parentNode
                        while(parent!=null){if(parent is Element && parent.localName=="navPoint")depth++;parent=parent.parentNode}
                        TocEntry(label, resolve(ncx.path, ref),fragment(ref),depth)
                    }
                    else -> chapters.mapIndexed { i, item -> TocEntry("第 ${i + 1} 节", item.path) }
                }
                val coverId=opf.elements("item").firstOrNull { "cover-image" in it.getAttribute("properties").split(' ') }?.getAttribute("id")
                    ?: opf.elements("meta").firstOrNull { it.getAttribute("name")=="cover" }?.getAttribute("content")
                return LocalEpub(zip, opf.elements("title").firstOrNull()?.textContent ?: "未命名",opf.elements("creator").joinToString("、"){it.textContent.trim()}, chapters, toc, ids.values.associateBy { it.path },ids[coverId]?.path)
            } catch (e: Throwable) { zip.close(); throw e }
        }

        private fun fragment(ref:String)=if('#' in ref) URLDecoder.decode(ref.substringAfter('#').replace("+","%2B"),"UTF-8") else ""
        private fun boundedRead(input: InputStream, max: Int): ByteArray {
            val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= max); out.write(buffer, 0, n) }
            return out.toByteArray()
        }
        private fun xml(bytes: ByteArray): Document {
            // P0 metadata is UTF-8 only. Reject declarations before parsing on both JVM and Android.
            val text = String(bytes, Charsets.UTF_8)
            require(text.count { it=='<' }<=20_000) { "Metadata too complex" }
            require(!text.contains('\u0000') && !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "DTD/entities rejected" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            return builder.parse(ByteArrayInputStream(bytes))
        }
        private fun Document.elements(name: String): List<Element> = getElementsByTagNameNS("*", name).let { list -> (0 until list.length).map { list.item(it) as Element } }
    }
}


