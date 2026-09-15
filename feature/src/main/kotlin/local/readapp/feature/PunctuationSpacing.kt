package local.readapp.feature

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.Spannable
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import kotlin.math.ceil

internal const val CJK_OPEN="（〔［｛〈《「『【“‘"
internal const val CJK_CLOSE="，。！？：；、）〕］｝〉》」』】”’"

/** Remove a punctuation glyph's side bearing, never squeeze the glyph itself. */
internal class PunctuationSpaceSpan(val fraction:Float):ReplacementSpan(){
    override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int = ceil(paint.measureText(text,start,end)*fraction).toInt()
    override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint){
        val advance=paint.measureText(text,start,end)
        val shift=if(text[start] in CJK_OPEN)advance*(1-fraction) else 0f
        canvas.drawText(text,start,end,x-shift,y.toFloat(),paint)
    }
}

/** Compress punctuation only when it lets the next complete character fit.
 * Native CJK prohibition rules and justification still perform the line breaking.
 * Source characters/offsets are never inserted, removed or substituted.
 */
internal fun punctuationLayout(text:Spannable,paint:TextPaint,width:Int,build:()->StaticLayout):StaticLayout {
    var layout=build()
    repeat(6){
        var changed=false
        for(line in 0 until layout.lineCount){
            val start=layout.getLineStart(line);val end=layout.getLineEnd(line)
            if(end>=text.length || end<=start || text[end-1]=='\n' || text[end]=='\n')continue
            var stop=end+Character.charCount(Character.codePointAt(text,end))
            // A closing mark must accompany the character before it.
            while(stop<text.length && text[stop] in CJK_CLOSE)stop++
            val candidates=(start until stop).filter{text[it] in CJK_OPEN || text[it] in CJK_CLOSE}
            if(candidates.isEmpty())continue
            val needed=Layout.getDesiredWidth(text,start,stop,paint)-width+1f
            if(needed<=0)continue
            val room=candidates.sumOf { i->
                val old=text.getSpans(i,i+1,PunctuationSpaceSpan::class.java).firstOrNull()?.fraction?:1f
                (paint.measureText(text,i,i+1)*(old-.5f).coerceAtLeast(0f)).toDouble()
            }.toFloat()
            if(room+0.01f<needed)continue
            candidates.forEach {i->
                val spans=text.getSpans(i,i+1,PunctuationSpaceSpan::class.java)
                val old=spans.firstOrNull()?.fraction?:1f
                val fraction=(old-(old-.5f)*needed/room).coerceAtLeast(.5f)
                spans.forEach(text::removeSpan)
                text.setSpan(PunctuationSpaceSpan(fraction),i,i+1,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            changed=true
        }
        if(!changed)return layout
        layout=build()
    }
    return layout
}
