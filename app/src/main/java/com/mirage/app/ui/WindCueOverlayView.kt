package com.mirage.app.ui

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.mirage.app.analysis.AnalysisResult
import com.mirage.app.analysis.MotionCue
import kotlin.math.*

/** v0.6 field overlay: sparse mirage traces, persistent secondary cues, target hint and stability pulse. */
class WindCueOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var latest: AnalysisResult? = null
    private var lastGoodMirage: AnalysisResult? = null
    private var lastGoodMirageMs = 0L
    private var heldCues: List<MotionCue> = emptyList()
    private var heldCueMs = 0L
    private var heldTarget: AnalysisResult? = null
    private var heldTargetMs = 0L
    private val holdMs = 4_000L

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=3f; strokeCap=Paint.Cap.ROUND }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; style=Paint.Style.STROKE; strokeWidth=6f; strokeCap=Paint.Cap.ROUND; alpha=150 }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=28f; setShadowLayer(5f,1f,1f,Color.BLACK) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; textSize=22f; setShadowLayer(4f,1f,1f,Color.BLACK) }
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xA8000000.toInt(); style=Paint.Style.FILL }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=7f }

    fun update(r: AnalysisResult) {
        latest=r; val now=SystemClock.elapsedRealtime()
        if (r.sufficientSignal) { lastGoodMirage=r; lastGoodMirageMs=now }
        if (r.motionCues.isNotEmpty()) { heldCues=r.motionCues; heldCueMs=now }
        if (r.targetLabel.isNotBlank() && r.targetConfidence >= .34) { heldTarget=r; heldTargetMs=now }
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c); val r=latest ?: return; val now=SystemClock.elapsedRealtime()
        if (r.fullWidthPx<=0 || r.fullHeightPx<=0) return
        val scale=max(width.toFloat()/r.fullWidthPx,height.toFloat()/r.fullHeightPx)
        val ox=(width-r.fullWidthPx*scale)/2f; val oy=(height-r.fullHeightPx*scale)/2f
        fun mx(x: Float) = ox + x * scale
        fun my(y: Float) = oy + y * scale
        fun rect(l:Int,t:Int,rr:Int,b:Int)=RectF(mx(l.toFloat()),my(t.toFloat()),mx(rr.toFloat()),my(b.toFloat()))

        val mir = if (r.sufficientSignal) r else lastGoodMirage?.takeIf { now-lastGoodMirageMs<=holdMs }
        if (mir != null) drawMirage(c,mir,scale,ox,oy,now)
        val cues=if(r.motionCues.isNotEmpty()) r.motionCues else heldCues.takeIf{now-heldCueMs<=holdMs}.orEmpty()
        cues.take(3).forEach { cue -> drawCue(c,cue,rect(cue.leftPx,cue.topPx,cue.rightPx,cue.bottomPx)) }
        val target=(if(r.targetLabel.isNotBlank()) r else heldTarget?.takeIf{now-heldTargetMs<=3_000L})
        if(target!=null && target.targetRightPx>target.targetLeftPx) drawTarget(c,target,rect(target.targetLeftPx,target.targetTopPx,target.targetRightPx,target.targetBottomPx))

        val bestCue=cues.maxByOrNull{it.confidence}
        val stable = r.mirageStable || bestCue?.stable==true
        val changing = (r.sufficientSignal || bestCue!=null) && !stable
        if(stable || changing) {
            val phase=(sin(now/180.0)+1.0)/2.0
            pulse.alpha=(120+120*phase).toInt(); pulse.color=if(stable) Color.GREEN else Color.RED
            c.drawCircle(width-42f,42f,20f,pulse); postInvalidateDelayed(80)
        }
    }

    private fun overlayColor(luma:Double):Int = when { luma>185 -> Color.MAGENTA; luma<70 -> Color.WHITE; else -> Color.CYAN }

    private fun drawMirage(c:Canvas,r:AnalysisResult,scale:Float,ox:Float,oy:Float,now:Long) {
        stroke.color=overlayColor(r.sceneLuma); stroke.alpha=210
        val left=ox+r.roiLeftPx*scale; val top=oy+r.roiTopPx*scale
        val rw=r.roiWidthPx*scale; val rh=r.roiHeightPx*scale
        val angle=if(r.bmAngleDeg.isFinite()) Math.toRadians(r.bmAngleDeg) else Math.PI/2
        // Frequency proxy: fast/dense mirage -> tighter wavelength; slow -> broad undulation.
        val freq=(r.fdDominantFreqHz.takeIf{it.isFinite() && it>0.05} ?: 1.0).coerceIn(.25,8.0)
        val wavelength=(110f/(0.55f+freq.toFloat())).coerceIn(16f,100f)
        val speed=(8f+freq.toFloat()*5f)
        val phase=(now%4000L)/1000f*speed
        val dirX=sin(angle).toFloat(); val dirY=(-cos(angle)).toFloat()
        val perpX=-dirY; val perpY=dirX
        repeat(3){k ->
            val baseX=left+rw*(0.32f+k*0.18f); val baseY=top+rh*0.62f
            val path=Path(); var first=true
            var d=-rh*.34f
            while(d<=rh*.34f){
                val wig=sin((d+phase)/wavelength*2f*Math.PI.toFloat())*(5f+min(8f,freq.toFloat()))
                val x=baseX+dirX*d+perpX*wig; val y=baseY+dirY*d+perpY*wig
                if(first){path.moveTo(x,y);first=false}else path.lineTo(x,y); d+=7f
            }
            c.drawPath(path,outline); c.drawPath(path,stroke)
        }
        val label="MIRAGE  ${r.mirageClockDirection}  ${if(freq>4.0) "FAST / DENSE" else if(freq<1.2) "SLOW / WIDE" else "MODERATE"}"
        drawLabel(c,left.coerceAtLeast(8f),(top+30f).coerceAtLeast(32f),label)
    }

    private fun drawCue(c:Canvas,q:MotionCue,box:RectF){
        stroke.color=Color.YELLOW; stroke.alpha=230; stroke.strokeWidth=4f; c.drawOval(box,outline); c.drawOval(box,stroke)
        val cx=box.centerX(); val cy=box.centerY(); val a=Math.toRadians(q.clockAngleDeg); val len=90f
        val ex=cx+sin(a).toFloat()*len; val ey=cy-cos(a).toFloat()*len
        drawArrow(c,cx,cy,ex,ey)
        val speed="%.1f–%.1f m/s EST".format(q.estimatedWindMinMps,q.estimatedWindMaxMps)
        drawLabel(c,box.left,(box.top-8f).coerceAtLeast(32f),"${q.label}  ${q.clockDirection}  $speed")
    }

    private fun drawArrow(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float){
        stroke.color=Color.YELLOW; stroke.strokeWidth=5f; c.drawLine(x1,y1,x2,y2,outline); c.drawLine(x1,y1,x2,y2,stroke)
        val a=atan2((y2-y1).toDouble(),(x2-x1).toDouble()); val h=18f
        for(off in listOf(2.55,-2.55)){ val xx=x2+cos(a+off).toFloat()*h; val yy=y2+sin(a+off).toFloat()*h; c.drawLine(x2,y2,xx,yy,stroke) }
    }

    private fun drawTarget(c:Canvas,r:AnalysisResult,box:RectF){
        stroke.color=Color.rgb(255,170,0); stroke.strokeWidth=3f; c.drawRect(box,stroke)
        drawLabel(c,box.left,(box.bottom+28f).coerceAtMost(height-12f),"TARGET: ${r.targetLabel}  conf ${(r.targetConfidence*100).toInt()}%")
    }

    private fun drawLabel(c:Canvas,x:Float,y:Float,s:String){ val w=text.measureText(s); c.drawRect(x-5,y-29,x+w+8,y+7,dark); c.drawText(s,x,y,text) }
}
