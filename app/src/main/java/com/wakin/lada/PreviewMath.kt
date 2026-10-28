package com.wakin.lada

import kotlin.math.*

data class PixelRegion(val left: Int, val top: Int, val side: Int) {
    val right get() = left + side
    val bottom get() = top + side
    fun normalized(width: Int, height: Int) = Region(left.toFloat()/width, top.toFloat()/height, right.toFloat()/width, bottom.toFloat()/height)
}

data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun square(width: Int, height: Int): PixelRegion {
        require(width > 0 && height > 0)
        val l=min(left,right).coerceIn(0f,1f); val r=max(left,right).coerceIn(0f,1f)
        val t=min(top,bottom).coerceIn(0f,1f); val b=max(top,bottom).coerceIn(0f,1f)
        val side=ceil(max((r-l)*width,(b-t)*height)).toInt().coerceIn(min(24,min(width,height)),min(width,height))
        val x=(((l+r)*width-side)/2).roundToInt().coerceIn(0,width-side)
        val y=(((t+b)*height-side)/2).roundToInt().coerceIn(0,height-side)
        return PixelRegion(x,y,side)
    }
    fun iou(other: Region): Float {
        val intersection=max(0f,min(right,other.right)-max(left,other.left))*max(0f,min(bottom,other.bottom)-max(top,other.top))
        val union=max(0f,right-left)*max(0f,bottom-top)+max(0f,other.right-other.left)*max(0f,other.bottom-other.top)-intersection
        return if(union > 0) intersection/union else 0f
    }
    companion object { val Center = Region(.3f,.3f,.7f,.7f) }
}

data class Candidate(val region: Region, val confidence: Float)
fun suppressOverlaps(candidates: List<Candidate>): List<Candidate> {
    val kept=mutableListOf<Candidate>()
    for(candidate in candidates.sortedByDescending { it.confidence }) {
        if(kept.none { it.region.iou(candidate.region) > .45f }) kept += candidate
        if(kept.size == 6) break
    }
    return kept
}
fun neighborTimes(positionMs: Long, durationMs: Long, frameIntervalMs: Double): List<Long> =
    listOf(-1,0,1).map { (positionMs + it*frameIntervalMs).roundToLong().coerceIn(0L,max(0L,durationMs-1)) }

object PixelTensors {
    fun planar(pixels: IntArray, bgr: Boolean): FloatArray {
        val n=pixels.size; val out=FloatArray(n*3)
        pixels.forEachIndexed { i,pixel ->
            out[i]=((pixel ushr (if(bgr) 0 else 16)) and 255)/255f
            out[n+i]=((pixel ushr 8) and 255)/255f
            out[2*n+i]=((pixel ushr (if(bgr) 16 else 0)) and 255)/255f
        }
        return out
    }
    fun bgrToArgb(data: FloatArray, offset: Int, pixels: Int)=planarToArgb(data,offset,pixels,true)
    fun planarToArgb(data: FloatArray, offset: Int, pixels: Int, bgr:Boolean): IntArray {
        fun channel(v: Float): Int { require(v.isFinite()) { "修复模型输出了无效像素" }; return (v*255).roundToInt().coerceIn(0,255) }
        return IntArray(pixels) { i -> (0xff shl 24) or (channel(data[offset+(if(bgr)2 else 0)*pixels+i]) shl 16) or (channel(data[offset+pixels+i]) shl 8) or channel(data[offset+(if(bgr)0 else 2)*pixels+i]) }
    }
}

/** Guards UI callbacks from cancelled or superseded playback generations. */
class PlaybackGeneration {
    private var sequence=0L
    @Volatile private var current:Long?=null
    @Synchronized fun begin():Long {sequence++;current=sequence;return sequence}
    fun isCurrent(token:Long)=current==token
    fun isRunning()=current!=null
    @Synchronized fun stop(){current=null}
    @Synchronized fun finish(token:Long):Boolean {
        if(current!=token)return false
        current=null
        return true
    }
}

fun lightModelShape(size:Int):LongArray {
    require(size==128||size==256) { "轻量模型仅支持 128 或 256 两档输入" }
    return longArrayOf(1,3,size.toLong(),size.toLong())
}
