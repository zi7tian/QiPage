package local.readapp.feature

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.readapp.core.Locator
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SelectionPage(val text:String,val locator:Locator,val offsets:LongArray?=null,val selected:Boolean=false)
internal val LocalSelectPage=staticCompositionLocalOf<(SelectionPage)->Unit>{{}}
internal fun encodeLocation(l:Locator)=JSONObject().put("format",l.format).put("href",l.href).put("offset",l.offset).put("anchor",l.anchor).put("progress",l.progression)
internal fun decodeLocation(j:JSONObject)=Locator(format=j.optString("format","txt"),href=j.optString("href"),offset=j.optLong("offset"),anchor=j.optString("anchor"),progression=j.optDouble("progress",0.0))
internal fun savedHighlights(context:android.content.Context,bookId:String,href:String=""):List<Highlight>{
    val entries=runCatching{JSONArray(context.getSharedPreferences("paper-notes",0).getString("entries","[]"))}.getOrDefault(JSONArray())
    return (0 until entries.length()).map{entries.getJSONObject(it)}.filter{it.optString("book")==bookId && it.getJSONObject("locator").optString("href")==href}.map{Highlight(it.getJSONObject("locator").optLong("offset"),it.optInt("length",it.optString("quote").length))}
}

@Composable internal fun SelectionNotebook(page:SelectionPage,bookId:String,bookTitle:String,saved:(Locator,Int)->Unit,close:()->Unit){
    val context=LocalContext.current;val store=remember{context.getSharedPreferences("paper-notes",0)}
    var selection by remember{mutableStateOf(if(page.selected)page.text else "")};var start by remember{mutableIntStateOf(0)};var note by remember{mutableStateOf("")}
    var textView by remember{mutableStateOf<TextView?>(null)}
    val ink=MaterialTheme.colorScheme.onSurface.toArgb()
    fun capture(){textView?.let{view->val a=minOf(view.selectionStart,view.selectionEnd).coerceAtLeast(0);val b=maxOf(view.selectionStart,view.selectionEnd).coerceAtLeast(a);if(b>a){selection=page.text.substring(a,b);start=a}}}
    fun save(){
        capture();if(selection.isBlank())return
        val range=sourceSelection(page.offsets?:longArrayOf(),start,start+selection.length,page.locator.offset)
        val locator=page.locator.copy(offset=range.start,anchor="")
        val length=range.length
        val entries=runCatching{JSONArray(store.getString("entries","[]"))}.getOrDefault(JSONArray())
        entries.put(JSONObject().put("id",UUID.randomUUID().toString()).put("book",bookId).put("title",bookTitle).put("quote",selection).put("length",length).put("note",note).put("locator",encodeLocation(locator)).put("created",System.currentTimeMillis()))
        store.edit().putString("entries",entries.toString()).apply()
        saved(locator,length);close()
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f),shape=androidx.compose.foundation.shape.RoundedCornerShape(24.dp)){
            Column(Modifier.padding(24.dp)){
                Row{Text("摘下一段微光",Modifier.weight(1f),fontSize=21.sp);TextButton(onClick=close){Text("关闭")}}
                Text("长按下方文字，拖动选区后高亮或写下笔记。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical=16.dp)){
                    AndroidView(factory={ctx->object:TextView(ctx){
                        override fun onSelectionChanged(a:Int,b:Int){
                            super.onSelectionChanged(a,b)
                            val from=minOf(a,b);val to=maxOf(a,b)
                            if(from>=0 && to>from && to<=page.text.length){start=from;selection=page.text.substring(from,to)}
                        }
                    }.apply{
                        setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);text=page.text;textSize=18f;setTextColor(ink);setLineSpacing(0f,1.8f);setTextIsSelectable(true)
                        customSelectionActionModeCallback=object:ActionMode.Callback{
                            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean{menu.add(0,9001,0,"高亮 / 笔记");return true}
                            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
                            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean{if(item.itemId!=9001)return false;capture();mode.finish();return true}
                            override fun onDestroyActionMode(mode:ActionMode){}
                        };textView=this
                    }},modifier=Modifier.fillMaxWidth())
                }
                if(selection.isNotEmpty())Text(selection.take(90),Modifier.background(PaperGold.copy(alpha=.25f)).padding(8.dp),fontSize=13.sp)
                OutlinedTextField(note,{note=it.take(4000)},label={Text("笔记（可留空，仅高亮）")},modifier=Modifier.fillMaxWidth(),maxLines=4)
                Button(onClick={save()},modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text("保存高亮与笔记")}
            }
        }
    }
}

@Composable internal fun PaperNotes(bookIds:Set<String>,onOpen:(String)->Unit){
    val context=LocalContext.current;val store=remember{context.getSharedPreferences("paper-notes",0)}
    var revision by remember{mutableIntStateOf(0)}
    val entries=remember(revision){val a=runCatching{JSONArray(store.getString("entries","[]"))}.getOrDefault(JSONArray());(0 until a.length()).map{a.getJSONObject(it)}}
    val shown=entries.filter{it.optString("book") in bookIds}
    if(shown.isEmpty())Text("还没有摘录。长按阅读正文，留下喜欢的句子。",Modifier.padding(vertical=20.dp),fontSize=13.sp)
    shown.reversed().forEach{item->
        Column(Modifier.fillMaxWidth().padding(vertical=16.dp)){
            Text(item.optString("quote"),Modifier.background(PaperGold.copy(alpha=.16f)).padding(12.dp))
            if(item.optString("note").isNotBlank())Text(item.optString("note"),Modifier.padding(top=12.dp))
            Text(item.optString("title"),Modifier.padding(top=8.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Row{
                TextButton(onClick={context.getSharedPreferences("paper-ui",0).edit().putString("pending-note",item.toString()).apply();onOpen(item.optString("book"))}){Text("回到原文")}
                TextButton(onClick={val remaining=JSONArray();entries.filter{it.optString("id")!=item.optString("id")}.forEach{remaining.put(it)};store.edit().putString("entries",remaining.toString()).apply();revision++}){Text("删除笔记")}
            }
        }
    }
}
