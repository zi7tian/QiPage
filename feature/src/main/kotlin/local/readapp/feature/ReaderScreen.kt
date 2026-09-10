package local.readapp.feature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import local.readapp.core.*
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private data class TxtFrame(val page:TextPage,val chapter:Int,val number:Int,val count:Int,val bitmap:Bitmap)
@Composable internal fun ReaderScreen(open:OpenBook,prefs:ReaderPreferences,jump:JumpRequest?,onPosition:(String,Long)->Unit,onBack:()->Unit,onSettings:()->Unit,onNavigation:()->Unit,onJumpHandled:()->Unit){
    val content=checkNotNull(open.content);val context=LocalContext.current;val density=LocalDensity.current;val scope=rememberCoroutineScope()
    val latestSettings by rememberUpdatedState(onSettings);val latestPosition by rememberUpdatedState(onPosition)
    val bg=MaterialTheme.colorScheme.surface.toArgb();val ink=MaterialTheme.colorScheme.onSurface.toArgb();val dark=readerIsDark(prefs)
    val font by produceState(Typeface.DEFAULT,prefs.fontFile){value=withContext(Dispatchers.IO){runCatching {ReadingAssets.file(context,prefs.fontFile)?.let(Typeface::createFromFile)}.getOrNull()?:Typeface.DEFAULT}}
    val backdrop by produceState<Bitmap?>(null,prefs.backgroundFile){value=withContext(Dispatchers.IO){ReadingAssets.file(context,prefs.backgroundFile)?.let{BitmapFactory.decodeFile(it.path)}}}
    val toc=remember(content,prefs.chapterRule,prefs.chapterUnits,prefs.chapterSeparator,prefs.chapterTitleLimit,prefs.excludedTitles){selectedChapters(content,prefs)}
    key(content){
        var offset by remember {mutableLongStateOf(open.book.position.coerceIn(0,(content.length-1).coerceAtLeast(0)))}
        var frame by remember {mutableStateOf<TxtFrame?>(null)}
        var progressOpen by remember {mutableStateOf(false)};var requestedPage by remember {mutableFloatStateOf(1f)}
        var wholeJump by remember {mutableStateOf(false)};var fraction by remember {mutableFloatStateOf(0f)}
        var failure by remember {mutableStateOf<String?>(null)}
        var pager by remember {mutableStateOf<CoverPageView?>(null)}
        LaunchedEffect(jump){jump?.let {offset=it.locator.offset.coerceIn(0,(content.length-1).coerceAtLeast(0));onJumpHandled()}}
        Column(Modifier.fillMaxSize()){
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                val w=with(density){maxWidth.roundToPx()}.coerceAtLeast(1);val h=with(density){maxHeight.roundToPx()}.coerceAtLeast(1)
                val margin=with(density){prefs.pageMargin.dp.roundToPx()};val vertical=with(density){4.dp.roundToPx()}
                val paginator=remember(content,toc,font,w,h,margin,density.density,density.fontScale,prefs.fontSize,prefs.lineSpacing,prefs.paragraphSpacing){TxtPaginator(content,toc,prefs,font,(w-2*margin).coerceAtLeast(1),(h-2*vertical).coerceAtLeast(1),density.density,density.fontScale,File(context.cacheDir,"pagination/${open.book.id}"))}
                val dim=if(dark)prefs.nightImageDim else 0f
                val stamp=listOf(paginator,bg,ink,backdrop,dim)
                var shownStamp by remember {mutableStateOf<List<Any?>?>(null)}
                var pending by remember {mutableStateOf<TxtFrame?>(null)};var prepareJob by remember {mutableStateOf<Job?>(null)}
                suspend fun render(chapter:Int,number:Int):TxtFrame=withContext(Dispatchers.IO){
                    val pages=paginator.index(chapter);val index=number.coerceIn(0,pages.lastIndex)
                    val page=paginator.page(pages[index],chapter,ink)
                    TxtFrame(page,chapter,index+1,pages.size,pageBitmap(page,w,h,margin,bg,backdrop,dim,vertical))
                }
                LaunchedEffect(stamp,offset){
                    if(frame?.page?.start==offset && shownStamp==stamp)return@LaunchedEffect
                    pager?.abort();prepareJob?.cancel();pending=null;failure=null
                    try{
                        // Keep the previous finished frame while a new layout is being prepared.
                        delay(45)
                        val chapter=paginator.chapter(offset)
                        val pages=withContext(Dispatchers.IO){paginator.index(chapter)}
                        val index=pages.binarySearch(offset).let {if(it>=0)it else (-it-2).coerceAtLeast(0)}
                        val result=render(chapter,index);frame=result;shownStamp=stamp;latestPosition(open.book.id,result.page.start)
                    }catch(e:Exception){if(e is CancellationException)throw e;failure="正文分页失败，请返回书架重试。"}
                }
                AndroidView(modifier=Modifier.fillMaxSize(),factory={CoverPageView(it).also {view->pager=view}},update={view ->
                    view.settings={latestSettings()};view.edgeTap=prefs.tapToTurn
                    if(frame!=null && view.tag!==frame){view.tag=frame;view.show(frame!!.bitmap,frame!!.page.layout.text.toString())}
                    view.prepare={next,done ->
                        val current=frame
                        if(current==null || shownStamp!=stamp)done(null)
                        else {
                            prepareJob?.cancel();prepareJob=scope.launch {
                                try{
                                    var chapter=current.chapter;var number=current.number-1+(if(next)1 else -1)
                                    if(number>=current.count){chapter++;number=0}
                                    if(number<0){chapter--;number=Int.MAX_VALUE}
                                    if(chapter !in paginator.starts.indices){done(null);return@launch}
                                    val result=render(chapter,number);pending=result;done(result.bitmap)
                                }catch(e:Exception){if(e is CancellationException)throw e;done(null)}
                            }
                        }
                    }
                    view.commit={pending?.let {result->frame=result;pending=null;shownStamp=stamp;offset=result.page.start;latestPosition(open.book.id,result.page.start)}}
                    view.cancelled={prepareJob?.cancel();pending=null}
                },onRelease={it.abort();prepareJob?.cancel();pager=null})
                failure?.let {Text(it,Modifier.padding(20.dp))}
                if(progressOpen)AlertDialog(onDismissRequest={progressOpen=false},title={Text("章节进度")},text={Column{
                    Text("${requestedPage.roundToInt()} / ${frame?.count?:1}")
                    Slider(requestedPage,{requestedPage=it},valueRange=1f..(frame?.count?:1).coerceAtLeast(2).toFloat(),enabled=(frame?.count?:1)>1)
                    TextButton(onClick={wholeJump=!wholeJump}){Text("整书定位")}
                    if(wholeJump){Text(String.format(Locale.ROOT,"%.1f%%",fraction*100));Slider(fraction,{fraction=it})}
                }},confirmButton={TextButton(onClick={scope.launch {
                    if(wholeJump)offset=(fraction*(content.length-1).coerceAtLeast(0)).toLong()
                    else frame?.let {val pages=withContext(Dispatchers.IO){paginator.index(it.chapter)};offset=pages[(requestedPage.roundToInt()-1).coerceIn(0,pages.lastIndex)]}
                    progressOpen=false
                }}){Text("跳转")}},dismissButton={TextButton(onClick={progressOpen=false}){Text("取消")}})
            }
            ReaderFooter(frame?.number?:0,frame?.count?:0,((frame?.page?.end?:offset)*100.0/content.length.coerceAtLeast(1)),prefs,onNavigation,{requestedPage=(frame?.number?:1).toFloat();fraction=(offset.toDouble()/content.length.coerceAtLeast(1)).toFloat();wholeJump=false;progressOpen=true})
        }
    }
}
internal fun progressLabel(page:Int,count:Int,whole:Double)=if(count>0)String.format(Locale.ROOT,"%d/%d %.1f%%",page.coerceIn(1,count),count,whole.coerceIn(0.0,100.0)) else "—/— "+String.format(Locale.ROOT,"%.1f%%",whole.coerceIn(0.0,100.0))
@Composable internal fun ReaderFooter(page:Int,count:Int,whole:Double,prefs:ReaderPreferences,navigation:()->Unit,progress:()->Unit){
    Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        if(prefs.navigationFirst)TextButton(onClick=navigation){Text(if(prefs.bookmarksEnabled)"目录 / 书签" else "目录")}
        if(prefs.navigationFirst)Spacer(Modifier.weight(1f))
        TextButton(onClick=progress,contentPadding=PaddingValues(horizontal=4.dp)){Text(progressLabel(page,count,whole),style=MaterialTheme.typography.labelMedium)}
        if(!prefs.navigationFirst)Spacer(Modifier.weight(1f))
        if(!prefs.navigationFirst)TextButton(onClick=navigation){Text(if(prefs.bookmarksEnabled)"目录 / 书签" else "目录")}
    }
}

