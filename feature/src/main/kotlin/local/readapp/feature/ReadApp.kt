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
    LaunchedEffect(reader) { if(reader==null) { settingsOpen=false; navigationOpen=false } }
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
        }
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if(reader==null) CompositionLocalProvider(LocalImport provides vm::importFiles) { Bookshelf(books,busy!=null,{pick()},vm::open,vm::remove,vm::editBook) }
                else ReadingAppearance(settings) { if(reader!!.epub!=null) EpubReaderScreen(reader!!,settings,jump,vm::locate,vm::closeReader,{settingsOpen=!settingsOpen},{navigationOpen=true},vm::consumeJump)
                else ReaderScreen(reader!!,settings,jump,vm::position,vm::closeReader,{settingsOpen=!settingsOpen},{navigationOpen=true},vm::consumeJump) }
                if(busy!=null) AlertDialog(onDismissRequest={},title={Text("请稍候")},text={Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(16.dp)); Text(busy!!) }},confirmButton={TextButton(onClick=vm::cancelOperation){Text("取消")}})
                if(message!=null && busy==null) AlertDialog(onDismissRequest=vm::dismissMessage,title={Text(if(broken!=null)"正文暂时无法打开" else "导入结果")},text={Text(message!!)},confirmButton={TextButton(onClick=vm::dismissMessage){Text("知道了")}},dismissButton={ if(broken!=null)TextButton(onClick={vm.dismissMessage(); pick(broken)}){Text("重新选择原文件")} })
                // Typography is adjustable only inside the reader; leaving the reader
                // must never strand the panel over the bookshelf.
                if(settingsOpen && reader!=null) Box(Modifier.align(Alignment.BottomCenter)) { ReaderSettings(settings,reader?.book?.encoding?.takeIf { it.isNotBlank() },vm::updatePreferences,{vm.encoding(it);settingsOpen=false},{settingsOpen=false}) }
                if(navigationOpen && reader!=null) NavigationSheet(reader!!,settings,bookmarks,{vm.navigate(it);navigationOpen=false},vm::addBookmark,vm::removeBookmark,{navigationOpen=false})
            }
        }
    }
    }
    BackHandler(reader!=null && busy==null && !settingsOpen && !navigationOpen) { vm.closeReader() }
    val activity=LocalContext.current as Activity
    DisposableEffect(reader!=null,settings.keepScreenOn) {
        if(reader!=null && settings.keepScreenOn)activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@OptIn(ExperimentalFoundationApi::class,ExperimentalMaterial3Api::class)
@Composable private fun Bookshelf(books:List<Book>,busy:Boolean,onImport:()->Unit,onOpen:(String)->Unit,onRemove:(String)->Unit,onEdit:(String,String,String,String)->Unit) {
    val drawer=rememberDrawerState(DrawerValue.Closed);val scope=rememberCoroutineScope()
    var drawerDrag by remember {mutableFloatStateOf(0f)}
    var detail by remember { mutableStateOf<Book?>(null) };var directory by remember {mutableStateOf(false)}
    var query by remember { mutableStateOf("") };var about by remember {mutableStateOf(false)}
    var addOpen by remember {mutableStateOf(false)}
    var license by remember {mutableStateOf<String?>(null)}
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=drawer.isOpen,drawerContent={ModalDrawerSheet(Modifier.width(300.dp),drawerContainerColor=MaterialTheme.colorScheme.surface){
        Column(Modifier.padding(24.dp)){
            Text("栖页",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold)
            Text("QiPage · 0.4.0-p4",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp));Text("只在本机，安心阅读。")
            Text("Android 11 及以上 · TXT / EPUB",style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))


            TextButton(onClick={about=true}){Text("关于与许可")}
            OutlinedTextField(query,{query=it},label={Text("搜索书名")},singleLine=true)
            TextButton(onClick={scope.launch{drawer.close()}}){Text("返回书架")}
        }
    }}){
        val shown=books.filter {it.title.contains(query,true)}
        Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end=8.dp),horizontalArrangement=Arrangement.End){IconButton(onClick={addOpen=true},enabled=!busy,modifier=Modifier.semantics{contentDescription="添加书籍"}){Text("+",fontSize=28.sp,fontWeight=FontWeight.Light)}}
        LazyColumn(Modifier.weight(1f).pointerInput(drawer){detectHorizontalDragGestures(onDragStart={drawerDrag=0f},onHorizontalDrag={change,amount->change.consume();drawerDrag+=amount},onDragEnd={if(drawerDrag>60)scope.launch{drawer.open()}})}.semantics {customActions=listOf(CustomAccessibilityAction("打开应用菜单"){scope.launch{drawer.open()};true})},contentPadding=PaddingValues(24.dp)){
            if(books.isEmpty())item { Column(Modifier.fillMaxWidth().padding(vertical=80.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("留一点时间，给阅读。",style=MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp));Text("点击右上角 +，添加本机书籍")
                TextButton(onClick={addOpen=true}){Text("开始阅读")}
            }}
            items(shown,key={it.id}){book ->
                Row(Modifier.fillMaxWidth().combinedClickable(enabled=!busy,onClick={onOpen(book.id)},onLongClick={detail=book},onLongClickLabel="书籍详情").padding(vertical=20.dp),verticalAlignment=Alignment.CenterVertically){
                    val cover by produceState<ImageBitmap?>(null,book.cover){value=if(book.cover.isBlank())null else withContext(Dispatchers.IO){android.graphics.BitmapFactory.decodeFile(book.cover)?.asImageBitmap()}}
                    Box(Modifier.size(60.dp,82.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer),contentAlignment=Alignment.Center){
                        if(cover!=null)Image(cover!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        else Text(book.title.take(1),style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)){
                        Text(book.title,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                        if(book.author.isNotBlank())Text(book.author,maxLines=1,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        val percent=if(book.format=="epub")(book.locator.progression*100).roundToInt() else (book.position*100.0/book.chars.coerceAtLeast(1)).roundToInt()
                        Text("${book.format.uppercase()} · "+if(book.lastRead==0L)"未开始" else "已读 $percent%",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(books.isNotEmpty()&&shown.isEmpty())item{Text("没有找到这本书，右滑修改搜索条件")}
        }
    }
    }
    if(addOpen)AlertDialog(onDismissRequest={addOpen=false},title={Text("添加书籍")},text={Column{TextButton(onClick={addOpen=false;onImport()}){Text("选择书籍文件")};TextButton(onClick={addOpen=false;directory=true}){Text("从目录选择")}}},confirmButton={TextButton(onClick={addOpen=false}){Text("取消")}})
    detail?.let {book ->BookDetails(book,{title,author,description->onEdit(book.id,title,author,description);detail=null},{onRemove(book.id);detail=null},{detail=null})}
    if(directory)DirectoryImport(LocalImport.current,{directory=false})
    license?.let {LicenseText(it,{license=null})}
    if(about)AlertDialog(onDismissRequest={about=false},title={Text("栖页 · QiPage")},text={Column(Modifier.verticalScroll(rememberScrollState())){
        Text("版本 0.4.0-p4\n仅处理本机电子书，无账号、广告、云同步或网络权限。\n\n右滑书架：应用菜单\n长按书籍：详情与编辑\n左右滑动正文：翻页\n点击正文中央：排版\n\n组件许可：AndroidX / Compose / Room / DataStore（Apache 2.0）；Kotlin / kotlinx.coroutines（Apache 2.0）；Android 系统 WebView（系统提供）。\n\n当前 APK 使用项目本机测试签名，支持覆盖旧版安装。")
        listOf("NOTICE.txt","Apache-2.0.txt","Desugar-GPL2-Classpath.txt").forEach {file->TextButton(onClick={license=file}){Text(file)}}
    }},confirmButton={TextButton(onClick={about=false}){Text("关闭")}})
}
private val LocalImport=staticCompositionLocalOf<(List<String>,Int)->Unit> { {_,_->} }
@Composable private fun BookDetails(book:Book,save:(String,String,String)->Unit,remove:()->Unit,close:()->Unit){
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

@Composable private fun LicenseText(file:String,close:()->Unit){
    val context=LocalContext.current
    val license by produceState("",file){value=withContext(Dispatchers.IO){context.assets.open("licenses/$file").bufferedReader().use{it.readText()}}}
    androidx.compose.ui.window.Dialog(onDismissRequest=close,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){Column(Modifier.safeDrawingPadding().padding(20.dp)){
            Row{Text(file,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick=close){Text("关闭")}}
            Text(license,Modifier.weight(1f).verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodySmall)
        }}
    }
}





