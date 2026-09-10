package local.readapp.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import local.readapp.core.ReaderPreferences
import kotlin.math.roundToInt

@Composable internal fun RgbSettings(prefs:ReaderPreferences,update:(ReaderPreferences)->Unit){
    var selected by remember {mutableIntStateOf(if(prefs.theme=="dark")2 else 0)}
    val latest by rememberUpdatedState(prefs)
    val codes=listOf(prefs.dayBackground,prefs.dayText,prefs.nightBackground,prefs.nightText)
    val defaults=listOf("#F6F1E7","#242424","#17191B","#E5E3DF")
    val code=codes[selected].ifEmpty {defaults[selected]}
    val color=Color(android.graphics.Color.parseColor(code))
    var hex by remember(selected,code){mutableStateOf(code)}
    fun save(value:String){update(when(selected){0->latest.copy(dayBackground=value);1->latest.copy(dayText=value);2->latest.copy(nightBackground=value);else->latest.copy(nightText=value)})}
    fun rgb(r:Float,g:Float,b:Float)=save("#%02X%02X%02X".format((r.coerceIn(0f,1f)*255).roundToInt(),(g.coerceIn(0f,1f)*255).roundToInt(),(b.coerceIn(0f,1f)*255).roundToInt()))
    Row{listOf("浅背景","浅文字","深背景","深文字").forEachIndexed {i,label->TextButton(onClick={selected=i},modifier=Modifier.weight(1f),contentPadding=PaddingValues(0.dp)){Text((if(i==selected)"•" else "")+label,style=MaterialTheme.typography.labelSmall)}}}
    val latestColor by rememberUpdatedState(color)
    val latestRgb by rememberUpdatedState<(Float,Float,Float)->Unit>(::rgb)
    Canvas(Modifier.fillMaxWidth().height(112.dp)
        .pointerInput(selected){detectTapGestures {p->latestRgb(p.x/size.width,1-p.y/size.height,latestColor.blue)}}
        .pointerInput(selected){detectDragGestures {change,_->change.consume();latestRgb(change.position.x/size.width,1-change.position.y/size.height,latestColor.blue)}}){
        drawRect(Brush.horizontalGradient(listOf(Color(0f,0f,color.blue),Color(1f,0f,color.blue))))
        drawRect(Brush.verticalGradient(listOf(Color(0f,1f,0f),Color.Black)),blendMode=BlendMode.Plus)
        val point=Offset(color.red*size.width,(1-color.green)*size.height)
        drawCircle(Color.Black,7.dp.toPx(),point);drawCircle(Color.White,5.dp.toPx(),point);drawCircle(color,3.dp.toPx(),point)
    }
    Text("色图：横向 R · 纵向 G；滑条可精确调整 RGB",style=MaterialTheme.typography.bodySmall)
    listOf("R" to color.red,"G" to color.green,"B" to color.blue).forEachIndexed {i,(label,value)->
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("$label ${(value*255).roundToInt()}",Modifier.width(52.dp));Slider(value,{v->rgb(if(i==0)v else color.red,if(i==1)v else color.green,if(i==2)v else color.blue)},modifier=Modifier.weight(1f))}
    }
    OutlinedTextField(hex,{hex=it.take(7);if(Regex("#[0-9a-fA-F]{6}").matches(hex))save(hex.uppercase())},label={Text("颜色代码 #RRGGBB")},singleLine=true,modifier=Modifier.fillMaxWidth())
    TextButton(onClick={save("")}){Text("恢复此项主题颜色")}
}
