package local.readapp.feature

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import local.readapp.core.ReaderPreferences

@Composable internal fun AppearanceSettings(prefs:ReaderPreferences,update:(ReaderPreferences)->Unit){
    val context=LocalContext.current;val scope=rememberCoroutineScope();val latest by rememberUpdatedState(prefs)
    var importing by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    var editingColors by remember {mutableStateOf(false)};var isFont by remember {mutableStateOf(true)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result ->
        result.data?.data?.takeIf {result.resultCode==android.app.Activity.RESULT_OK}?.let { uri ->
            val font=isFont;importing=true
            scope.launch {try{ReadingAssets.prune(context,setOf(latest.fontFile,latest.backgroundFile));val (file,name)=ReadingAssets.import(context,uri,font);update(if(font)latest.copy(fontFile=file,fontName=name) else latest.copy(backgroundFile=file))}catch(e:Exception){if(e is kotlinx.coroutines.CancellationException)throw e;error=e.message?:"文件读取失败"}finally{importing=false}}
        }
    }
    fun pick(font:Boolean){isFont=font;picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type=if(font)"*/*" else "image/*";putExtra(Intent.EXTRA_LOCAL_ONLY,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)})}
    Text("字体 · ${prefs.fontName.ifBlank { "系统字体" }}",style=MaterialTheme.typography.bodyMedium)
    Row {TextButton(onClick={pick(true)},enabled=!importing){Text("选择字体文件")};if(prefs.fontFile.isNotEmpty())TextButton(onClick={update(prefs.copy(fontFile="",fontName=""))}){Text("系统字体")}}
    TextButton(onClick={editingColors=!editingColors}){Text("自定义背景与文字颜色")}
    Row {TextButton(onClick={pick(false)},enabled=!importing){Text("选择背景图片")};if(prefs.backgroundFile.isNotEmpty())TextButton(onClick={update(prefs.copy(backgroundFile=""))}){Text("移除图片")}}
    if(prefs.backgroundFile.isNotEmpty()){
        Text("深色模式图片遮暗 · ${(prefs.nightImageDim*100).toInt()}%")
        Slider(prefs.nightImageDim,{update(prefs.copy(nightImageDim=it))},valueRange=0f..0.95f)
    }
    if(importing)Text("正在保存到本机…")
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(editingColors) RgbSettings(prefs,update)
}

@Composable internal fun ChapterRuleSettings(prefs:ReaderPreferences,update:(ReaderPreferences)->Unit){
    var editing by remember {mutableStateOf(false)}
    TextButton(onClick={editing=true}){Text("手动调整 TXT 章节规则")}
    if(editing){
        var units by remember{mutableStateOf(prefs.chapterUnits)};var separator by remember{mutableStateOf(prefs.chapterSeparator)}
        var limit by remember{mutableFloatStateOf(prefs.chapterTitleLimit.toFloat())};var excluded by remember{mutableStateOf(prefs.excludedTitles)}
        AlertDialog(onDismissRequest={editing=false},title={Text("章节匹配规则")},text={Column{
            Text("对所有 TXT 生效。误识别“第一节下课……”时，可去掉“节”，或要求序号后有空格。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(units,{units=it.filter { c->c in "章回节卷部篇" }.toSet().joinToString("")},label={Text("识别单位：章回节卷部篇")},singleLine=true)
            Row(verticalAlignment=Alignment.CenterVertically){Text("序号与标题必须分隔",Modifier.weight(1f));Switch(separator,{separator=it})}
            Text("标题最多 ${limit.toInt()} 字");Slider(limit,{limit=it},valueRange=6f..100f)
            OutlinedTextField(excluded,{excluded=it.take(8000)},label={Text("排除的完整标题（每行一条）")},minLines=2,maxLines=4)
        }},confirmButton={TextButton(onClick={update(prefs.copy(chapterUnits=units,chapterSeparator=separator,chapterTitleLimit=limit.toInt(),excludedTitles=excluded));editing=false}){Text("应用规则")}},dismissButton={TextButton(onClick={editing=false}){Text("取消")}})
    }
}

