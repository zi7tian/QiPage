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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
@Composable internal fun NavigationSheet(open:OpenBook,prefs:ReaderPreferences,marks:List<Bookmark>,navigate:(Locator)->Unit,add:(String)->Unit,remove:(String)->Unit,close:()->Unit) {
    var tab by remember { mutableIntStateOf(0) };var adding by remember { mutableStateOf(false) };var label by remember { mutableStateOf("") }
    val toc=open.epub?.toc ?: open.content?.let { selectedChapters(it,prefs) }.orEmpty()

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
                    0 -> TocTab(toc,open,navigate)
                    1 -> BookmarksTab(open,marks,add,remove,navigate,{label=it;adding=true})
                    else -> SearchTab(open,navigate)
                }
            }
        }
    }
    if(adding)AlertDialog(onDismissRequest={adding=false},title={Text("添加书签")},text={OutlinedTextField(label,{label=it.take(160)},singleLine=true,label={Text("书签名称")})},confirmButton={TextButton(onClick={add(label);adding=false}){Text("保存")}},dismissButton={TextButton(onClick={adding=false}){Text("取消")}})
}

@Composable private fun TocTab(toc:List<Chapter>,open:OpenBook,navigate:(Locator)->Unit) {
    if(toc.isEmpty()) {
        Text(
            if(open.book.format=="txt")"未识别到章节，可继续正常阅读。可在排版设置中调整章节规则。"
            else "这本书没有提供目录。可左右滑动继续阅读。",
            Modifier.padding(28.dp)
        )
        return
    }
    val state=rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
            itemsIndexed(toc){i,entry ->
                Text(entry.title,Modifier.fillMaxWidth().clickable{navigate(entry.locator)}.padding(start=(20+entry.depth.coerceAtMost(4)*16).dp,end=8.dp,top=16.dp,bottom=16.dp),maxLines=3,overflow=TextOverflow.Ellipsis)
                if(i<toc.lastIndex)HorizontalDivider()
            }
        }
        FastScrollbar(state,toc.size,Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical=8.dp))
    }
}

@Composable private fun BookmarksTab(open:OpenBook,marks:List<Bookmark>,add:(String)->Unit,remove:(String)->Unit,navigate:(Locator)->Unit,requestAdd:(String)->Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal=20.dp)) {
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(
            onClick={requestAdd("${open.book.title} · ${if(open.book.format=="epub")((open.book.locator.progression*100).toInt()).toString()+"%" else "位置 ${open.book.position}"}")},
            modifier=Modifier.fillMaxWidth()
        ){Text("添加当前书签")}
        if(marks.isEmpty())Text("还没有书签。留下一个位置，下次接着读。",Modifier.padding(vertical=28.dp))
        else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
            items(marks,key={it.id}){mark ->Row(Modifier.fillMaxWidth()) {
                Text(mark.title,Modifier.weight(1f).clickable{navigate(mark.locator)}.padding(vertical=18.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                TextButton(onClick={remove(mark.id)}){Text("删除")}
            }}
        }
    }
}

@Composable private fun SearchTab(open:OpenBook,navigate:(Locator)->Unit) {
    val scope=rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var scanned by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    val state=rememberLazyListState()

    fun start() {
        val q=query.trim()
        if(q.isEmpty())return
        job?.cancel()
        hits=emptyList();scanned=0;total=0;running=true;searched=true
        job=scope.launch {
            // Blocking zip/disk work stays on IO; only progress writes touch Compose state.
            try {
                withContext(Dispatchers.IO) {
                    val progress:suspend (SearchProgress)->Unit={p->
                        withContext(Dispatchers.Main){scanned=p.scanned;total=p.total;hits=p.hits}
                    }
                    val epub=open.epub
                    if(epub!=null)searchEpubContent(epub,q,onProgress=progress)
                    else open.content?.let { searchTextContent(it,q,onProgress=progress) }
                }
            } catch(e:CancellationException){ throw e }
            finally { running=false }
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
            Button(onClick={start()},enabled=query.isNotBlank()){Text("搜索")}
        }
        if(running) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("已扫描 $scanned / $total 节，找到 ${hits.size} 处",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={job?.cancel()}){Text("停止")}
        } else if(searched) {
            Spacer(Modifier.height(8.dp))
            Text(if(hits.isEmpty())"没有找到“${query.trim()}”。" else "找到 ${hits.size} 处" + if(hits.size>=200)"（只显示前 200 处）" else "",style=MaterialTheme.typography.bodySmall)
        }
        if(hits.isNotEmpty()) {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state=state,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(end=34.dp,bottom=24.dp)) {
                    itemsIndexed(hits){i,hit ->
                        Column(Modifier.fillMaxWidth().clickable{navigate(hit.locator)}.padding(top=14.dp,bottom=14.dp,end=8.dp)) {
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
