package com.wakin.lada

import ai.onnxruntime.*
import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.*
import kotlinx.coroutines.CancellationException

enum class LiveQuality(val title:String) { ORIGINAL("原版画质"), FAST("轻量流畅") }

enum class Backend(val title: String) { CPU("CPU"), WEBGPU("WebGPU") }
data class VideoInfo(val uri: Uri, val name: String, val durationMs: Long, val width: Int, val height: Int, val frameIntervalMs: Double)
data class RestoredPreview(val original:Bitmap,val restored:Bitmap?,val elapsedMs:Long,val candidates:List<Candidate>)

class PreviewEngine(private val context: Context): AutoCloseable {
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }
    private val sessions=mutableMapOf<String,OrtSession>()
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val runLock=Any()
    @Volatile private var cancelled=false
    private var activeRun:OrtSession.RunOptions?=null
    fun cancelCurrent() {
        cancelled=true
        synchronized(runLock){runCatching{activeRun?.setTerminate(true)}}
    }
    private fun checkCancelled(){if(cancelled)throw CancellationException("已停止处理")}
    private fun infer(model:OrtSession,inputs:Map<String,OnnxTensor>):OrtSession.Result {
        checkCancelled()
        return OrtSession.RunOptions().use { options ->
            synchronized(runLock){checkCancelled();activeRun=options}
            try {
                val result=model.run(inputs,options)
                if(cancelled){result.close();throw CancellationException("已停止处理")}
                result
            } finally {synchronized(runLock){if(activeRun===options)activeRun=null}}
        }
    }
    var backend=Backend.CPU
        private set
    fun setBackend(value: Backend) { if(value!=backend) { close(); backend=value } }
    fun webGpuAvailable()=OrtEnvironment.getAvailableProviders().any { it.name == "WEBGPU" }

    private fun session(model: String): OrtSession = sessions.getOrPut(model) {
        checkCancelled()
        val manifest=JSONObject(context.assets.open("model-info.json").bufferedReader().use { it.readText() })
        val destination=File(context.filesDir,model)
        val expected=manifest.getJSONObject(model).getString("sha256")
        fun digest(file:File):String {
            val md=MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream -> val bytes=ByteArray(1024*1024); while(true) { val n=stream.read(bytes); if(n<0)break;md.update(bytes,0,n) } }
            return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if(!destination.isFile || digest(destination)!=expected) {
            context.assets.open(model).use { input -> destination.outputStream().use { input.copyTo(it) } }
            check(digest(destination)==expected) { "模型文件校验失败，请重新安装预览版" }
        }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(4); options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if(backend==Backend.WEBGPU) {
                check(webGpuAvailable()) { "当前运行库不支持 WebGPU，请切回 CPU" }
                options.addWebGPU(mapOf("preferredLayout" to "NHWC"))
            }
            checkCancelled()
            val created=environment.createSession(destination.absolutePath,options)
            if(cancelled){runCatching{created.close()};checkCancelled()}
            created
        }
    }

    private fun <T> withVideo(uri: Uri, action: (MediaMetadataRetriever)->T): T {
        require(uri.scheme in setOf("file","content")) { "请选择本机可以读取的视频" }
        val retriever=MediaMetadataRetriever()
        return try { retriever.setDataSource(context,uri); action(retriever) } finally { retriever.release() }
    }
    fun inspect(uri:Uri):VideoInfo = withVideo(uri) { retriever ->
        val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        require(duration>0) { "无法读取视频时长，请尝试本地 MP4 文件" }
        val width=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val height=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val frameCount=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        val averageFps=frameCount?.let { it*1000.0/duration }?.takeIf { it.isFinite() && it in 1.0..240.0 }
        val fps=averageFps ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 30.0
        var name=uri.lastPathSegment?.substringAfterLast('/') ?: "所选视频"
        if(uri.scheme=="content") runCatching {
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor -> if(cursor.moveToFirst()) name=cursor.getString(0) ?: name }
        }
        VideoInfo(uri,name,duration,width,height,1000.0/fps)
    }
    private fun frame(retriever:MediaMetadataRetriever,info:VideoInfo,timeMs:Long):Bitmap {
        val scale=min(1.0,960.0/max(info.width,info.height))
        val width=max(1,(info.width*scale).roundToInt()); val height=max(1,(info.height*scale).roundToInt())
        val image=retriever.getScaledFrameAtTime(timeMs.coerceIn(0,max(0,info.durationMs-1))*1000,MediaMetadataRetriever.OPTION_CLOSEST,width,height)
            ?: throw IllegalStateException("无法读取这一帧，请换个时间点")
        return if(image.config==Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888,false) else image
    }
    fun frame(info:VideoInfo,timeMs:Long)=withVideo(info.uri) { frame(it,info,timeMs) }

    fun detect(image:Bitmap,live:Boolean=false):List<Candidate> {
        checkCancelled()
        val model=session(if(live) "detector-live.onnx" else "detector.onnx")
        val size=if(live)320 else 640
        val scale=min(size.toFloat()/image.width,size.toFloat()/image.height)
        val w=(image.width*scale).roundToInt(); val h=(image.height*scale).roundToInt()
        val dx=(size-w)/2; val dy=(size-h)/2
        val input=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(input); canvas.drawColor(Color.rgb(114,114,114))
        canvas.drawBitmap(image,null,Rect(dx,dy,dx+w,dy+h),paint)
        val pixels=IntArray(size*size);input.getPixels(pixels,0,size,0,0,size,size)
        val values=PixelTensors.planar(pixels,bgr=false)
        val candidates=mutableListOf<Candidate>()
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(values),longArrayOf(1,3,size.toLong(),size.toLong())).use { tensor ->
            infer(model,mapOf("images" to tensor)).use { result ->
                val prediction=result[0] as OnnxTensor
                val shape=(prediction.info as TensorInfo).shape
                check(shape.size==3 && shape[0]==1L && shape[1]==38L) { "检测模型版本不匹配" }
                val n=shape[2].toInt()
                val raw=FloatArray(38*n); prediction.floatBuffer.get(raw)
                // Both trained mosaic classes are selectable; do not silently ignore head mosaics.
                for(i in 0 until n) {
                    val confidence=max(raw[4*n+i],raw[5*n+i])
                    if(!confidence.isFinite() || confidence<.35f) continue
                    val cx=raw[i]; val cy=raw[n+i]; val bw=raw[2*n+i]; val bh=raw[3*n+i]
                    if(!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
                    val l=((cx-bw/2-dx)/scale/image.width).coerceIn(0f,1f)
                    val r=((cx+bw/2-dx)/scale/image.width).coerceIn(0f,1f)
                    val t=((cy-bh/2-dy)/scale/image.height).coerceIn(0f,1f)
                    val b=((cy+bh/2-dy)/scale/image.height).coerceIn(0f,1f)
                    if(r>l && b>t) candidates+=Candidate(Region(l,t,r,b),confidence)
                }
            }
        }
        return suppressOverlaps(candidates).map { it.copy(region=it.region.square(image.width,image.height).normalized(image.width,image.height)) }
    }

    fun restoreFrame(info:VideoInfo,positionMs:Long,quality:LiveQuality,lightSize:Int,manual:Region?,
                     stage:(String)->Unit,onDetected:(Bitmap,List<Candidate>)->Unit):RestoredPreview {
        checkCancelled()
        val started=System.nanoTime()
        stage("正在读取当前帧…")
        val current=frame(info,positionMs)
        val found=if(manual==null) {
            stage(if(backend==Backend.WEBGPU)"正在准备 WebGPU 并自动识别…" else "正在自动识别马赛克…")
            detect(current)
        } else listOf(Candidate(manual,1f))
        onDetected(current,found)
        if(found.isEmpty())return RestoredPreview(current,null,(System.nanoTime()-started)/1_000_000,found)
        stage("正在修复 ${found.size} 个区域…")
        val frames=if(quality==LiveQuality.FAST)listOf(current) else withVideo(info.uri) { retriever ->
            neighborTimes(positionMs,info.durationMs,info.frameIntervalMs).mapIndexed { index,time -> if(index==1)current else frame(retriever,info,time) }
        }
        val processed=repairBatch(frames,found.map{it.region},quality,lightSize)
        return RestoredPreview(current,processed[if(quality==LiveQuality.FAST)0 else 1],(System.nanoTime()-started)/1_000_000,found)
    }

    fun fastAvailable()=context.assets.list("")?.contains("mobile-deblock.onnx")==true

    /** All returned frames correspond to the supplied source timestamps; never overlay an old patch. */
    fun repairBatch(frames:List<Bitmap>,regions:List<Region>,quality:LiveQuality,lightSize:Int=128):List<Bitmap> {
        checkCancelled()
        if(regions.isEmpty()) return frames
        require(frames.isNotEmpty())
        val size=if(quality==LiveQuality.FAST)lightSize else 256
        if(quality==LiveQuality.FAST)lightModelShape(size)
        val pixelsPerFrame=size*size
        val model=session(if(quality==LiveQuality.FAST) "mobile-deblock.onnx" else if(backend==Backend.CPU) "restorer.onnx" else "restorer-portable.onnx")
        val output=frames.map {it.copy(Bitmap.Config.ARGB_8888,true)}
        for(region in regions) {
            checkCancelled()
            val batch=if(quality==LiveQuality.FAST)frames else List(3) {frames[min(it,frames.lastIndex)]}
            val data=FloatArray(batch.size*3*pixelsPerFrame)
            batch.forEachIndexed { index,image ->
                val crop=region.square(image.width,image.height)
                val scaled=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
                Canvas(scaled).drawBitmap(image,Rect(crop.left,crop.top,crop.right,crop.bottom),Rect(0,0,size,size),paint)
                val pixels=IntArray(pixelsPerFrame);scaled.getPixels(pixels,0,size,0,0,size,size)
                System.arraycopy(PixelTensors.planar(pixels,quality==LiveQuality.ORIGINAL),0,data,index*3*pixelsPerFrame,3*pixelsPerFrame)
            }
            if(quality==LiveQuality.FAST) {
                require(frames.size==1)
                OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),lightModelShape(size)).use { tensor ->
                    infer(model,mapOf("image" to tensor)).use { result ->
                        val values=FloatArray(3*pixelsPerFrame);(result[0] as OnnxTensor).floatBuffer.get(values)
                        composite(output[0],region,PixelTensors.planarToArgb(values,0,pixelsPerFrame,false),size)
                    }
                }
            } else {
                OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),longArrayOf(1,3,3,256,256)).use { tensor ->
                    infer(model,mapOf("frames" to tensor)).use { result ->
                        val values=FloatArray(9*pixelsPerFrame);(result[0] as OnnxTensor).floatBuffer.get(values)
                        output.forEachIndexed { index,image -> composite(image,region,PixelTensors.bgrToArgb(values,index*3*pixelsPerFrame,pixelsPerFrame),size) }
                    }
                }
            }
        }
        return output
    }
    private fun composite(destination:Bitmap,region:Region,values:IntArray,size:Int) {
        val crop=region.square(destination.width,destination.height)
        val patch=Bitmap.createScaledBitmap(Bitmap.createBitmap(values,size,size,Bitmap.Config.ARGB_8888),crop.side,crop.side,true)
        val before=IntArray(crop.side*crop.side);val after=IntArray(before.size)
        destination.getPixels(before,0,crop.side,crop.left,crop.top,crop.side,crop.side)
        patch.getPixels(after,0,crop.side,0,0,crop.side,crop.side)
        val feather=max(1f,crop.side*.04f)
        for(y in 0 until crop.side)for(x in 0 until crop.side) {
            val i=y*crop.side+x
            val amount=(min(min(x,crop.side-1-x),min(y,crop.side-1-y))/feather).coerceIn(0f,1f)
            var pixel=0xff shl 24
            for(shift in intArrayOf(0,8,16)) {
                val old=(before[i] ushr shift) and 255;val fresh=(after[i] ushr shift) and 255
                pixel=pixel or ((old+(fresh-old)*amount).roundToInt().coerceIn(0,255) shl shift)
            }
            after[i]=pixel
        }
        destination.setPixels(after,0,crop.side,crop.left,crop.top,crop.side,crop.side)
    }

    override fun close() { sessions.values.forEach { runCatching { it.close() } }; sessions.clear() }
}
