package local.readapp.feature

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import local.readapp.core.ReaderPreferences

@Composable internal fun AppPalette(prefs:ReaderPreferences,content:@Composable ()->Unit){
    val dark=readerIsDark(prefs);val base=MaterialTheme.colorScheme
    val bg=customColor(if(dark)prefs.nightBackground else prefs.dayBackground,base.surface)
    val fg=customColor(if(dark)prefs.nightText else prefs.dayText,base.onSurface)
    MaterialTheme(colorScheme=base.copy(background=bg,surface=bg,onBackground=fg,onSurface=fg,onSurfaceVariant=lerp(fg,bg,.25f),surfaceVariant=lerp(bg,fg,.07f),surfaceContainer=lerp(bg,fg,.04f),surfaceContainerLow=lerp(bg,fg,.02f),surfaceContainerHigh=lerp(bg,fg,.07f),surfaceContainerHighest=lerp(bg,fg,.1f),surfaceContainerLowest=bg),content=content)
}

/** An overlay, not a resizing dialog: typography changes can be compared with the same full page. */
@Composable internal fun ReaderSettings(prefs:ReaderPreferences,encoding:String?,update:(ReaderPreferences)->Unit,changeEncoding:(String)->Unit,close:()->Unit){
    BackHandler(onBack=close)
    var tab by remember {mutableIntStateOf(0)};var encodings by remember {mutableStateOf(false)}
    val height=(LocalConfiguration.current.screenHeightDp*.48f).coerceIn(240f,420f).dp
    Surface(Modifier.fillMaxWidth().height(height),shape=RoundedCornerShape(topStart=22.dp,topEnd=22.dp),color=MaterialTheme.colorScheme.surfaceContainer,shadowElevation=12.dp){
        Column(Modifier.fillMaxSize().padding(horizontal=16.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("阅读设置",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick=close){Text("完成")}}
            Row(Modifier.fillMaxWidth()){listOf("排版","外观","更多").forEachIndexed {i,label->TextButton(onClick={tab=i},modifier=Modifier.weight(1f)){Text(if(tab==i)"• $label" else label)}}}
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top=8.dp,bottom=16.dp)){
                when(tab){
                    0->{
                        Text("字号 · ${prefs.fontSize}");ReadingSlider(prefs.fontSize.toFloat(),{update(prefs.copy(fontSize=it.toInt()))},valueRange=14f..32f)
                        Text("行高 · ${"%.1f".format(prefs.lineSpacing)} 倍字号");ReadingSlider(prefs.lineSpacing,{update(prefs.copy(lineSpacing=it))},valueRange=1.2f..2.4f)
                        Text("段间距 · ${prefs.paragraphSpacing} dp");ReadingSlider(prefs.paragraphSpacing.toFloat(),{update(prefs.copy(paragraphSpacing=it.toInt()))},valueRange=0f..24f)
                        Text("页边距 · ${prefs.pageMargin} dp");ReadingSlider(prefs.pageMargin.toFloat(),{update(prefs.copy(pageMargin=it.toInt()))},valueRange=8f..40f)
                        Text("翻页方式");Row{listOf("curl" to "仿真","slide" to "平移","cover" to "覆盖").forEach{(key,label)->TextButton(onClick={update(prefs.copy(turnStyle=key))}){Text((if(prefs.turnStyle==key)"✓ " else "")+label)}}}
                        Text("字间距 · ${"%.2f".format(prefs.letterSpacing)} 字宽");ReadingSlider(prefs.letterSpacing,{update(prefs.copy(letterSpacing=it))},valueRange=0f..0.3f)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("两端对齐",Modifier.weight(1f));Switch(prefs.justify,{update(prefs.copy(justify=it))})}
                    }
                    1->{
                        Row{listOf("paper" to "纸色","light" to "浅色","dark" to "深色","system" to "系统").forEach {(key,label)->TextButton(onClick={update(prefs.copy(theme=key))}){Text((if(prefs.theme==key)"✓ " else "")+label)}}}
                        AppearanceSettings(prefs,update)
                    }
                    else->{
                        Row(verticalAlignment=Alignment.CenterVertically){Text("阅读时保持亮屏",Modifier.weight(1f));Switch(prefs.keepScreenOn,{update(prefs.copy(keepScreenOn=it))})}
                        Row(verticalAlignment=Alignment.CenterVertically){Text("点击左右边缘翻页",Modifier.weight(1f));Switch(prefs.tapToTurn,{update(prefs.copy(tapToTurn=it))})}
                        Row(verticalAlignment=Alignment.CenterVertically){Text("启用书签",Modifier.weight(1f));Switch(prefs.bookmarksEnabled,{update(prefs.copy(bookmarksEnabled=it))})}
                        Row(verticalAlignment=Alignment.CenterVertically){Text("目录按钮放在左侧",Modifier.weight(1f));Switch(prefs.navigationFirst,{update(prefs.copy(navigationFirst=it))})}
                        if(encoding!=null){ChapterRuleSettings(prefs,update)
                            Row{listOf("all" to "全部","chinese" to "中文","english" to "英文","none" to "关闭").forEach {(key,label)->TextButton(onClick={update(prefs.copy(chapterRule=key))}){Text((if(prefs.chapterRule==key)"✓ " else "")+label)}}}
                            Text("编码：$encoding");TextButton(onClick={encodings=!encodings}){Text("切换编码")}
                            if(encodings)listOf("UTF-8","GB18030","UTF-16LE","UTF-16BE").forEach {v->TextButton(onClick={changeEncoding(v)}){Text(v)}}
                        }
                        TextButton(onClick={update(ReaderPreferences())}){Text("恢复默认设置")}
                    }
                }
            }
        }
    }
}

/** Continuous, tick-free controls; only the current value is shown in the label. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ReadingSlider(value:Float,onValueChange:(Float)->Unit,valueRange:ClosedFloatingPointRange<Float>){
    var sliding by remember { mutableStateOf(false) }
    var position by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value){if(!sliding)position=value}
    Slider(value=position.coerceIn(valueRange),onValueChange={sliding=true;position=it;onValueChange(it)},onValueChangeFinished={sliding=false;position=value},valueRange=valueRange,
        thumb={Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary,androidx.compose.foundation.shape.CircleShape))},
        track={Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.primary.copy(alpha=.15f))){Box(Modifier.fillMaxWidth(((position-valueRange.start)/(valueRange.endInclusive-valueRange.start)).coerceIn(0f,1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))}})
}
