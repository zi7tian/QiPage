package local.readapp.feature

import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.readapp.core.*

data class JumpRequest(val locator:Locator,val serial:Long=System.nanoTime())
@OptIn(FlowPreview::class,ExperimentalCoroutinesApi::class)
class LibraryViewModel(private val repository:BookRepository, private val settings:PreferencesRepository, private val saved:SavedStateHandle):ViewModel() {
    val books=repository.books.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val preferences=settings.preferences.stateIn(viewModelScope,SharingStarted.Eagerly,ReaderPreferences())
    val reader=MutableStateFlow<OpenBook?>(null)
    val busy=MutableStateFlow<String?>(null)
    val message=MutableStateFlow<String?>(null)
    val brokenBook=MutableStateFlow<String?>(null)
    val jump=MutableStateFlow<JumpRequest?>(null)
    val bookmarks=reader.map { it?.book?.id }.distinctUntilChanged().flatMapLatest { if(it==null)flowOf(emptyList()) else repository.bookmarks(it) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private var operation:Job?=null
    private val pending=MutableStateFlow<Pair<String,Locator>?>(null)
    private val saveMutex=Mutex()
    private var retained:OpenBook?=null
    init {
        viewModelScope.launch { pending.sample(800).collect { persist() } }
        saved.get<String>("book")?.let(::open)
    }
    fun position(id:String,offset:Long) {
        locate(id,Locator(offset=offset))
    }
    fun locate(id:String,locator:Locator) {
        val current=reader.value ?: return
        if(current.book.id==id) {
            pending.value=id to locator
            reader.value=current.copy(book=current.book.copy(position=if(locator.format=="txt")locator.offset else current.book.position,locator=locator))
        }
    }
    private suspend fun persist()=saveMutex.withLock {
        pending.value?.let { (id,locator) -> repository.saveLocator(id,locator) }
    }
    fun flush() { viewModelScope.launch { persist() } }
    fun dismissMessage() { message.value=null }
    fun editBook(id:String,title:String,author:String,description:String) { viewModelScope.launch {
        try { repository.editBook(id,title,author,description) }
        catch(e:Exception) { if(e is CancellationException)throw e;message.value=readableError(e) }
    } }
    fun cancelOperation() { operation?.cancel() }
    fun importFiles(uris:List<String>,flags:Int) {
        if(operation?.isActive==true) { message.value="正在处理文件，请稍候"; return }
        operation=viewModelScope.launch {
            var imported=0; var duplicate=0; val failures=mutableListOf<String>(); var single:Book?=null
            try {
                persist()
                for((i,uri) in uris.distinct().take(100).withIndex()) {
                    busy.value="正在导入 ${i+1} / ${uris.distinct().take(100).size}…"
                    try {
                        val result=repository.importBook(uri,flags)
                        if(result.duplicate)duplicate++ else imported++
                        single=result.book
                    } catch(e:CancellationException) { throw e }
                    catch(e:Exception) { failures.add(readableError(e)) }
                }
                message.value=buildList {
                    if(imported>0)add("已导入 $imported 本")
                    if(duplicate>0)add("$duplicate 本已在书架")
                    if(failures.isNotEmpty())add("${failures.size} 个文件未导入：${failures.first()}")
                    if(uris.size>100)add("每批最多处理 100 个文件")
                }.joinToString("\n")
                if(uris.size==1 && failures.isEmpty() && single!=null) load(single!!.id)
            } catch(_:CancellationException) { message.value="已取消；已完成的导入保留在书架" }
            catch(e:Exception) { message.value=readableError(e) }
            finally { busy.value=null }
        }
    }
    fun open(id:String) {
        if(operation?.isActive==true)return
        operation=viewModelScope.launch {
            try { persist(); load(id) }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { brokenBook.value=id; message.value=readableError(e) }
            finally { busy.value=null }
        }
    }
    private suspend fun load(id:String) {
        val cached=retained?.takeIf { it.book.id==id }
        val opened=if(cached!=null)cached.copy(book=books.value.firstOrNull {it.id==id}?:cached.book) else repository.openBook(id)
        if(cached==null)retained?.epub?.close()
        retained=null
        pending.value=null;reader.value?.epub?.close(); reader.value=opened; jump.value=null;saved["book"]=id; brokenBook.value=null
    }
    fun closeReader() { viewModelScope.launch { persist(); pending.value=null;retained?.epub?.close();retained=reader.value; reader.value=null; saved["book"]=null } }
    fun navigate(locator:Locator){reader.value?.let {locate(it.book.id,locator)};jump.value=JumpRequest(locator)}
    fun consumeJump(){jump.value=null}
    fun addBookmark(title:String){val current=reader.value?:return;viewModelScope.launch {
        try{persist();repository.addBookmark(current.book.id,title.ifBlank { "阅读书签" },current.book.locator)}
        catch(e:Exception){if(e is CancellationException)throw e;message.value="书签保存失败"}
    }}
    fun removeBookmark(id:String){viewModelScope.launch { repository.removeBookmark(id) }}
    override fun onCleared(){reader.value?.epub?.close();retained?.epub?.close();super.onCleared()}
    fun remove(id:String) { viewModelScope.launch {
        try {
            if(reader.value?.book?.id==id) { persist(); pending.value=null;reader.value?.epub?.close(); reader.value=null; saved["book"]=null }
            repository.removeBook(id)
            if(retained?.book?.id==id){retained?.epub?.close();retained=null}
        } catch(e:Exception) { if(e is CancellationException)throw e; message.value=readableError(e) }
    } }
    fun updatePreferences(value:ReaderPreferences) { viewModelScope.launch { settings.update(value) } }
    fun encoding(value:String) {
        val book=reader.value?.book ?: return
        if(operation?.isActive==true)return
        operation=viewModelScope.launch {
            try {
                persist(); pending.value=null; reader.value=null; busy.value="正在切换编码…"
                reader.value=repository.changeEncoding(book.id,value)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                message.value="编码切换失败：${readableError(e)}"
                try { load(book.id) } catch(failure:Exception) {
                    if(failure is CancellationException)throw failure
                    brokenBook.value=book.id
                }
            }
            finally { busy.value=null }
        }
    }
    fun reconnect(id:String,uri:String,flags:Int) {
        if(operation?.isActive==true)return
        operation=viewModelScope.launch {
            try { busy.value="正在重新关联…"; repository.reconnect(id,uri,flags); load(id); message.value="已重新关联原文件" }
            catch(e:CancellationException) { throw e }
            catch(e:Exception) { message.value=readableError(e) }
            finally { busy.value=null }
        }
    }
    private fun readableError(e:Exception):String=when(e) {
        is SecurityException -> "文件读取授权已失效，请在文件管理器中重新选择"
        is java.nio.charset.CharacterCodingException -> "无法识别文本编码，请确认文件为 UTF-8、GB18030 或带 BOM 的 UTF-16 文本"
        is java.io.FileNotFoundException -> "文件已移动或无法读取，请重新选择"
        else -> when {
            e.message.orEmpty().contains("Fixed layout") -> "暂不支持固定版式 EPUB，请选择可重排文本 EPUB"
            e.message.orEmpty().contains("Encrypted") -> "暂不支持 DRM 加密或字体混淆的 EPUB"
            e.message.orEmpty().contains("Unsafe ZIP") || e.message.orEmpty().contains("Path escapes") -> "EPUB 包含越界资源路径，无法导入"
            e.message.orEmpty().contains("Decompression limit") || e.message.orEmpty().contains("Archive too large") -> "EPUB 超出解析上限：压缩包 512 MiB，累计解压 1 GiB，单项资源 32 MiB"
            e.message.orEmpty().contains("Duplicate or excessive") -> "EPUB 条目重复或超过 20000 项上限"
            e.message.orEmpty().contains("DTD/entities") -> "EPUB 元数据包含不支持的实体声明"
            e.message.orEmpty().contains("spine",true) -> "EPUB 正文资源缺失或格式暂不支持"
            e is java.util.zip.ZipException -> "EPUB 压缩包损坏或格式不正确"
            e is org.xml.sax.SAXException -> "EPUB 内容格式不规范，无法解析此文件"
            else -> e.message?.take(240) ?: "读取失败，请重新选择文件"
        }
    }
}

