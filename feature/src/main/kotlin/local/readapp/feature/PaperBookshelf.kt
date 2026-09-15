package local.readapp.feature

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import local.readapp.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val PaperGold=Color(0xFFD4A373)
internal fun bookProgress(book:Book):Float=(if(book.format=="epub")book.locator.progression.toFloat() else book.position.toFloat()/book.chars.coerceAtLeast(1)).coerceIn(0f,1f)

@Composable internal fun GlyphButton(name:String,label:String,onClick:()->Unit) {
    IconButton(onClick=onClick,modifier=Modifier.semantics{contentDescription=label}){PaperIcon(name)}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun PaperBookshelf(books:List<Book>,busy:Boolean,onImport:()->Unit,onOpen:(String)->Unit,onRemove:(String)->Unit,onEdit:(String,String,String,String)->Unit,prefs:ReaderPreferences,update:(ReaderPreferences)->Unit,repository:BookRepository) {
    val context=LocalContext.current
    val memory=remember{context.getSharedPreferences("paper-ui",0)}
    var grid by rememberSaveable{mutableStateOf(memory.getBoolean("grid",true))}
    var query by rememberSaveable{mutableStateOf("")};var search by rememberSaveable{mutableStateOf(false)}
    var drawerTarget by remember{mutableFloatStateOf(0f)}
    var dragging by remember{mutableStateOf(false)}
    val animated by animateFloatAsState(drawerTarget,if(dragging)snap() else spring(stiffness=Spring.StiffnessLow),label="drawer")
    val drawer=animated.coerceIn(0f,1f)
    var detail by remember{mutableStateOf<Book?>(null)};var directory by remember{mutableStateOf(false)}
    var add by remember{mutableStateOf(false)};var panel by remember{mutableStateOf<String?>(null)}
    var selected by remember{mutableStateOf(setOf<String>())};var editing by remember{mutableStateOf(false)}
    var confirmRemove by remember{mutableStateOf(false)}
    var hero by remember{mutableStateOf<Book?>(null)}
    val coverBounds=remember{mutableMapOf<String,Rect>()}
    var heroBounds by remember{mutableStateOf<Rect?>(null)}
    var recentBounds by remember{mutableStateOf<Rect?>(null)}
    val scope=rememberCoroutineScope()
    fun open(book:Book,bounds:Rect?=coverBounds[book.id]){if(!busy){heroBounds=bounds;hero=book;scope.launch{delay(350);onOpen(book.id);delay(400);hero=null}}}
    BackHandler(drawerTarget>0 || editing){if(drawerTarget>0)drawerTarget=0f else {editing=false;selected=emptySet()}}
    val shown=books.filter{it.title.contains(query,true)||it.author.contains(query,true)}
    val recent=books.filter{it.lastRead>0}.maxByOrNull{it.lastRead}
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        val fullWidth=maxWidth;val fullHeight=maxHeight
        val widthPx=with(LocalDensity.current){maxWidth.toPx()}
        Column(Modifier.fillMaxHeight().fillMaxWidth(.75f).then(if(drawer<.01f)Modifier.clearAndSetSemantics{} else Modifier).graphicsLayer{translationX=-32*(1-drawer)}.safeDrawingPadding().padding(horizontal=28.dp,vertical=16.dp)) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.primary),contentAlignment=Alignment.Center){PaperIcon("book",Modifier.size(30.dp),MaterialTheme.colorScheme.surface)}
            Spacer(Modifier.height(16.dp));Text("栖页",fontFamily=FontFamily.Serif,fontSize=30.sp,letterSpacing=5.sp)
            Spacer(Modifier.height(10.dp));Text("已陪伴阅读 ${memory.getLong("readingSeconds",0)/60} 分钟",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf("book" to "我的书房","plus" to "本地导入","stats" to "阅读统计","bookmark" to "书签与笔记","cloud" to "云端同步","type" to "主题与排版","settings" to "关于").forEach{(icon,label)->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if(label=="我的书房")PaperGold.copy(alpha=.12f) else Color.Transparent).clickable{
                    drawerTarget=0f
                    when(label){"我的书房"->Unit;"本地导入"->add=true;else->panel=label}
                }.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){PaperIcon(icon);Spacer(Modifier.width(16.dp));Text(label,fontSize=14.sp)}
            }
            }
            Spacer(Modifier.height(12.dp));Text("Q I P A G E  /  0.5.0",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("留一点时间，给阅读。",Modifier.padding(top=8.dp),fontFamily=FontFamily.Serif,fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxSize().graphicsLayer{transformOrigin=TransformOrigin(0f,.5f);translationX=widthPx*.75f*drawer;scaleX=1-.15f*drawer;scaleY=1-.15f*drawer;shadowElevation=24*drawer;shape=RoundedCornerShape((24*drawer).dp);clip=true}
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(widthPx){detectHorizontalDragGestures(onDragStart={dragging=true},onHorizontalDrag={change,amount->if(drawerTarget>0 || amount>0){change.consume();drawerTarget=(drawerTarget+amount/(widthPx*.75f)).coerceIn(0f,1f)}},onDragEnd={dragging=false;drawerTarget=if(drawerTarget>.35f)1f else 0f},onDragCancel={dragging=false;drawerTarget=if(drawerTarget>.5f)1f else 0f})}){
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
                    GlyphButton(if(editing)"close" else "menu",if(editing)"退出编辑" else "打开应用菜单"){if(editing){editing=false;selected=emptySet()}else drawerTarget=1f}
                    Text(if(editing)"已选 ${selected.size} 本" else "我的书房",Modifier.weight(1f).offset(x=24.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontFamily=FontFamily.Serif,fontSize=19.sp,letterSpacing=3.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.72f))
                    GlyphButton("search","搜索书名"){search=!search;if(!search)query=""}
                    GlyphButton(if(grid)"list" else "grid","切换书架视图"){grid=!grid;memory.edit().putBoolean("grid",grid).apply()}
                }
                AnimatedVisibility(search){OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("寻找书名或作者")},shape=RoundedCornerShape(24.dp),modifier=Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,bottom=12.dp))}
                LazyVerticalGrid(columns=GridCells.Fixed(if(grid)3 else 1),contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=112.dp),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(26.dp),modifier=Modifier.weight(1f)) {
                    if(recent!=null&&!search&&!editing)item(span={GridItemSpan(maxLineSpan)}){
                        Column(Modifier.padding(top=10.dp,bottom=2.dp)){
                            Text("CONTINUE READING",fontSize=10.sp,letterSpacing=2.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            Surface(onClick={open(recent,recentBounds)},shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer){
                                Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){
                                    BookCover(recent,Modifier.width(76.dp).height(106.dp).onGloballyPositioned{recentBounds=it.boundsInRoot()});Spacer(Modifier.width(20.dp))
                                    Column(Modifier.weight(1f)){
                                        Text("当前在读",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(recent.title,Modifier.padding(vertical=8.dp),fontFamily=FontFamily.Serif,fontSize=20.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(memory.getString("chapter-${recent.id}","继续上次的阅读")?:"继续上次的阅读",fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(SimpleDateFormat("MM月dd日 HH:mm",Locale.CHINA).format(Date(recent.lastRead))+" · 已读 ${(bookProgress(recent)*100).toInt()}%",Modifier.padding(top=8.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    item(span={GridItemSpan(maxLineSpan)}){Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){Text("藏书",fontFamily=FontFamily.Serif,fontSize=22.sp,letterSpacing=3.sp);Spacer(Modifier.weight(1f));Text("${shown.size} 本 · 本地珍藏",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    if(shown.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Column(Modifier.fillMaxWidth().padding(vertical=64.dp),horizontalAlignment=Alignment.CenterHorizontally){PaperIcon("book",Modifier.size(48.dp),PaperGold);Spacer(Modifier.height(24.dp));Text(if(query.isEmpty())"让故事，在这里安放。" else "没有找到这本书",fontFamily=FontFamily.Serif,fontSize=20.sp);Text("导入本机 TXT / EPUB，开始一段安静的阅读",Modifier.padding(top=12.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    itemsIndexed(shown,key={_,b->b.id}){index,book->
                        var entered by remember{mutableStateOf(false)}
                        LaunchedEffect(Unit){delay((index.coerceAtMost(12)*30).toLong());entered=true}
                        val appear by animateFloatAsState(if(entered)1f else 0f,spring(dampingRatio=.85f,stiffness=220f),label="shelf entry")
                        val scale by animateFloatAsState(if(editing).95f else 1f,label="edit scale")
                        val pulse=rememberInfiniteTransition(label="edit breathe")
                        val angle by pulse.animateFloat(-.35f,.35f,infiniteRepeatable(tween(1300),RepeatMode.Reverse),label="edit angle")
                        val itemModifier=Modifier.graphicsLayer{alpha=appear;translationY=(1-appear)*28;scaleX=scale;scaleY=scale;rotationZ=if(editing)angle else 0f}.combinedClickable(enabled=!busy,onClick={if(editing)selected=if(book.id in selected)selected-book.id else selected+book.id else open(book)},onLongClick={editing=true;selected=setOf(book.id)},onLongClickLabel="编辑书架")
                        if(grid)Column(itemModifier){
                            Box{BookCover(book,Modifier.fillMaxWidth().aspectRatio(.69f).onGloballyPositioned{coverBounds[book.id]=it.boundsInRoot()});if(editing)Checkbox(book.id in selected,{selected=if(it)selected+book.id else selected-book.id},Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surface.copy(alpha=.86f),RoundedCornerShape(12.dp)))}
                            Text(book.title,Modifier.padding(top=12.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=20.sp)
                            Text(book.author.ifBlank{"佚名"},Modifier.padding(top=4.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }else Row(itemModifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            BookCover(book,Modifier.width(66.dp).height(94.dp).onGloballyPositioned{coverBounds[book.id]=it.boundsInRoot()});Spacer(Modifier.width(20.dp));Column(Modifier.weight(1f)){Text(book.title,fontFamily=FontFamily.Serif,fontSize=19.sp,maxLines=2);Text(book.author.ifBlank{"佚名"},Modifier.padding(top=7.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("${(bookProgress(book)*100).toInt()}% · ${book.format.uppercase()}",Modifier.padding(top=12.dp),fontSize=10.sp,color=PaperGold)}
                            if(editing)Checkbox(book.id in selected,{selected=if(it)selected+book.id else selected-book.id})
                        }
                    }
                }
            }
            if(editing)Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),shape=RoundedCornerShape(28.dp),shadowElevation=8.dp){Row(Modifier.padding(8.dp)){
                TextButton(onClick={selected=books.map{it.id}.toSet()}){Text("全选")}
                TextButton(enabled=selected.size==1,onClick={detail=books.firstOrNull{it.id in selected}}){Text("编辑详情")}
                TextButton(enabled=selected.isNotEmpty(),onClick={confirmRemove=true}){Text("移出书架")}
            }} else ExtendedFloatingActionButton(onClick={add=true},modifier=Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp),containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(28.dp),icon={PaperIcon("plus",color=MaterialTheme.colorScheme.surface)},text={Text("导入",letterSpacing=2.sp)})
            if(drawer>.01f)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.08f*drawer)).clickable{drawerTarget=0f})
        }
        hero?.let{book->
            var expanded by remember{mutableStateOf(false)};LaunchedEffect(Unit){expanded=true}
            val f by animateFloatAsState(if(expanded)1f else 0f,tween(350),label="cover opening")
            val density=LocalDensity.current
            val bounds=heroBounds
            val startWidth=with(density){(bounds?.width?:widthPx*.35f).toDp()};val startHeight=with(density){(bounds?.height?:widthPx*.5f).toDp()}
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha=f))){BookCover(book,Modifier.offset{IntOffset(((bounds?.left?:0f)*(1-f)).toInt(),((bounds?.top?:0f)*(1-f)).toInt())}.width(startWidth+(fullWidth-startWidth)*f).height(startHeight+(fullHeight-startHeight)*f).graphicsLayer{alpha=1-f*.65f})}
        }
    }
    if(add)AlertDialog(onDismissRequest={add=false},title={Text("把故事带进书房",fontFamily=FontFamily.Serif)},text={Column{TextButton(onClick={add=false;onImport()}){PaperIcon("book");Spacer(Modifier.width(12.dp));Text("选择书籍文件")};TextButton(onClick={add=false;directory=true}){PaperIcon("plus");Spacer(Modifier.width(12.dp));Text("从目录选择")}}},confirmButton={TextButton(onClick={add=false}){Text("取消")}})
    if(directory)DirectoryImport(LocalImport.current){directory=false}
    detail?.let{book->BookDetails(book,{title,author,description->onEdit(book.id,title,author,description);detail=null},{onRemove(book.id);detail=null;selected=selected-book.id},{detail=null})}
    if(confirmRemove)AlertDialog(onDismissRequest={confirmRemove=false},title={Text("移出 ${selected.size} 本书？")},text={Text("移除应用内阅读记录与副本，保留手机中的原文件。")},confirmButton={TextButton(onClick={selected.forEach(onRemove);selected=emptySet();editing=false;confirmRemove=false}){Text("移出")}},dismissButton={TextButton(onClick={confirmRemove=false}){Text("取消")}})
    panel?.let{PaperLibraryPanel(it,books,prefs,update,repository,onOpen){panel=null}}
}

@Composable internal fun BookCover(book:Book,modifier:Modifier=Modifier) {
    val cover by produceState<ImageBitmap?>(null,book.cover){value=withContext(Dispatchers.IO){if(book.cover.isBlank())null else android.graphics.BitmapFactory.decodeFile(book.cover)?.asImageBitmap()}}
    val palette=listOf(Color(0xFF465652),Color(0xFFACA18B),Color(0xFF696C78),Color(0xFF99765F),Color(0xFFB2AEA0),Color(0xFF566873))
    val color=palette[(book.id.hashCode() and Int.MAX_VALUE)%palette.size]
    BoxWithConstraints(modifier.shadow(5.dp,RoundedCornerShape(4.dp),ambientColor=Color.Black.copy(alpha=.06f),spotColor=Color.Black.copy(alpha=.06f)).clip(RoundedCornerShape(4.dp)).background(color)){
        val compact=maxWidth<90.dp || maxHeight<125.dp
        if(cover!=null)Image(cover!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
        else Column(Modifier.fillMaxSize().padding(horizontal=if(compact)8.dp else 12.dp,vertical=if(compact)10.dp else 18.dp),verticalArrangement=Arrangement.SpaceBetween){
            Text("栖 页 藏 书",fontSize=if(compact)6.sp else 7.sp,letterSpacing=if(compact).5.sp else 1.sp,color=Color.White.copy(alpha=.6f))
            Text(book.title,fontFamily=FontFamily.Serif,fontSize=if(compact)13.sp else 19.sp,lineHeight=if(compact)18.sp else 27.sp,color=Color(0xFFF8F6F0),maxLines=4,overflow=TextOverflow.Ellipsis)
            Text(book.author.ifBlank{"QIPAGE"},fontSize=9.sp,color=Color.White.copy(alpha=.65f),maxLines=1)
        }
        Box(Modifier.fillMaxHeight().width(7.dp).background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha=.13f),Color.Transparent,Color.White.copy(alpha=.08f)))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Color.Black.copy(alpha=.08f))){Box(Modifier.fillMaxWidth(bookProgress(book)).fillMaxHeight().background(PaperGold))}
    }
}
