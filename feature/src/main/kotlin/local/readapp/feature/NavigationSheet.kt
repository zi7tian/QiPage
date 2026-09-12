package local.readapp.feature
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import local.readapp.core.*
import kotlin.math.roundToInt

/**
 * Full-screen directory / bookmarks / search panel.
 *
 * It covers the whole screen rather than sitting in a bottom sheet, because a
 * long table of contents needs the vertical space, and it carries a draggable
 * fast-scroll bar so a thousand-chapter book can be crossed in one gesture.
 */
@Composable internal fun NavigationSheet(
    open:OpenBook,
    prefs:ReaderPreferences,
    marks:List<Bookmark>,
    navigate:(Locator,Int)->Unit,
    add:(String)->Unit,
    remove:(String)->Unit,
    close:()->Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val toc=open.epub?.toc ?: open.content?.let { selectedChapters(it,prefs) }.orEmpty()
    val current=remember(open.book.id,open.book.locator,open.book.position,toc){currentChapterIndex(open,toc)}

    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(start=12.dp,end=4.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={tab=0}){Text(if(tab==0)"✓ 目录" else "目录")}
                    if(prefs.bookmarksEnabled)TextButton(onClick={tab=1}){Text(if(tab==1)"✓ 书签" else "书签")}
                    TextButton(onClick={tab=2}){Text(if(tab==2)"✓ 搜索" else "搜索")}
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick=close){Text("关闭")}
                }
                HorizontalDivider()
                when(tab) {
                    0 -> TocTab(toc,open,current,navigate)
                    1 -> BookmarksTab(open,marks,add,remove,navigate)
                    else -> SearchTab(open,navigate)
                }
            }
        }
    }
}

/** Which table-of-contents entry the reader is currently inside. */
internal fun currentChapterIndex(open:OpenBook,toc:List<Chapter>):Int {
    if(toc.isEmpty())return -1
    val epub=open.epub
    if(epub!=null){
        val href=open.book.locator.href
        val offset=open.book.locator.offset
        // Exact spine item first; several entries can share one file, so then pick
        // the last heading at or before the current offset.
        var best=-1
        for(i in toc.indices){
            val entry=toc[i]
            if(entry.locator.href!=href)continue
            if(best<0)best=i
            if(entry.locator.offset<=offset)best=i
        }
        return best
    }
    val position=open.book.position
    var best=0
    for(i in toc.indices){ if(toc[i].locator.offset<=position)best=i else break }
    return best
}

@Composable private fun TocTab(toc:List<Chapter>,open:OpenBook,current:Int,navigate:(Locator,Int)->Unit) {
    if(toc.isEmpty()) {
        Text(
            if(open.book.format=="txt")"未识别到章节，可继续正常阅读。可在排版设置中调整章节规则。"
            else "这本书没有提供目录。可左右滑动继续阅读。",
            Modifier.padding(28.dp)
        )
        return
    }
    // Open already scrolled to where the reader is, so a long book never starts
    // the directory back at chapter one.
    val state=rememberLazyListState(initialFirstVisibleItemIndex=current.coerceAtLeast(0))
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
            itemsIndexed(toc){i,entry ->
                val here=i==current
                Text(
                    entry.title,
                    Modifier.fillMaxWidth().clickable{navigate(entry.locator,0)}.padding(start=(20+entry.depth.coerceAtMost(4)*16).dp,end=8.dp,top=16.dp,bottom=16.dp),
                    maxLines=3,
                    overflow=TextOverflow.Ellipsis,
                    color=if(here)MaterialTheme.colorScheme.error else Color.Unspecified,
                    fontWeight=if(here)FontWeight.SemiBold else null,
                )
                if(i<toc.lastIndex)HorizontalDivider()
            }
        }
        FastScrollbar(state,toc.size,Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical=8.dp))
    }
}

@Composable private fun BookmarksTab(open:OpenBook,marks:List<Bookmark>,add:(String)->Unit,remove:(String)->Unit,navigate:(Locator,Int)->Unit) {
    var adding by remember{mutableStateOf(false)};var label by remember{mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick={label="";adding=true},modifier=Modifier.fillMaxWidth()){Text("添加当前书签")}
        if(marks.isEmpty())Text("还没有书签。留下一个位置，下次接着读。",Modifier.padding(vertical=28.dp))
        else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
            items(marks,key={it.id}){mark ->Row(Modifier.fillMaxWidth()) {
                Text(mark.title.ifBlank{"（未命名书签）"},Modifier.weight(1f).clickable{navigate(mark.locator,0)}.padding(vertical=18.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={remove(mark.id)}){Text("删除")}
            }}
        }
    }
    if(adding)AlertDialog(
        onDismissRequest={adding=false},
        title={Text("添加书签")},
        text={OutlinedTextField(
            label,{label=it.take(160)},
            singleLine=true,
            // No floating label: with one, Material 3 paints the label instead of the
            // placeholder until the field is focused, and this hint must be visible
            // the moment the dialog opens.
            placeholder={Text("输入书签名称（可留空）",color=MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.65f))},
        )},
        confirmButton={TextButton(onClick={add(label);adding=false;label=""}){Text("保存")}},
        dismissButton={TextButton(onClick={adding=false;label=""}){Text("取消")}},
    )
}

/**
 * Search tab.
 *
 * The first query of a session builds a [BookSearchIndex] for the whole book
 * (one sanitising pass, with progress and cancellation). Every later query is a
 * single linear scan over the flattened text, which is what keeps a phrase
 * lookup on a multi-million-character novel comfortably interactive.
 */
@Composable private fun SearchTab(open:OpenBook,navigate:(Locator,Int)->Unit) {
    val scope=rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var building by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var scanned by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }
    var index by remember { mutableStateOf(SearchIndexCache.get(open.book.id)) }
    val state=rememberLazyListState()

    fun start() {
        val q=query.trim()
        if(q.isEmpty())return
        job?.cancel()
        hits=emptyList();scanned=0;total=0;searched=true
        job=scope.launch {
            try {
                var ready=index
                if(ready==null){
                    building=true
                    ready=withContext(Dispatchers.IO){
                        val onProgress:suspend (SearchProgress)->Unit={p->
                            withContext(Dispatchers.Main){scanned=p.scanned;total=p.total}
                        }
                        val epub=open.epub
                        if(epub!=null)BookSearchIndex.buildEpub(epub,onProgress)
                        else BookSearchIndex.buildText(checkNotNull(open.content),onProgress)
                    }
                    if(!isActive)return@launch
                    index=ready;SearchIndexCache.put(open.book.id,ready);building=false
                }
                running=true
                val from=ready
                val found=withContext(Dispatchers.Default){
                    val started=System.nanoTime()
                    val result=from.find(q,200)
                    result to (System.nanoTime()-started)/1_000_000
                }
                hits=found.first;elapsed=found.second
            } catch(e:CancellationException){ throw e }
            finally { building=false;running=false }
        }
    }
    DisposableEffect(Unit){ onDispose { job?.cancel() } }

    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(
                query,{query=it},
                modifier=Modifier.weight(1f),
                label={Text("搜索全书文字")},
                singleLine=true,
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),
                keyboardActions=KeyboardActions(onSearch={start()})
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick={start()},enabled=query.isNotBlank()&&!building){Text("搜索")}
        }
        if(building) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("首次搜索正在建立索引 $scanned / $total 节，完成后查询会很快",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={job?.cancel()}){Text("停止")}
        } else if(running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if(searched) {
            Spacer(Modifier.height(8.dp))
            Text(
                if(hits.isEmpty())"没有找到“${query.trim()}”。"
                else "找到 ${hits.size} 处" + if(hits.size>=200)"（只显示前 200 处）" else "" + "，用时 ${elapsed}ms",
                style=MaterialTheme.typography.bodySmall
            )
        }
        if(hits.isNotEmpty()) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
                    itemsIndexed(hits){i,hit ->
                        Column(Modifier.fillMaxWidth().clickable{navigate(hit.locator,hit.length)}.padding(top=14.dp,bottom=14.dp,end=8.dp)) {
                            Text(hit.chapter,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text(hit.snippet,maxLines=3,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                        }
                        if(i<hits.lastIndex)HorizontalDivider()
                    }
                }
                FastScrollbar(state,hits.size,Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical=8.dp))
            }
        }
    }
}

/**
 * Keeps the flattened index alive between searches and across re-opened panels.
 *
 * A two-million-character book costs a few megabytes, so only the most recent
 * books are kept.
 */
internal object SearchIndexCache {
    private const val KEEP=2
    private val entries=LinkedHashMap<String,BookSearchIndex>(4,.75f,true)
    @Synchronized fun get(bookId:String):BookSearchIndex?=entries[bookId]
    @Synchronized fun put(bookId:String,index:BookSearchIndex){
        entries[bookId]=index
        while(entries.size>KEEP)entries.remove(entries.keys.first())
    }
}

/**
 * Draggable fast-scroll bar for a [LazyListState].
 *
 * The touch target is deliberately wider than the 3dp thumb so it stays easy to
 * grab, and dragging jumps proportionally through the list instead of nudging it.
 */
@Composable private fun FastScrollbar(state:LazyListState,count:Int,modifier:Modifier=Modifier) {
    if(count<=1)return
    val density=LocalDensity.current
    val scope=rememberCoroutineScope()
    var trackHeight by remember { mutableIntStateOf(0) }
    val thumbMin=with(density){40.dp.toPx()}
    val layout=state.layoutInfo
    val visible=layout.visibleItemsInfo.size.coerceAtLeast(1)
    if(layout.totalItemsCount<=visible)return

    val thumbHeight=(trackHeight.toFloat()*visible/count.coerceAtLeast(1)).coerceIn(thumbMin,trackHeight.toFloat().coerceAtLeast(thumbMin))
    val maxOffset=(trackHeight-thumbHeight).coerceAtLeast(0f)
    val thumbTop=if(count<=1)0f else maxOffset*state.firstVisibleItemIndex/(count-1).toFloat()

    fun seekTo(y:Float) {
        if(maxOffset<=0f)return
        val ratio=((y-thumbHeight/2f)/maxOffset).coerceIn(0f,1f)
        scope.launch { state.scrollToItem((ratio*(count-1)).roundToInt().coerceIn(0,count-1)) }
    }

    Box(
        modifier
            .width(30.dp)
            .semantics{contentDescription="快速滚动条"}
            .onSizeChanged{trackHeight=it.height}
            .pointerInput(count,trackHeight,thumbHeight){
                detectVerticalDragGestures(
                    onDragStart={offset:Offset->seekTo(offset.y)},
                    onVerticalDrag={change,_->change.consume();seekTo(change.position.y)}
                )
            },
        contentAlignment=Alignment.TopEnd
    ) {
        Box(
            Modifier
                .padding(end=6.dp)
                .offset(y=with(density){thumbTop.toDp()})
                .width(3.dp)
                .height(with(density){thumbHeight.toDp()})
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}
