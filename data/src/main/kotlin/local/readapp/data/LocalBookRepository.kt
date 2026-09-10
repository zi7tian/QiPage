package local.readapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.readapp.core.*
import java.io.File
import java.util.UUID

class LocalBookRepository(private val context:Context, private val dao:BookDao,private val marks:BookmarkDao, private val engine:TextEngine,private val epubEngine:EpubEngine):BookRepository {
    override suspend fun editBook(id:String,title:String,author:String,description:String)=mutex.withLock {
        require(title.isNotBlank()) { "书名不能为空" }
        val row=dao.find(id) ?: error("书籍不存在")
        dao.update(row.copy(title=title.trim().take(200),author=author.trim().take(200),description=description.take(8000)))
    }
    private val root=File(context.filesDir,"books").apply { mkdirs() }
    private val mutex=Mutex()
    private val source=LocalDocumentSource(context.contentResolver)
    override val books=dao.observe().map { rows -> rows.map { row -> row.model().copy(cover=File(folder(row.id),"cover.png").takeIf { it.exists() }?.absolutePath.orEmpty()) } }
    private fun folder(id:String):File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root,id) }
    private fun persist(uri:Uri, flags:Int):Boolean {
        if(flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) return false
        return runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
    }
    private suspend fun recoverSource(row:BookRow,raw:File) {
        val staging=File(raw.parentFile,"source-recovery.tmp")
        try {
            val copied=withTimeout(60_000) { source.copy(Uri.parse(row.uri),staging) }
            require(copied.hash==row.hash) { "原文件内容已改变，请作为新书重新导入" }
            check(staging.renameTo(raw)) { "无法恢复本机副本" }
        } finally { staging.delete() }
    }
    override suspend fun importBook(uri:String,grantFlags:Int):ImportResult = mutex.withLock { withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString(); val directory=folder(id).apply { mkdirs() }; val raw=File(directory,"source.txt")
        var committed=false
        try {
            val address=Uri.parse(uri); val copied=withTimeout(60_000) { source.copy(address,raw) }
            dao.byHash(copied.hash)?.let { return@withContext ImportResult(it.model(),true) }
            val content=if(copied.format=="txt")runInterruptible { engine.prepare(raw,File(directory,"text")) } else null
            var title=copied.name.replace(Regex("(?i)\\.(txt|epub)$"),"");var author=""
            if(copied.format=="epub")runInterruptible { epubEngine.open(raw).use { epub ->
                title=epub.title;author=epub.author
                // Validate the first chapter before registering the book; later chapters load on demand.
                epub.chapter(epub.chapters.first().path)
                epub.cover()?.second?.let { bytes ->
                    val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if(bounds.outWidth>0 && bounds.outHeight>0){
                        var sample=1;while(bounds.outWidth/sample>256 || bounds.outHeight/sample>256)sample*=2
                        val options=android.graphics.BitmapFactory.Options().apply { inSampleSize=sample }
                        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)?.let { bitmap ->
                            try{File(directory,"cover.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }}finally{bitmap.recycle()}
                        }
                    }
                }
            } }
            ensureActive()
            // Persisting a grant, inserting metadata and choosing reference/copy form one commit step.
            val row=withContext(NonCancellable) {
                val referenced=persist(address,grantFlags) && copied.format=="txt"
                val row=BookRow(id,title,copied.hash,uri,!referenced,content?.encoding?:"",content?.length?:0,copied.bytes,format=copied.format,author=author)
                dao.insert(row); committed=true
                if(referenced) raw.delete()
                row
            }
            ImportResult(row.model(),false)
        } finally { if(!committed) directory.deleteRecursively() }
    } }
    override suspend fun openBook(id:String):OpenBook = mutex.withLock { withContext(Dispatchers.IO) {
        val row=dao.find(id) ?: error("这本书已移出书架")
        if(row.format=="epub"){
            val raw=File(folder(id),"source.txt");if(!raw.exists())recoverSource(row,raw)
            val epub=runInterruptible { epubEngine.open(raw) }
            dao.locator(id,row.locator,System.currentTimeMillis())
            return@withContext OpenBook(row.model(),epub=epub)
        }
        val cache=File(folder(id),"text")
        val content=try { engine.reopen(cache,row.encoding) } catch (_:Exception) {
            val raw=File(folder(id),"source.txt")
            try {
                if(!raw.exists()) recoverSource(row,raw)
                runInterruptible { engine.prepare(raw,cache,row.encoding) }
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { throw IllegalStateException("无法恢复正文，请重新选择原 TXT 文件。${e.message.orEmpty()}",e) }
            finally { if(!row.copied) raw.delete() }
        }
        dao.progress(id,row.position.coerceIn(0,content.length),System.currentTimeMillis())
        runInterruptible { content.toc };OpenBook(row.model(),content)
    } }
    override suspend fun savePosition(id:String,position:Long) {
        val row=dao.find(id) ?: return
        dao.progress(id,position.coerceIn(0,row.chars),System.currentTimeMillis())
    }
    override suspend fun removeBook(id:String)=mutex.withLock { withContext(Dispatchers.IO) {
        dao.delete(id); folder(id).deleteRecursively(); Unit
    } }
    override suspend fun changeEncoding(id:String,encoding:String):OpenBook=mutex.withLock { withContext(Dispatchers.IO) {
        require(encoding in setOf("UTF-8","GB18030","UTF-16LE","UTF-16BE"))
        val row=dao.find(id) ?: error("书籍不存在")
        val directory=folder(id); val raw=File(directory,"source.txt"); val newCache=File(directory,"text-new"); val oldCache=File(directory,"text")
        try {
            if(!raw.exists()) recoverSource(row,raw)
            val content=runInterruptible { engine.prepare(raw,newCache,encoding) }
            withContext(NonCancellable) {
                val backup=File(directory,"text-old"); backup.deleteRecursively()
                check(oldCache.renameTo(backup)); check(newCache.renameTo(oldCache))
                try { dao.update(row.copy(encoding=content.encoding,chars=content.length,position=0,locator="")) }
                catch(e:Exception) { oldCache.deleteRecursively(); backup.renameTo(oldCache); throw e }
                backup.deleteRecursively()
            }
            val next=dao.find(id)!!;val reopened=engine.reopen(oldCache,next.encoding);reopened.toc; OpenBook(next.model(),reopened)
        } finally { newCache.deleteRecursively(); if(!row.copied) raw.delete() }
    } }
    override suspend fun reconnect(id:String,uri:String,grantFlags:Int)=mutex.withLock { withContext(Dispatchers.IO) {
        val row=dao.find(id) ?: error("书籍不存在")
        val temp=File.createTempFile("relink-",".txt",context.cacheDir)
        try {
            val address=Uri.parse(uri); val copied=source.copy(address,temp)
            require(copied.hash==row.hash) { "选择的不是原文件；内容不同的书请通过导入添加" }
            withContext(NonCancellable) {
                val referenced=persist(address,grantFlags) && row.format=="txt"
                if(!referenced) temp.copyTo(File(folder(id),"source.txt"),overwrite=true)
                dao.update(row.copy(uri=uri,copied=!referenced))
            }
        } finally { temp.delete() }
    } }
    override suspend fun saveLocator(id:String,locator:Locator) {
        if(locator.format=="txt")savePosition(id,locator.offset)
        else dao.locator(id,LocatorCodec.encode(locator),System.currentTimeMillis())
    }
    override fun bookmarks(id:String)=marks.observe(id).map { rows -> rows.map { Bookmark(it.id,it.bookId,it.title,LocatorCodec.decode(it.locator),it.createdAt) } }
    override suspend fun addBookmark(id:String,title:String,locator:Locator) { marks.insert(BookmarkRow(UUID.randomUUID().toString(),id,title.take(160),LocatorCodec.encode(locator),System.currentTimeMillis())) }
    override suspend fun removeBookmark(id:String)=marks.delete(id)
}
