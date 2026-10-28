package com.wakin.lada

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.*
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlin.math.*

data class VideoFrame(val bitmap:Bitmap,val timeMs:Long)

/** Sequential hardware decode to a bounded-size bitmap, with source timestamps. */
class StreamingDecoder(context:Context,uri:Uri,startMs:Long):AutoCloseable {
    private val extractor=MediaExtractor()
    private val codec:MediaCodec
    private val info=MediaCodec.BufferInfo()
    private var inputEnded=false
    private var outputEnded=false
    private val startUs=startMs*1000
    private val rotation:Int
    private val colorStandard:Int
    private val fullRange:Boolean
    init {
        val descriptor=context.contentResolver.openAssetFileDescriptor(uri,"r") ?: error("无法打开视频")
        descriptor.use {
            if(it.declaredLength>=0) extractor.setDataSource(it.fileDescriptor,it.startOffset,it.declaredLength)
            else extractor.setDataSource(it.fileDescriptor)
        }
        val track=(0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true }
            ?: error("文件中没有视频轨道")
        extractor.selectTrack(track)
        val format=extractor.getTrackFormat(track)
        rotation=if(format.containsKey(MediaFormat.KEY_ROTATION))format.getInteger(MediaFormat.KEY_ROTATION) else 0
        colorStandard=if(format.containsKey(MediaFormat.KEY_COLOR_STANDARD))format.getInteger(MediaFormat.KEY_COLOR_STANDARD) else 2
        fullRange=format.containsKey(MediaFormat.KEY_COLOR_RANGE)&&format.getInteger(MediaFormat.KEY_COLOR_RANGE)==MediaFormat.COLOR_RANGE_FULL
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        extractor.seekTo(startUs,MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try { codec.configure(format,null,null,0);codec.start() } catch(error:Exception) {codec.release();extractor.release();throw error}
    }
    fun next(cancelled:()->Boolean):VideoFrame? {
        while(!outputEnded) {
            if(cancelled()) throw CancellationException("Playback stopped")
            if(!inputEnded) {
                val index=codec.dequeueInputBuffer(10_000)
                if(index>=0) {
                    val buffer=codec.getInputBuffer(index)!!
                    buffer.clear()
                    val count=extractor.readSampleData(buffer,0)
                    if(count<0) {codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true}
                    else {codec.queueInputBuffer(index,0,count,extractor.sampleTime,0);extractor.advance()}
                }
            }
            val index=codec.dequeueOutputBuffer(info,10_000)
            if(index>=0) {
                val end=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                try {
                    if(info.size>0 && info.presentationTimeUs>=startUs) {
                        val image=codec.getOutputImage(index) ?: error("此解码器未提供图像帧，请使用选帧预览")
                        val bitmap=image.use { toBitmap(it) }
                        if(end)outputEnded=true
                        return VideoFrame(bitmap,info.presentationTimeUs/1000)
                    }
                } finally {codec.releaseOutputBuffer(index,false)}
                if(end)outputEnded=true
            }
        }
        return null
    }
    private fun toBitmap(image:Image):Bitmap {
        check(image.planes.size==3) { "实时预览暂不支持该视频像素格式，请使用普通选帧预览" }
        val crop=image.cropRect
        val scale=min(1.0,640.0/max(crop.width(),crop.height()))
        val w=max(1,(crop.width()*scale).roundToInt());val h=max(1,(crop.height()*scale).roundToInt())
        val planes=image.planes;val pixels=IntArray(w*h)
        val yPlane=planes[0];val uPlane=planes[1];val vPlane=planes[2]
        val yBuffer=yPlane.buffer;val uBuffer=uPlane.buffer;val vBuffer=vPlane.buffer
        val yBase=yBuffer.position();val uBase=uBuffer.position();val vBase=vBuffer.position()
        for(y in 0 until h) {
            val sy=crop.top+(y.toLong()*crop.height()/h).toInt()
            for(x in 0 until w) {
                val sx=crop.left+(x.toLong()*crop.width()/w).toInt()
                val yy=yBuffer.get(yBase+sy*yPlane.rowStride+sx*yPlane.pixelStride).toInt() and 255
                val u=(uBuffer.get(uBase+(sy/2)*uPlane.rowStride+(sx/2)*uPlane.pixelStride).toInt() and 255)-128
                val v=(vBuffer.get(vBase+(sy/2)*vPlane.rowStride+(sx/2)*vPlane.pixelStride).toInt() and 255)-128
                val c=if(fullRange) yy*256 else max(0,yy-16)*298
                val red:Int;val green:Int;val blue:Int
                if(colorStandard==MediaFormat.COLOR_STANDARD_BT709) {
                    red=(c+(if(fullRange)403 else 459)*v+128) shr 8
                    green=(c-(if(fullRange)48 else 55)*u-(if(fullRange)120 else 136)*v+128) shr 8
                    blue=(c+(if(fullRange)475 else 541)*u+128) shr 8
                } else {
                    red=(c+(if(fullRange)359 else 409)*v+128) shr 8
                    green=(c-(if(fullRange)88 else 100)*u-(if(fullRange)183 else 208)*v+128) shr 8
                    blue=(c+(if(fullRange)454 else 516)*u+128) shr 8
                }
                pixels[y*w+x]=(0xff shl 24) or (red.coerceIn(0,255) shl 16) or (green.coerceIn(0,255) shl 8) or blue.coerceIn(0,255)
            }
        }
        val bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        return if(rotation%360!=0) Bitmap.createBitmap(bitmap,0,0,w,h,Matrix().apply{postRotate(rotation.toFloat())},true) else bitmap
    }
    override fun close() {runCatching{codec.stop()};runCatching{codec.release()};extractor.release()}
}
