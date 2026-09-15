package local.readapp.feature

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Consistent thin round-ended strokes without an icon font. */
@Composable internal fun PaperIcon(name:String,modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(22.dp)) {
        val u=size.width/24f;val stroke=1.5.dp.toPx()
        fun line(x:Float,y:Float,xx:Float,yy:Float)=drawLine(color,Offset(x*u,y*u),Offset(xx*u,yy*u),stroke,StrokeCap.Round)
        fun box(x:Float,y:Float,w:Float,h:Float)=drawRoundRect(color,Offset(x*u,y*u),Size(w*u,h*u),CornerRadius(u),style=Stroke(stroke))
        when(name){
            "menu"->{line(3f,6f,21f,6f);line(3f,12f,15f,12f);line(3f,18f,21f,18f)}
            "search"->{drawCircle(color,7*u,Offset(10*u,10*u),style=Stroke(stroke));line(15f,15f,21f,21f)}
            "grid"->{box(3f,3f,7f,7f);box(14f,3f,7f,7f);box(3f,14f,7f,7f);box(14f,14f,7f,7f)}
            "list"->{for(y in listOf(5f,12f,19f)){line(3f,y,4f,y);line(9f,y,21f,y)}}
            "plus"->{line(12f,4f,12f,20f);line(4f,12f,20f,12f)}
            "back"->{line(14f,4f,6f,12f);line(6f,12f,14f,20f)}
            "close"->{line(5f,5f,19f,19f);line(19f,5f,5f,19f)}
            "check"->{line(4f,12f,9f,17f);line(9f,17f,20f,6f)}
            "bookmark"->{val p=Path().apply{moveTo(5*u,3*u);lineTo(19*u,3*u);lineTo(19*u,21*u);lineTo(12*u,16*u);lineTo(5*u,21*u);close()};drawPath(p,color,style=Stroke(stroke))}
            "book"->{box(3f,3f,18f,18f);line(8f,3f,8f,21f);line(12f,8f,17f,8f)}
            "cloud"->{val p=Path().apply{moveTo(6*u,19*u);cubicTo(0f,19*u,0f,10*u,6*u,10*u);cubicTo(7*u,3*u,16*u,3*u,18*u,10*u);cubicTo(24*u,10*u,24*u,19*u,18*u,19*u);close()};drawPath(p,color,style=Stroke(stroke,join=StrokeJoin.Round))}
            "settings"->{val p=Path();for(i in 0..31){val angle=i*Math.PI/16;val radius=if(i%4 in 1..2)10f else 8f;val x=(12+radius*kotlin.math.cos(angle)).toFloat()*u;val y=(12+radius*kotlin.math.sin(angle)).toFloat()*u;if(i==0)p.moveTo(x,y) else p.lineTo(x,y)};p.close();drawPath(p,color,style=Stroke(stroke,join=StrokeJoin.Round));drawCircle(color,3*u,center,style=Stroke(stroke))}
            "stats"->{line(5f,20f,5f,13f);line(12f,20f,12f,5f);line(19f,20f,19f,9f)}
            "sun"->{drawCircle(color,4*u,center,style=Stroke(stroke));for(i in 0..7){val a=i*Math.PI/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())}}
            "type"->{line(4f,5f,20f,5f);line(12f,5f,12f,20f);line(8f,20f,16f,20f)}
            "more"->{for(x in listOf(5f,12f,19f))drawCircle(color,1.3f*u,Offset(x*u,12*u))}
            else->{drawCircle(color,8*u,center,style=Stroke(stroke));drawCircle(color,3*u,center,style=Stroke(stroke))}
        }
    }
}
