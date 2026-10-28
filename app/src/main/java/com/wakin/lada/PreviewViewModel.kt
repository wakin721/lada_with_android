package com.wakin.lada

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors

data class LiveState(
    val visible:Boolean=false,val running:Boolean=false,val buffering:Boolean=false,val fullscreen:Boolean=false,
    val frame:Bitmap?=null,val original:Bitmap?=null,val positionMs:Long=0,val fps:Double=0.0,
    val regions:List<Region> = emptyList(),val status:String="",val autoDetect:Boolean=true,
    val quality:LiveQuality=LiveQuality.ORIGINAL,val lightSize:Int=128,val ended:Boolean=false,val frames:Long=0
)

data class PreviewState(
    val video:VideoInfo?=null, val positionMs:Long=0, val frame:Bitmap?=null,
    val restored:Bitmap?=null, val region:Region=Region.Center, val candidates:List<Candidate> = emptyList(),
    val busy:Boolean=false, val stage:String="", val error:String?=null, val notice:String?=null,
    val backend:Backend=Backend.CPU, val resultBackend:Backend?=null, val elapsedMs:Long?=null,
    val comparing:Boolean=false, val manualMode:Boolean=false, val live:LiveState=LiveState(), val fastAvailable:Boolean=false, val gpuRetiring:Boolean=false
)

class PreviewViewModel(application:Application):AndroidViewModel(application) {
    private var executor=Executors.newSingleThreadExecutor()
    private var dispatcher=executor.asCoroutineDispatcher()
    private var engine=PreviewEngine(application)
    private var liveJob:Job?=null
    private var liveCancelled=AtomicBoolean(false)
    private val playbackGeneration=PlaybackGeneration()
    private val preferences=application.getSharedPreferences("preview-session",0)
    var state by mutableStateOf(PreviewState())
        private set
    init {
        state=state.copy(fastAvailable=engine.fastAvailable())
        if(preferences.getBoolean("gpu_pending",false)) {
            preferences.edit().putBoolean("gpu_pending",false).apply()
            state=state.copy(notice="上次 GPU 任务未正常完成，本次已使用 CPU。你仍可手动开启 WebGPU。")
        }
    }
    private fun operation(label:String,gpu:Boolean=false,onDone:()->Unit={},work:suspend ()->Unit) {
        if(state.busy)return
        viewModelScope.launch {
            state=state.copy(busy=true,stage=label,error=null,notice=null)
            if(gpu) preferences.edit().putBoolean("gpu_pending",true).commit()
            var success=false
            try { work();success=true }
            catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                Log.e("LadaPreview","Operation failed",error)
                val detail=error.message.orEmpty().take(220)
                state=state.copy(error=if(gpu) "WebGPU 处理未完成，请切回 CPU 再试。$detail" else detail.ifBlank { "处理失败，请重试或换一个视频" })
            } finally {
                if(gpu) preferences.edit().putBoolean("gpu_pending",false).apply()
                state=state.copy(busy=false,stage="")
            }
            if(success)onDone()
        }
    }
    private suspend fun open(uri:Uri,position:Long=0) {
        val pair=withContext(dispatcher) { val info=engine.inspect(uri); info to engine.frame(info,position.coerceAtMost(info.durationMs-1)) }
        val region=Region.Center.square(pair.second.width,pair.second.height).normalized(pair.second.width,pair.second.height)
        state=state.copy(video=pair.first,positionMs=position.coerceAtMost(pair.first.durationMs-1),frame=pair.second,region=region,restored=null,candidates=emptyList(),elapsedMs=null,resultBackend=null,comparing=false,manualMode=false)
        Log.i("LadaPreview","VIDEO_READY ${pair.second.width}x${pair.second.height} duration=${pair.first.durationMs}")
    }
    fun openVideo(uri:Uri)=operation("正在读取视频…") { open(uri) }
    fun loadDemo(autoRestore:Boolean=false,autoLive:Boolean=false,quality:LiveQuality=LiveQuality.ORIGINAL,automatic:Boolean=true,requestedBackend:Backend?=null)=operation("正在打开演示视频…",onDone={if(autoLive){chooseQuality(quality);setAutoDetect(automatic);startLive()}else if(autoRestore){setManualMode(!automatic);restore()}}) {
        if(requestedBackend!=null){withContext(dispatcher){engine.setBackend(requestedBackend)};state=state.copy(backend=requestedBackend)}
        val file=withContext(dispatcher) {
            File(getApplication<Application>().cacheDir,"demo/demo.mp4").also { destination ->
                destination.parentFile?.mkdirs()
                getApplication<Application>().assets.open("demo.mp4").use { input -> destination.outputStream().use { input.copyTo(it) } }
            }
        }
        open(FileProvider.getUriForFile(getApplication(),"${getApplication<Application>().packageName}.files",file),2000)
    }
    fun seek(position:Long) {
        val video=state.video ?: return
        operation("正在读取这一帧…") {
            val time=position.coerceIn(0,maxOf(0,video.durationMs-1))
            val frame=withContext(dispatcher) { engine.frame(video,time) }
            state=state.copy(positionMs=time,frame=frame,region=state.region.square(frame.width,frame.height).normalized(frame.width,frame.height),restored=null,candidates=emptyList(),elapsedMs=null,resultBackend=null,comparing=false)
        }
    }
    fun setRegion(region:Region) {
        val image=state.frame ?: return
        if(state.busy)return
        state=state.copy(region=region.square(image.width,image.height).normalized(image.width,image.height),restored=null,elapsedMs=null,resultBackend=null,manualMode=true,comparing=false)
    }
    fun setManualMode(value:Boolean){if(!state.busy)state=state.copy(manualMode=value,comparing=false)}
    fun setComparing(value:Boolean) { state=state.copy(comparing=value) }
    fun setBackend(backend:Backend) {
        if(state.backend==backend || state.busy || state.live.running)return
        if(backend==Backend.WEBGPU&&state.gpuRetiring){state=state.copy(notice="上一次 GPU 任务仍在后台释放，请先使用 CPU。");return}
        operation("正在切换处理后端…",gpu=state.backend==Backend.WEBGPU) {
            withContext(dispatcher) { check(backend!=Backend.WEBGPU || engine.webGpuAvailable()) { "此安装包的运行库未提供 WebGPU" }; engine.setBackend(backend) }
            state=state.copy(backend=backend,notice=if(backend==Backend.WEBGPU) "WebGPU 已选择。首次准备可能较慢，部分算子仍可能使用 CPU。" else "已切回 CPU")
        }
    }
    fun detect() {
        val image=state.frame ?: return
        operation(if(state.backend==Backend.WEBGPU) "正在准备 WebGPU 并检测…" else "正在自动框选…",gpu=state.backend==Backend.WEBGPU) {
            val found=withContext(dispatcher) { engine.detect(image) }
            state=state.copy(candidates=found,region=found.firstOrNull()?.region ?: state.region,restored=null,elapsedMs=null,resultBackend=null,
                notice=if(found.isEmpty()) "未找到自动识别的区域，请直接在画面上拖动框选。" else "找到 ${found.size} 个候选区域，可点选或重新框选。")
        }
    }
    fun restore() {
        val video=state.video ?: return
        val position=state.positionMs;val backend=state.backend
        val quality=state.live.quality;val lightSize=state.live.lightSize
        val manual=if(state.manualMode)state.region else null
        operation(if(manual==null)"正在自动识别并修复…" else "正在修复所选区域…",gpu=backend==Backend.WEBGPU) {
            val result=withContext(dispatcher) {
                engine.restoreFrame(video,position,quality,lightSize,manual,
                    { stage -> viewModelScope.launch { state=state.copy(stage=stage) } },
                    { frame,found -> viewModelScope.launch {state=state.copy(frame=frame,candidates=found,region=found.firstOrNull()?.region?:state.region,restored=null,comparing=false)} })
            }
            state=state.copy(frame=result.original,restored=result.restored,candidates=result.candidates,
                comparing=result.restored!=null,elapsedMs=if(result.restored!=null)result.elapsedMs else null,resultBackend=if(result.restored!=null)backend else null,
                notice=if(result.restored==null)"这一帧未识别到马赛克区域，可换一个时间点再试。" else "已修复 ${result.candidates.size} 个区域。")
            Log.i("LadaPreview","RESTORE_READY backend=${backend.name} quality=${quality.name} light_size=$lightSize regions=${result.candidates.size} elapsed_ms=${result.elapsedMs}")
        }
    }
    fun save(uri:Uri) {
        val result=state.restored ?: return
        operation("正在保存当前帧…") {
            withContext(dispatcher) {
                val stream=getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("无法写入所选位置")
                stream.use { check(result.compress(Bitmap.CompressFormat.PNG,100,it)) { "保存图片失败" } }
            }
            state=state.copy(notice="当前修复帧已保存")
        }
    }

    fun chooseQuality(quality:LiveQuality) {
        if(state.live.running || state.busy)return
        if(quality==LiveQuality.FAST&&!state.fastAvailable) {state=state.copy(error="此安装包未包含轻量模型");return}
        state=state.copy(live=state.live.copy(quality=quality))
    }
    fun seekLive(position:Long) {
        val video=state.video?:return
        if(state.live.running||state.busy)return
        operation("正在读取时间点…") {
            val time=position.coerceIn(0,maxOf(0,video.durationMs-1))
            val frame=withContext(dispatcher){engine.frame(video,time)}
            state=state.copy(frame=frame,positionMs=time,live=state.live.copy(frame=frame,original=frame,positionMs=time,regions=emptyList(),ended=false,status="待处理帧"))
        }
    }
    fun setLightSize(size:Int) {
        if(state.live.running||state.busy)return
        lightModelShape(size)
        state=state.copy(live=state.live.copy(lightSize=size))
    }
    fun setAutoDetect(value:Boolean) {state=state.copy(live=state.live.copy(autoDetect=value))}
    fun setLiveFullscreen(value:Boolean) {state=state.copy(live=state.live.copy(fullscreen=value))}
    fun stopLive(exit:Boolean=false) {
        val wasRunning=state.live.running||playbackGeneration.isRunning()
        if(!wasRunning) {
            if(exit) {
                val live=state.live
                state=state.copy(live=live.copy(visible=false,fullscreen=false),frame=live.original?:state.frame,
                    restored=if(live.regions.isNotEmpty())live.frame else state.restored,positionMs=live.positionMs)
            }
            return
        }
        playbackGeneration.stop() // Invalidate callbacks before cancelling native work.
        liveCancelled.set(true)
        val retiring=liveJob
        liveJob=null
        val live=state.live
        // UI must not wait for GPU compilation, decoder release or coroutine finally.
        state=state.copy(busy=false,stage="",live=live.copy(running=false,buffering=false,
            visible=if(exit)false else live.visible,fullscreen=if(exit)false else live.fullscreen,status="已暂停"),
            frame=if(exit)live.original?:state.frame else state.frame,
            restored=if(exit&&live.regions.isNotEmpty())live.frame else state.restored,
            positionMs=if(exit)live.positionMs else state.positionMs)
        if(!wasRunning)return
        val oldEngine=engine;val oldDispatcher=dispatcher
        val wasGpu=state.backend==Backend.WEBGPU
        oldEngine.cancelCurrent()
        retiring?.cancel()
        // A driver may ignore termination while compiling. Isolate it from the next CPU request.
        executor=Executors.newSingleThreadExecutor()
        dispatcher=executor.asCoroutineDispatcher()
        engine=PreviewEngine(getApplication())
        if(wasGpu)state=state.copy(backend=Backend.CPU,gpuRetiring=true,notice="已停止播放。GPU 正在后台释放，已切回 CPU。")
        preferences.edit().putBoolean("gpu_pending",false).apply()
        CoroutineScope(Dispatchers.Default).launch {
            try {retiring?.join();withContext(oldDispatcher){oldEngine.close()}}
            finally {
                oldDispatcher.close()
                if(wasGpu)withContext(Dispatchers.Main){state=state.copy(gpuRetiring=false)}
            }
        }
    }
    fun startLive() {
        val video=state.video ?: return
        if(state.busy||state.live.running)return
        val quality=state.live.quality;val lightSize=state.live.lightSize;val selectedBackend=state.backend
        val starting=if(state.live.ended)0L else if(state.live.visible)state.live.positionMs else state.positionMs
        val runId=playbackGeneration.begin()
        val cancelled=AtomicBoolean(false)
        liveCancelled=cancelled
        val runEngine=engine
        val runDispatcher=dispatcher
        fun publish(update:()->Unit){if(playbackGeneration.isCurrent(runId))update()}
        state=state.copy(error=null,notice=null,live=state.live.copy(visible=true,running=true,buffering=true,ended=false,
            positionMs=starting,frame=null,original=null,fps=0.0,frames=0,status="正在准备视频与模型…"))
        liveJob=viewModelScope.launch {
            var decoder:StreamingDecoder?=null
            if(selectedBackend==Backend.WEBGPU)preferences.edit().putBoolean("gpu_pending",true).commit()
            val started=SystemClock.elapsedRealtime();var rendered=0L;var lastPresentation=starting;var lastWall=0L
            var previousRegions=emptyList<Region>();var sinceDetection=100
            try {
                decoder=withContext(runDispatcher) {StreamingDecoder(getApplication(),video.uri,starting)}
                while(isActive&&!cancelled.get()) {
                    val automatic=state.live.autoDetect;val manual=state.region
                    publish{state=state.copy(live=state.live.copy(buffering=true,status="正在识别与修复…"))}
                    val result=withContext(runDispatcher) {
                        val frames=mutableListOf<VideoFrame>()
                        repeat(if(quality==LiveQuality.ORIGINAL)3 else 1) {
                            if(!cancelled.get())decoder!!.next {cancelled.get()}?.let(frames::add)
                        }
                        if(frames.isEmpty())null else {
                            val regions=if(!automatic) listOf(manual) else if(sinceDetection>=3||previousRegions.isEmpty()) {
                                sinceDetection=0
                                runEngine.detect(frames.first().bitmap,live=true).map { it.region }
                            } else previousRegions
                            previousRegions=regions;sinceDetection+=frames.size
                            val processed=runEngine.repairBatch(frames.map{it.bitmap},regions,quality,lightSize)
                            Triple(frames,processed,regions)
                        }
                    }
                    if(result==null) {publish{state=state.copy(live=state.live.copy(ended=true,status="已到片尾"))};break}
                    for(index in result.first.indices) {
                        ensureActive();if(cancelled.get())break
                        val source=result.first[index]
                        val gap=(source.timeMs-lastPresentation).coerceIn(0L,150L)
                        if(lastWall>0)delay((lastWall+gap-SystemClock.elapsedRealtime()).coerceAtLeast(0))
                        rendered++;lastWall=SystemClock.elapsedRealtime();lastPresentation=source.timeMs
                        val fps=rendered*1000.0/(lastWall-started).coerceAtLeast(1)
                        publish{state=state.copy(live=state.live.copy(frame=result.second[index],original=source.bitmap,positionMs=source.timeMs,
                            regions=result.third,buffering=false,fps=fps,frames=rendered,
                            status=if(result.third.isEmpty())"本帧未识别到马赛克区域" else "已处理 ${result.third.size} 个区域"))}
                        if(rendered==1L||rendered%30==0L)Log.i("LadaPreview","LIVE_FRAME quality=${quality.name} backend=${selectedBackend.name} frames=$rendered time_ms=${source.timeMs} regions=${result.third.size} fps=$fps")
                    }
                }
            } catch(error:CancellationException) {
                // The current native operation is allowed to finish; no session is closed underneath it.
            } catch(error:Exception) {
                Log.e("LadaPreview","Continuous playback failed",error)
                publish{state=state.copy(error=error.message.orEmpty().take(220).ifBlank{"实时预览失败，请切换 CPU 或使用选帧预览"},live=state.live.copy(status="处理已停止"))}
            } finally {
                withContext(NonCancellable+runDispatcher) {decoder?.close()}
                if(playbackGeneration.finish(runId)) {
                    if(selectedBackend==Backend.WEBGPU)preferences.edit().putBoolean("gpu_pending",false).apply()
                    state=state.copy(busy=false,stage="",live=state.live.copy(running=false,buffering=false,
                        status=if(state.live.ended)"已到片尾" else if(state.error!=null)"处理已停止" else "已暂停"))
                }
                Log.i("LadaPreview","LIVE_STOP frames=$rendered backend=${selectedBackend.name}")
            }
        }
    }

    fun dismissMessage() { state=state.copy(error=null,notice=null) }
    override fun onCleared() {
        playbackGeneration.stop()
        liveCancelled.set(true)
        engine.cancelCurrent()
        liveJob?.cancel()
        val pending=liveJob
        val closingEngine=engine
        val closingDispatcher=dispatcher
        CoroutineScope(Dispatchers.Default).launch {
            pending?.join()
            withContext(closingDispatcher){closingEngine.close()}
            closingDispatcher.close()
        }
        super.onCleared()
    }
}
