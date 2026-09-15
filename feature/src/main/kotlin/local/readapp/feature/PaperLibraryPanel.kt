package local.readapp.feature

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.first
import local.readapp.core.*

@Composable internal fun PaperLibraryPanel(title:String,books:List<Book>,prefs:ReaderPreferences,update:(ReaderPreferences)->Unit,repository:BookRepository,onOpen:(String)->Unit,close:()->Unit){
    val context=LocalContext.current
    if(title=="主题与排版"){
        Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){ReaderSettings(prefs,null,update,{},close)}
        return
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize()){
            Column(Modifier.safeDrawingPadding().padding(24.dp)){
                Row(verticalAlignment=Alignment.CenterVertically){GlyphButton("back","返回",close);Text(title,Modifier.weight(1f),fontFamily=FontFamily.Serif,fontSize=23.sp)}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=28.dp)){
                    when(title){
                        "阅读统计"->{
                            val seconds=context.getSharedPreferences("paper-ui",0).getLong("readingSeconds",0)
                            Text("${seconds/3600} 小时 ${(seconds%3600)/60} 分钟",fontFamily=FontFamily.Serif,fontSize=30.sp)
                            Text("累计前台阅读时长",Modifier.padding(top=12.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(32.dp));Text("藏书 ${books.size} 本 · 已开始 ${books.count{it.lastRead>0}} 本")
                            Text("从此版本开始记录；历史阅读时长未作推算。",Modifier.padding(top=20.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        "书签与笔记"->{
                            val marks by produceState<List<Pair<Book,Bookmark>>>(emptyList(),books){value=books.flatMap{book->repository.bookmarks(book.id).first().map{book to it}}}
                            Text("书签",fontSize=20.sp,fontFamily=FontFamily.Serif)
                            if(marks.isEmpty())Text("在阅读页轻点书签，收藏此刻。",Modifier.padding(vertical=20.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                            marks.forEach{(book,mark)->TextButton(onClick={context.getSharedPreferences("paper-ui",0).edit().putString("pending-bookmark",mark.id).apply();onOpen(book.id);close()}){Column(Modifier.fillMaxWidth()){Text(mark.title.ifBlank{book.title});Text(book.title,fontSize=12.sp)}}}
                            Spacer(Modifier.height(24.dp));Text("笔记",fontSize=20.sp,fontFamily=FontFamily.Serif)
                            PaperNotes(books.map{it.id}.toSet(),onOpen)
                        }
                        "云端同步"->{PaperIcon("cloud",Modifier.size(40.dp),PaperGold);Spacer(Modifier.height(24.dp));Text("把安静，留在本机。",fontFamily=FontFamily.Serif,fontSize=24.sp);Text("云端同步尚未启用。书架、书签与笔记保存在此设备中，当前版本不连接网络。",Modifier.padding(top=20.dp))}
                        else->{
                            Text("栖页 · QiPage",fontFamily=FontFamily.Serif,fontSize=28.sp);Text("微光墨韵 / Subtle Paper",Modifier.padding(top=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(24.dp));Text("左右滑动翻页，轻点中央唤醒工具栏。\n长按书架封面进入编辑。\n阅读时长与笔记仅保存在本机。")
                            Spacer(Modifier.height(24.dp));Text("版本 0.5.0 · Android 11+")
                            var license by remember{mutableStateOf<String?>(null)}
                            listOf("NOTICE.txt","Apache-2.0.txt","Desugar-GPL2-Classpath.txt").forEach{file->TextButton(onClick={license=file}){Text(file)}}
                            license?.let{LicenseText(it){license=null}}
                        }
                    }
                }
            }
        }
    }
}
