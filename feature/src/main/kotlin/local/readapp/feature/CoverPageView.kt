package local.readapp.feature

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Duration of a full-width page turn, in milliseconds.
 *
 * A commit animation starts from the current drag fraction, so the real
 * duration is scaled by how far is left to travel. Raise this to make page
 * turns feel slower, lower it to make them snappier.
 */
private const val TURN_FULL_MS=300f

/** Floor so that a turn released almost at its target still animates visibly. */
private const val TURN_MIN_MS=110L

/**
 * Horizontal placement of the two page layers during a turn, plus where the
 * seam shadow belongs. Extracted so the geometry can be unit tested without a
 * device.
 *
 * Forward (drag left, go to the next page): the current page follows the
 * finger and slides off to the left while the next page waits underneath.
 *
 * Backward (drag right, go to the previous page) is that motion mirrored: the
 * current page stays put and the previous page slides in from the left edge
 * until it covers it.
 */
internal data class TurnLayout(val baseX:Float,val targetX:Float,val shadowX:Float)

/**
 * How long a turn animation should run.
 *
 * Scaling by the distance still to travel keeps a short commit snappy while a
 * full sweep still takes [TURN_FULL_MS]; the floor stops a release right on top
 * of the target from snapping instantly.
 */
internal fun turnDurationMs(accept:Boolean,fraction:Float):Long {
    val remaining=(if(accept) 1f-fraction else fraction).coerceIn(0f,1f)
    return (TURN_FULL_MS*remaining).toLong().coerceIn(TURN_MIN_MS,TURN_FULL_MS.toLong())
}

internal fun turnLayout(forward:Boolean,fraction:Float,width:Int):TurnLayout {
    val f=fraction.coerceIn(0f,1f)
    val w=width.coerceAtLeast(1).toFloat()
    return if(forward) {
        // base leaves to the left, target is pinned underneath
        TurnLayout(baseX=-w*f,targetX=0f,shadowX=w*(1f-f))
    } else {
        // base is pinned, target arrives from the left
        TurnLayout(baseX=0f,targetX=-w*(1f-f),shadowX=w*f)
    }
}

/** Only viewport-sized frames move. The underlying renderer never moves during a gesture. */
internal class CoverPageView(context:Context):View(context) {
    var prepare:((Boolean,(Bitmap?)->Unit)->Unit)?=null
    var commit:((Boolean)->Unit)?=null
    var cancelled:(()->Unit)?=null
    var settings:(()->Unit)?=null
    var longPress:((Float,Float)->Unit)?=null
    var edgeTap=true
    /**
     * While the typography panel is open every tap belongs to it: the first tap
     * anywhere dismisses the panel instead of turning a page, so the user never
     * pages away by accident while adjusting settings.
     */
    var settingsOpen=false
    private var current:Bitmap?=null
    private var target:Bitmap?=null
    private var active=false
    private var next=true
    private var released:Boolean?=null
    private var generation=0
    private var downX=0f;private var downY=0f
    private var fraction=0f
    private var animator:ValueAnimator?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    init { isClickable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun show(bitmap:Bitmap,description:String){
        generation++;animator?.cancel();animator=null;current=bitmap;target=null;active=false;fraction=0f;released=null
        contentDescription=description;if(description.isBlank())contentDescription="阅读页";invalidate()
    }
    fun abort(){generation++;animator?.cancel();animator=null;target=null;active=false;fraction=0f;released=null;invalidate()}
    fun turn(forward:Boolean){if(active || current==null)return;begin(forward);finish(true)}
    private fun begin(forward:Boolean){
        if(current==null || active)return
        active=true;next=forward;target=null;fraction=0f;released=null
        val token=++generation
        prepare?.invoke(forward){bitmap ->
            if(token!=generation)return@invoke
            if(bitmap==null){abort();cancelled?.invoke();return@invoke}
            target=bitmap;invalidate();released?.let(::finish)
        } ?: run {abort()}
    }
    private fun finish(accept:Boolean){
        if(!active)return
        released=accept
        if(target==null){if(!accept){abort();cancelled?.invoke()};return}
        if(animator!=null)return
        val token=generation
        animator=ValueAnimator.ofFloat(fraction,if(accept)1f else 0f).apply {
            duration=turnDurationMs(accept,fraction)
            interpolator=DecelerateInterpolator()
            addUpdateListener {fraction=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){
                if(token!=generation)return
                animator=null
                if(accept){current=target;target=null;active=false;fraction=0f;released=null;invalidate();commit?.invoke(next)}
                else{abort();cancelled?.invoke()}
            }})
            start()
        }
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        val base=current?:return
        fun draw(bitmap:Bitmap,x:Float){canvas.drawBitmap(bitmap,null,RectF(x,0f,x+width,height.toFloat()),paint)}
        val adjacent=target
        if(!active || adjacent==null){draw(base,0f);return}
        val layout=turnLayout(next,fraction,width)
        // Paint the pinned layer first so the arriving layer covers it.
        if(next){draw(adjacent,layout.targetX);draw(base,layout.baseX)}
        else{draw(base,layout.baseX);draw(adjacent,layout.targetX)}
        shadow(canvas,layout.shadowX)
    }
    /** Soft seam where a moving page overlaps the page underneath. */
    private fun shadow(canvas:Canvas,x:Float){
        val d=12*resources.displayMetrics.density
        paint.shader=LinearGradient(x,0f,x+d,0f,0x28000000,Color.TRANSPARENT,Shader.TileMode.CLAMP)
        canvas.drawRect(x,0f,x+d,height.toFloat(),paint);paint.shader=null
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(animator!=null)return true
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{if(active)return true;downX=event.x;downY=event.y;parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE->{
                val dx=event.x-downX;val dy=event.y-downY
                if(!active && kotlin.math.abs(dx)>slop && kotlin.math.abs(dx)>kotlin.math.abs(dy))begin(dx<0)
                if(active){fraction=((if(next)-dx else dx)/width.coerceAtLeast(1)).coerceIn(0f,1f);invalidate()}
            }
            MotionEvent.ACTION_CANCEL->{finish(false);parent?.requestDisallowInterceptTouchEvent(false)}
            MotionEvent.ACTION_UP->{
                if(active)finish(fraction>=0.18f)
                else if(kotlin.math.abs(event.x-downX)<slop && kotlin.math.abs(event.y-downY)<slop){
                    if(settingsOpen)settings?.invoke()
                    else if(event.eventTime-event.downTime>=ViewConfiguration.getLongPressTimeout() && longPress!=null)longPress?.invoke(event.x,event.y)
                    else if(event.x in width*.25f..width*.75f && event.y in height*.2f..height*.8f)settings?.invoke()
                    else if(edgeTap && event.x<width*.25f)turn(false) else if(edgeTap && event.x>width*.75f)turn(true)
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000001,"下一页"));info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000002,"上一页"));info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000003,"排版设置"))}
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean=when(action){0x01000001->{turn(true);true};0x01000002->{turn(false);true};0x01000003->{settings?.invoke();true};else->super.performAccessibilityAction(action,args)}
    override fun onDetachedFromWindow(){abort();current=null;super.onDetachedFromWindow()}
}

