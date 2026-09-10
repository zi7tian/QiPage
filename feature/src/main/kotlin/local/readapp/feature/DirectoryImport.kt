package local.readapp.feature

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as Docs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

private data class DirectoryEntry(val uri:String,val name:String)
/** SAF grants are owned by Android; a typed path is only an initial picker location. */
@Composable internal fun DirectoryImport(import:(List<String>,Int)->Unit,close:()->Unit){
    val context=LocalContext.current;val resolver=context.contentResolver
    val scope=rememberCoroutineScope()
    val saved=remember { context.getSharedPreferences("local-directory",0) }
    var address by remember { mutableStateOf(saved.getString("tree",null)) }
    var path by remember { mutableStateOf("/storage/emulated/0/Novel/") }
    var entries by remember { mutableStateOf(emptyList<DirectoryEntry>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) };var loading by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
        if(result.resultCode==Activity.RESULT_OK)result.data?.data?.let { uri ->
            try {
                resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val previous=address;address=uri.toString();saved.edit().putString("tree",address).apply();revision++
                if(previous!=null && previous!=address)runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous),Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }catch(_:Exception){error="目录授权未保留，请重新选择目录并允许访问。"}
        }
    }
    LaunchedEffect(address,revision){
        entries=emptyList();selected=emptySet();error=null
        val tree=address?:return@LaunchedEffect
        loading=true
        try{entries=withContext(Dispatchers.IO){
            val uri=Uri.parse(tree);val children=Docs.buildChildDocumentsUriUsingTree(uri,Docs.getTreeDocumentId(uri))
            val results=ArrayList<DirectoryEntry>()
            resolver.query(children,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME,Docs.Document.COLUMN_MIME_TYPE,Docs.Document.COLUMN_FLAGS),null,null,null)?.use { cursor ->
                var count=0
                while(cursor.moveToNext()){
                    ensureActive();require(++count<=20000){"目录条目超过 20000 项，请选择更小的子目录"}
                    val id=cursor.getString(0);val name=cursor.getString(1).orEmpty();val mime=cursor.getString(2);val flags=cursor.getInt(3)
                    if(mime!=Docs.Document.MIME_TYPE_DIR && flags and (Docs.Document.FLAG_VIRTUAL_DOCUMENT or Docs.Document.FLAG_PARTIAL)==0 && (name.endsWith(".txt",true)||name.endsWith(".epub",true)))results.add(DirectoryEntry(Docs.buildDocumentUriUsingTree(uri,id).toString(),name))
                }
            }?:error("无法读取目录")
            results.sortedBy { it.name.lowercase() }
        }}catch(e:Exception){if(e is CancellationException)throw e;error=if(e is SecurityException)"目录授权已失效，请重新选择" else e.message?:"目录读取失败"}finally{loading=false}
    }
    AlertDialog(onDismissRequest=close,title={Text("目录导入")},text={Column(Modifier.fillMaxWidth()){
        OutlinedTextField(path,{path=it},label={Text("本机目录")},singleLine=true)
        Text("首次选择目录并允许访问，此后可直接勾选其中的书籍。",style=MaterialTheme.typography.bodySmall)
        Row{
            TextButton(onClick={
                val relative=path.trim().replaceFirst(Regex("(?i)^/storage/emulated/0/?"),"").trim('/')
                if(relative.split('/').any { it==".."||it=="." }){error="请输入有效的本机目录"}
                else picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra(Intent.EXTRA_LOCAL_ONLY,true)
                    putExtra(Docs.EXTRA_INITIAL_URI,Docs.buildDocumentUri("com.android.externalstorage.documents","primary:$relative"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                })
            }){Text("选择目录")}
            if(address!=null)TextButton(onClick={revision++}){Text("刷新")}
        }
        address?.let { Text("当前："+runCatching { Docs.getTreeDocumentId(Uri.parse(it)) }.getOrDefault(it),style=MaterialTheme.typography.bodySmall) }
        if(loading)Text("正在读取目录…")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(entries.isNotEmpty())TextButton(onClick={selected=if(selected.isEmpty())entries.take(100).map {it.uri}.toSet() else emptySet()}){Text(if(selected.isEmpty())"全选（最多100本）" else "取消选择")}
        LazyColumn(Modifier.heightIn(max=280.dp)){items(entries,key={it.uri}){entry ->
            Row(Modifier.fillMaxWidth().clickable {selected=if(entry.uri in selected)selected-entry.uri else if(selected.size<100)selected+entry.uri else selected},verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                Checkbox(entry.uri in selected,null);Text(entry.name,Modifier.weight(1f))
            }
        }}
        if(address!=null && !loading && entries.isEmpty() && error==null)Text("此目录没有 TXT 或 EPUB 文件。可选择其他目录；不递归扫描子目录。")
    }},confirmButton={TextButton(enabled=selected.isNotEmpty()&&!loading,onClick={import(selected.toList(),0);close()}){Text("导入 ${selected.size} 本")}},dismissButton={TextButton(onClick=close){Text("关闭")}})
}
