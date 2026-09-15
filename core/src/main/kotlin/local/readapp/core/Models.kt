package local.readapp.core

import java.io.File
import kotlinx.coroutines.flow.Flow

data class Book(val id: String, val title: String, val encoding: String, val chars: Long, val bytes: Long,
    val position: Long, val lastRead: Long, val importedAt: Long, val localCopy: Boolean,
    val format:String="txt",val author:String="",val locator:Locator=Locator(),val cover:String="",val description:String="")

data class Locator(val version:Int=1,val format:String="txt",val offset:Long=0,
    val href:String="",val anchor:String="",val progression:Double=0.0)
data class Chapter(val title:String,val locator:Locator,val depth:Int=0,val rule:String="all")
data class Bookmark(val id:String,val bookId:String,val title:String,val locator:Locator,val createdAt:Long)
data class EpubSpine(val path:String,val title:String)
interface EpubContent:java.io.Closeable {
    val title:String
    val author:String
    val chapters:List<EpubSpine>
    val toc:List<Chapter>
    fun chapter(path:String):String
    fun image(path:String):Pair<String,ByteArray>?
    fun cover():Pair<String,ByteArray>?=null
}
interface EpubEngine { fun open(source:File):EpubContent }
data class TextBlock(val start: Long, val text: String)
interface TextContent {
    val length: Long
    val encoding: String
    val blockCount: Int
    val toc:List<Chapter> get()=emptyList()
    fun blockStart(index: Int): Long
    fun findBlock(position: Long): Int
    /** Blocking disk access: invoke on an IO dispatcher. */
    fun readBlock(index: Int): TextBlock
}
interface TextEngine {
    fun prepare(source: File, directory: File, encoding: String? = null): TextContent
    fun reopen(directory: File, encoding: String): TextContent
}
data class OpenBook(val book: Book, val content: TextContent?=null,val epub:EpubContent?=null)
data class ImportResult(val book: Book, val duplicate: Boolean)
interface BookRepository {
    suspend fun editBook(id:String,title:String,author:String,description:String)
    val books: Flow<List<Book>>
    suspend fun importBook(uri: String, grantFlags: Int): ImportResult
    suspend fun openBook(id: String): OpenBook
    suspend fun savePosition(id: String, position: Long)
    suspend fun removeBook(id: String)
    suspend fun changeEncoding(id: String, encoding: String): OpenBook
    suspend fun reconnect(id: String, uri: String, grantFlags: Int)
    suspend fun saveLocator(id:String,locator:Locator)
    fun bookmarks(id:String):Flow<List<Bookmark>>
    suspend fun addBookmark(id:String,title:String,locator:Locator)
    suspend fun removeBookmark(id:String)
}
data class ReaderPreferences(val fontSize: Int = 20, val theme: String = "paper", val keepScreenOn: Boolean = false,
    val lineSpacing:Float=1.8f,val paragraphSpacing:Int=16,val pageMargin:Int=20,
    /** Extra tracking in em units, on top of whatever the typeface ships with. */
    val letterSpacing:Float=0f,val justify:Boolean=true,val chapterRule:String="all",
    val tapToTurn:Boolean=true,val bookmarksEnabled:Boolean=true,val navigationFirst:Boolean=true,
    val fontFile:String="",val fontName:String="",val backgroundFile:String="",
    val dayBackground:String="",val dayText:String="",val nightBackground:String="",val nightText:String="",
    val nightImageDim:Float=0.65f,val chapterUnits:String="章回节卷部篇",val chapterSeparator:Boolean=false,
    val chapterTitleLimit:Int=100,val excludedTitles:String="",val turnStyle:String="curl")

/** Rules only filter bounded heading candidates; no arbitrary regular expressions execute. */
fun selectedChapters(content:TextContent,prefs:ReaderPreferences):List<Chapter> {
    val excluded=prefs.excludedTitles.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    return content.toc.filter { chapter ->
        val title=chapter.title
        val unit=if(title.startsWith("第"))title.indexOfFirst { it in "章回节卷部篇" } else -1
        prefs.chapterRule!="none" && (prefs.chapterRule=="all" || chapter.rule==prefs.chapterRule) &&
            title !in excluded && title.length<=prefs.chapterTitleLimit &&
            (unit<0 || (title[unit] in prefs.chapterUnits && (!prefs.chapterSeparator || unit==title.lastIndex || title[unit+1].isWhitespace() || title[unit+1] in ":：、.．—-")))
    }
}
interface PreferencesRepository {
    val preferences: Flow<ReaderPreferences>
    suspend fun update(preferences: ReaderPreferences)
}
