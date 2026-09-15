package local.readapp.feature

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import local.readapp.core.*

internal class ReaderChrome {
    var visible by mutableStateOf(false)
    var page by mutableIntStateOf(1)
    var count by mutableIntStateOf(1)
    var chapter by mutableStateOf("")
    var seek:(Int)->Unit={}
    var progress:(()->Unit)?=null
    var select:(()->Unit)?=null
}
internal val LocalReaderChrome=staticCompositionLocalOf<ReaderChrome?>{null}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PaperReaderChrome(chrome:ReaderChrome,open:OpenBook,prefs:ReaderPreferences,update:(ReaderPreferences)->Unit,back:()->Unit,navigation:()->Unit,settings:()->Unit,bookmark:()->Unit,notes:()->Unit) {
    var brightness by remember{mutableStateOf(false)}
    BackHandler(chrome.visible){chrome.visible=false}
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(chrome.visible,enter=fadeIn()+slideInVertically{ -it/3 },exit=fadeOut(),modifier=Modifier.align(Alignment.TopCenter)) {
            ReaderPlate(true){
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                    GlyphButton("back","返回书架",back)
                    Text(open.book.title,Modifier.weight(1f).padding(horizontal=12.dp),fontFamily=FontFamily.Serif,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(prefs.bookmarksEnabled)GlyphButton("bookmark","添加书签",bookmark)
                    GlyphButton("more","阅读笔记",notes)
                }
            }
        }
        AnimatedVisibility(chrome.visible,enter=fadeIn()+slideInVertically{it/3},exit=fadeOut(),modifier=Modifier.align(Alignment.BottomCenter)) {
            ReaderPlate(false){
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=24.dp,vertical=16.dp)){
                    var dragging by remember{mutableStateOf(false)};var target by remember(chrome.page){mutableFloatStateOf(chrome.page.toFloat())}
                    Row(verticalAlignment=Alignment.CenterVertically){Text(chrome.chapter,Modifier.weight(1f),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);TextButton(onClick={chrome.progress?.invoke()}){Text("${if(dragging)target.toInt() else chrome.page} / ${chrome.count}",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                    Box(Modifier.fillMaxWidth()) {
                        Slider(value=target.coerceIn(1f,chrome.count.coerceAtLeast(2).toFloat()),onValueChange={dragging=true;target=it},onValueChangeFinished={chrome.seek(target.toInt());dragging=false},valueRange=1f..chrome.count.coerceAtLeast(2).toFloat(),enabled=chrome.count>1,
                            thumb={Box(Modifier.size(12.dp).background(PaperGold,androidx.compose.foundation.shape.CircleShape))},
                            track={Box(Modifier.fillMaxWidth().height(3.dp).background(PaperGold.copy(alpha=.18f),RoundedCornerShape(2.dp))){Box(Modifier.fillMaxWidth(((target-1)/((chrome.count-1).coerceAtLeast(1))).coerceIn(0f,1f)).fillMaxHeight().background(PaperGold,RoundedCornerShape(2.dp)))}})
                        if(dragging)Surface(Modifier.align(Alignment.TopCenter).offset(y=(-32).dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer,shadowElevation=4.dp){Text("${target.toInt()} 页 · ${chrome.chapter}",Modifier.padding(horizontal=12.dp,vertical=8.dp),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        listOf(Triple("list","目录",navigation),Triple("sun","亮度 / 夜间",{brightness=true}),Triple("type","阅读设置",settings),Triple("bookmark","划词笔记",{chrome.select?.invoke();Unit})).let{if(prefs.navigationFirst)it else it.reversed()}.forEach{(icon,title,action)->
                            Column(Modifier.clipForTap().clickable(onClick=action).padding(horizontal=10.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally){PaperIcon(icon);Text(title,Modifier.padding(top=8.dp),fontSize=11.sp)}
                        }
                    }
                }
            }
        }
    }
    if(brightness){
        val window=(LocalContext.current as Activity).window
        var level by remember{mutableFloatStateOf(window.attributes.screenBrightness.takeIf{it>=0}?:.5f)}
        AlertDialog(onDismissRequest={brightness=false},title={Text("一盏适合此刻的光",fontFamily=FontFamily.Serif)},text={Column{
            Text("屏幕亮度");Slider(level,{level=it;window.attributes=window.attributes.apply{screenBrightness=it.coerceIn(.02f,1f)}})
            TextButton(onClick={window.attributes=window.attributes.apply{screenBrightness=-1f}}){Text("跟随系统亮度")}
            Row{listOf("paper" to "纸色","light" to "日间","dark" to "夜间").forEach{(key,label)->TextButton(onClick={update(prefs.copy(theme=key))}){Text(label)}}}
        }},confirmButton={TextButton(onClick={brightness=false}){Text("完成")}})
    }
}
private fun Modifier.clipForTap()=this.then(Modifier.background(androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(12.dp)))

@Composable private fun ReaderPlate(top:Boolean,content:@Composable ()->Unit){
    val shape=if(top)RoundedCornerShape(bottomStart=20.dp,bottomEnd=20.dp) else RoundedCornerShape(topStart=24.dp,topEnd=24.dp)
    Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface,shape=shape){content()}
}
