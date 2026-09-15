package local.readapp.feature

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import local.readapp.core.*
import local.readapp.design.ReaderTheme
import kotlin.math.roundToInt

data class IncomingFile(val uri:String,val flags:Int)

@Composable fun ReadApp(repository:BookRepository,preferences:PreferencesRepository,incoming:Flow<IncomingFile>) {
    val vm:LibraryViewModel=viewModel(factory=viewModelFactory { initializer { LibraryViewModel(repository,preferences,createSavedStateHandle()) } })
    val books by vm.books.collectAsStateWithLifecycle()
    val settings by vm.preferences.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val broken by vm.brokenBook.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    var navigationOpen by remember { mutableStateOf(false) }
    var relinking by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var highlight by remember { mutableStateOf<Highlight?>(null) }
    val chrome=remember { ReaderChrome() }
    var selection by remember { mutableStateOf<SelectionPage?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    val paperContext=LocalContext.current
    val paperMemory=remember {paperContext.getSharedPreferences("paper-ui",0)}
    val activeLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(reader?.book?.id,activeLifecycle) {
        if(reader!=null)activeLifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var last=android.os.SystemClock.elapsedRealtime()
            while(true){kotlinx.coroutines.delay(1000);val now=android.os.SystemClock.elapsedRealtime();val seconds=(now-last)/1000;last=now
                paperMemory.edit().putLong("readingSeconds",paperMemory.getLong("readingSeconds",0)+seconds).apply()
            }
        }
    }
    LaunchedEffect(reader?.book?.id,bookmarks) {
        val current=reader ?: return@LaunchedEffect
        paperMemory.getString("pending-note",null)?.let {raw->
            val j=org.json.JSONObject(raw)
            if(j.optString("book")==current.book.id){vm.navigate(decodeLocation(j.getJSONObject("locator")));highlight=Highlight(j.getJSONObject("locator").optLong("offset"),j.optInt("length",j.optString("quote").length));paperMemory.edit().remove("pending-note").apply()}
        }
        val id=paperMemory.getString("pending-bookmark",null)
        bookmarks.firstOrNull {it.id==id}?.let {vm.navigate(it.locator);paperMemory.edit().remove("pending-bookmark").apply()}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK) {
            val intent=result.data
            val uris=buildList { intent?.data?.let { add(it.toString()) }; intent?.clipData?.let { clip -> for(i in 0 until clip.itemCount)add(clip.getItemAt(i).uri.toString()) } }.distinct()
            if(uris.isNotEmpty()) { val id=relinking; if(id!=null)vm.reconnect(id,uris.first(),intent?.flags?:0) else vm.importFiles(uris,intent?.flags?:0) }
        }
        relinking=null
    }
    fun pick(id:String?=null) {
        relinking=id
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY,true); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,id==null)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }
    LaunchedEffect(incoming) { incoming.collect { vm.importFiles(listOf(it.uri),it.flags) } }
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle,vm) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP)vm.flush() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); vm.flush() }
    }
    LaunchedEffect(reader) { if(reader==null) { settingsOpen=false; navigationOpen=false; highlight=null; chrome.visible=false; notesOpen=false;selection=null } }
    ReaderTheme(settings.theme) { AppPalette(settings) {
        val window=(LocalContext.current as Activity).window
        val dark=readerIsDark(settings)
        val surface=if(reader==null)MaterialTheme.colorScheme.surface else customColor(if(dark)settings.nightBackground else settings.dayBackground,MaterialTheme.colorScheme.surface)
        SideEffect {
            @Suppress("DEPRECATION")
            window.statusBarColor=surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor=surface.toArgb()
            val flags=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(surface.luminance()>.5f)flags else 0,flags)
            window.insetsController?.systemBarsBehavior=android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
            if(reader!=null)window.insetsController?.hide(android.view.WindowInsets.Type.navigationBars()) else window.insetsController?.show(android.view.WindowInsets.Type.navigationBars())
        }
        Surface(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalReaderChrome provides chrome,LocalSelectPage provides {selection=it},LocalMarkSelection provides {mark->reader?.let{book->
                val store=paperContext.getSharedPreferences("paper-notes",0);val entries=org.json.JSONArray(store.getString("entries","[]"))
                entries.put(org.json.JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("book",book.book.id).put("title",book.book.title).put("quote",mark.text).put("length",mark.length).put("note","").put("locator",encodeLocation(mark.locator)).put("created",System.currentTimeMillis()))
                store.edit().putString("entries",entries.toString()).apply();highlight=Highlight(mark.locator.offset,mark.length);vm.navigate(mark.locator)
            }}) {
            Box(Modifier.fillMaxSize().then(if(reader!=null)Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)) else Modifier)) {
                if(reader==null) CompositionLocalProvider(LocalImport provides vm::importFiles) { PaperBookshelf(books,busy!=null,{pick()},vm::open,vm::remove,vm::editBook,settings,vm::updatePreferences,repository) }
                else ReadingAppearance(settings) { if(reader!!.epub!=null) EpubReaderScreen(reader!!,settings,settingsOpen,highlight,{highlight=null},jump,vm::locate,vm::closeReader,{if(settingsOpen)settingsOpen=false else chrome.visible=!chrome.visible},{navigationOpen=true},vm::consumeJump)
                else ReaderScreen(reader!!,settings,settingsOpen,highlight,{highlight=null},jump,vm::position,vm::closeReader,{if(settingsOpen)settingsOpen=false else chrome.visible=!chrome.visible},{navigationOpen=true},vm::consumeJump) }
                if(reader!=null && !settingsOpen && !navigationOpen)PaperReaderChrome(chrome,reader!!,settings,vm::updatePreferences,vm::closeReader,{navigationOpen=true},{settingsOpen=true},{vm.addBookmark(chrome.chapter.ifBlank {reader!!.book.title})},{notesOpen=true})
                if(notesOpen && reader!=null)PaperLibraryPanel("书签与笔记",listOf(reader!!.book),settings,vm::updatePreferences,repository,{id->notesOpen=false
                    val raw=paperMemory.getString("pending-note",null)
                    if(raw!=null){val j=org.json.JSONObject(raw);val loc=decodeLocation(j.getJSONObject("locator"));vm.navigate(loc);highlight=Highlight(loc.offset,j.optInt("length",j.optString("quote").length));paperMemory.edit().remove("pending-note").apply()}
                    else{val mark=paperMemory.getString("pending-bookmark",null);bookmarks.firstOrNull{it.id==mark}?.let{vm.navigate(it.locator);paperMemory.edit().remove("pending-bookmark").apply()}}
                },{notesOpen=false})
                selection?.let {page->reader?.let {book->SelectionNotebook(page,book.book.id,book.book.title,{locator,length->highlight=Highlight(locator.offset,length);vm.navigate(locator)},{selection=null})}}
                if(busy!=null) AlertDialog(onDismissRequest={},title={Text("请稍候")},text={Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(16.dp)); Text(busy!!) }},confirmButton={TextButton(onClick=vm::cancelOperation){Text("取消")}})
                if(message!=null && busy==null) AlertDialog(onDismissRequest=vm::dismissMessage,title={Text(if(broken!=null)"正文暂时无法打开" else "导入结果")},text={Text(message!!)},confirmButton={TextButton(onClick=vm::dismissMessage){Text("知道了")}},dismissButton={ if(broken!=null)TextButton(onClick={vm.dismissMessage(); pick(broken)}){Text("重新选择原文件")} })
                // Typography is adjustable only inside the reader; leaving the reader
                // must never strand the panel over the bookshelf.
                if(settingsOpen && reader!=null) Box(Modifier.align(Alignment.BottomCenter)) { ReaderSettings(settings,reader?.book?.encoding?.takeIf { it.isNotBlank() },vm::updatePreferences,{vm.encoding(it);settingsOpen=false},{settingsOpen=false}) }
                if(navigationOpen && reader!=null) NavigationSheet(
                    reader!!,settings,bookmarks,
                    {locator,length-> highlight=if(length>0)Highlight(locator.offset,length) else null; vm.navigate(locator); navigationOpen=false },
                    vm::addBookmark,vm::removeBookmark,{navigationOpen=false})
            }
        }
    }
    }
    BackHandler(reader!=null && busy==null && !settingsOpen && !navigationOpen && !chrome.visible && selection==null && !notesOpen) { vm.closeReader() }
    }
    val activity=LocalContext.current as Activity
    DisposableEffect(reader!=null,settings.keepScreenOn) {
        if(reader!=null && settings.keepScreenOn)activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

internal val LocalImport=staticCompositionLocalOf<(List<String>,Int)->Unit> { {_,_->} }
@Composable internal fun BookDetails(book:Book,save:(String,String,String)->Unit,remove:()->Unit,close:()->Unit){
    var title by remember(book.id){mutableStateOf(book.title)};var author by remember(book.id){mutableStateOf(book.author)};var description by remember(book.id){mutableStateOf(book.description)};var confirm by remember{mutableStateOf(false)}
    androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()) {Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("书籍详情",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);TextButton(onClick=close){Text("关闭")}}
            Spacer(Modifier.height(24.dp));OutlinedTextField(title,{title=it.take(200)},label={Text("书籍名称")},modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp));OutlinedTextField(author,{author=it.take(200)},label={Text("作者")},modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp));OutlinedTextField(description,{description=it.take(8000)},label={Text("简介 / 备注")},modifier=Modifier.fillMaxWidth(),minLines=5)
            Spacer(Modifier.height(24.dp));Text("${book.format.uppercase()} · ${"%.2f".format(book.bytes/1048576.0)} MiB"+(if(book.encoding.isNotBlank())" · ${book.encoding}" else ""))
            Text(if(book.localCopy)"已保存在应用本机空间" else "已建立本机阅读索引",style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp));Button(enabled=title.isNotBlank(),onClick={save(title,author,description)}){Text("保存修改")}
            TextButton(onClick={confirm=true}){Text("移出书架")}
        }}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("移出书架？")},text={Text("移除阅读记录和应用内副本，保留手机中的原文件。")},confirmButton={TextButton(onClick=remove){Text("移出")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}

@Composable internal fun LicenseText(file:String,close:()->Unit){
    val context=LocalContext.current
    val license by produceState("",file){value=withContext(Dispatchers.IO){context.assets.open("licenses/$file").bufferedReader().use{it.readText()}}}
    androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){Column(Modifier.safeDrawingPadding().padding(20.dp)){
            Row{Text(file,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick=close){Text("关闭")}}
            Text(license,Modifier.weight(1f).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
        }}
    }
}





