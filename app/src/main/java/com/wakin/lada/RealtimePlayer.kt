package com.wakin.lada

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.math.*

@Composable fun RealtimePlayer(model:PreviewViewModel) {
    MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xffd0bcff),secondary=Color(0xffb8eed7))) {RealtimePlayerContent(model)}
}
@Composable private fun RealtimePlayerContent(model:PreviewViewModel) {
    val state=model.state;val live=state.live
    val activity=LocalContext.current as Activity
    var boxes by remember {mutableStateOf(true)}
    var controls by remember {mutableStateOf(true)}
    LaunchedEffect(live.fullscreen,controls,live.running){if(live.fullscreen&&controls&&live.running){delay(3500);controls=false}}
    fun fullscreen(value:Boolean) {
        controls=true
        model.setLiveFullscreen(value)
        activity.requestedOrientation=if(value)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    fun leave() {if(live.fullscreen)fullscreen(false);model.stopLive(exit=true)}
    BackHandler {if(live.fullscreen)fullscreen(false) else leave()}
    DisposableEffect(live.fullscreen) {
        val bars=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
        if(live.fullscreen) {bars.hide(WindowInsetsCompat.Type.systemBars());bars.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}
        else bars.show(WindowInsetsCompat.Type.systemBars())
        onDispose {bars.show(WindowInsetsCompat.Type.systemBars())}
    }
    val bitmap=live.frame?:state.frame
    val image=remember(bitmap){bitmap?.asImageBitmap()}
    Column(Modifier.fillMaxSize().background(Color(0xff100e14)).then(if(live.fullscreen)Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
        if(!live.fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=::leave) {Text("返回预览")}
            Text("实时修复",color=Color.White,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            Text(if(live.quality==LiveQuality.FAST)"${live.quality.title} ${live.lightSize}" else live.quality.title,color=Color(0xffd0bcff),style=MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(live.fullscreen){detectTapGestures{if(live.fullscreen)controls=!controls}}) {
            if(image!=null) Canvas(Modifier.fillMaxSize()) {
                val scale=min(size.width/image.width,size.height/image.height)
                val w=image.width*scale;val h=image.height*scale;val x=(size.width-w)/2;val y=(size.height-h)/2
                drawImage(image,dstOffset=IntOffset(x.roundToInt(),y.roundToInt()),dstSize=IntSize(w.roundToInt(),h.roundToInt()))
                if(boxes)for(region in live.regions)drawRect(Color(0xffa8f0d1),Offset(x+region.left*w,y+region.top*h),Size((region.right-region.left)*w,(region.bottom-region.top)*h),style=Stroke(2.dp.toPx()))
            }
            if(!live.fullscreen||controls) Row(Modifier.align(Alignment.TopStart).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Surface(color=Color.Black.copy(alpha=.65f),shape=RoundedCornerShape(10.dp),contentColor=Color.White) {
                    Text("${state.backend.title} · ${String.format(Locale.ROOT,"%.1f",live.fps)} fps",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)
                }
                Surface(color=Color.Black.copy(alpha=.65f),shape=RoundedCornerShape(10.dp),contentColor=Color.White) {
                    Text("静音修复预览",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium)
                }
            }
            if(live.buffering) Surface(Modifier.align(Alignment.Center),color=Color.Black.copy(alpha=.75f),shape=RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                    Spacer(Modifier.width(12.dp));Text(live.status,color=Color.White)
                }
            }
            if(live.fullscreen&&controls) TextButton(onClick={fullscreen(false)},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp)) {Text("退出全屏",color=Color.White)}
            if(live.fullscreen&&controls) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()){PlayerControls(model,boxes,{boxes=it},::fullscreen)}
        }
        if(!live.fullscreen) PlayerControls(model,boxes,{boxes=it},::fullscreen)
    }
}
private fun formatPlayerTime(ms:Long)=String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60)

@Composable private fun PlayerControls(model:PreviewViewModel,boxes:Boolean,onBoxes:(Boolean)->Unit,onFullscreen:(Boolean)->Unit){
    val state=model.state;val live=state.live
        Surface(color=Color(0xff24202d).copy(alpha=if(live.fullscreen).86f else 1f),contentColor=Color.White,shape=if(live.fullscreen)RoundedCornerShape(0.dp) else RoundedCornerShape(topStart=24.dp,topEnd=24.dp)) {
            Column(Modifier.padding(horizontal=18.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(formatPlayerTime(live.positionMs),fontWeight=FontWeight.Bold)
                    Text(" / ${formatPlayerTime(state.video?.durationMs?:0)}",color=Color.LightGray,style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text(live.status,style=MaterialTheme.typography.labelSmall,color=Color(0xffb8eed7))
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick={onFullscreen(!live.fullscreen)}) {Icon(painterResource(R.drawable.ic_fullscreen),contentDescription="切换全屏",tint=Color.White)}
                }
                if(!live.fullscreen) {
                    var seek by remember(live.positionMs){mutableFloatStateOf(live.positionMs.toFloat())}
                    Slider(value=seek,onValueChange={seek=it},onValueChangeFinished={model.seekLive(seek.toLong())},valueRange=0f..max(1f,(state.video?.durationMs?:1).toFloat()-1),enabled=!live.running&&!state.busy)
                    LiveQualityOptions(model)
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("自动识别区域",modifier=Modifier.weight(1f),style=MaterialTheme.typography.labelMedium)
                        Switch(checked=live.autoDetect,onCheckedChange=model::setAutoDetect,enabled=!state.busy)
                    }
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("WebGPU",modifier=Modifier.weight(1f));Switch(checked=state.backend==Backend.WEBGPU,onCheckedChange={model.setBackend(if(it)Backend.WEBGPU else Backend.CPU)},enabled=!live.running&&!state.busy)
                        Spacer(Modifier.width(18.dp));Text("显示框");Switch(checked=boxes,onCheckedChange=onBoxes)
                    }
                }
                state.error?.let {Text(it,color=Color(0xffffb4ab),style=MaterialTheme.typography.bodySmall,maxLines=3)}
                state.notice?.let {Text(it,color=Color(0xffb8eed7),style=MaterialTheme.typography.bodySmall,maxLines=3)}
                Button(onClick={if(live.running)model.stopLive() else model.startLive()},enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(if(live.fullscreen)42.dp else 50.dp),shape=RoundedCornerShape(18.dp)) {
                    Text(if(live.running)"暂停修复" else if(live.ended)"重新播放并修复" else "继续修复")
                }
            }
        }
}

@Composable fun LiveQualityOptions(model:PreviewViewModel) {
    val state=model.state;val live=state.live
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=live.quality==LiveQuality.ORIGINAL,onClick={model.chooseQuality(LiveQuality.ORIGINAL)},label={Text("原版画质")},enabled=!live.running&&!state.busy)
            if(state.fastAvailable)FilterChip(selected=live.quality==LiveQuality.FAST,onClick={model.chooseQuality(LiveQuality.FAST)},label={Text("轻量流畅")},enabled=!live.running&&!state.busy)
        }
        if(live.quality==LiveQuality.FAST) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=live.lightSize==128,onClick={model.setLightSize(128)},label={Text("128 × 128 · 更快")},enabled=!live.running&&!state.busy)
                FilterChip(selected=live.lightSize==256,onClick={model.setLightSize(256)},label={Text("256 × 256 · 更细")},enabled=!live.running&&!state.busy)
            }
        }
        Text(if(live.quality==LiveQuality.FAST) "轻量模型来自手机原型，效果与原版 Lada 不同。" else "原版 Lada：处理赶不上时缓冲等待。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(live.running)Text("暂停后可切换模型和输入尺寸",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
