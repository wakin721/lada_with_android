package com.wakin.lada

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.*

class MainActivity:ComponentActivity() {
    private var activeModel:PreviewViewModel?=null
    override fun onStop(){super.onStop();if(!isChangingConfigurations)activeModel?.stopLive()}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model:PreviewViewModel=viewModel()
            SideEffect{activeModel=model}
            val keepAwake=model.state.busy||model.state.live.running
            DisposableEffect(keepAwake){
                if(keepAwake)window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose{window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
            }
            LaunchedEffect(Unit) {
                if(BuildConfig.DEBUG && intent.getBooleanExtra("demo",false)) model.loadDemo(intent.getBooleanExtra("restore",false),intent.getBooleanExtra("live",false),if(intent.getStringExtra("quality")=="fast")LiveQuality.FAST else LiveQuality.ORIGINAL,!intent.getBooleanExtra("manual",false),if(intent.getStringExtra("backend")=="webgpu")Backend.WEBGPU else null)
            }
            LadaTheme { PreviewScreen(model) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable private fun LadaTheme(content:@Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    val colors=if(dark) darkColorScheme(
        primary=Color(0xffd0bcff),onPrimary=Color(0xff381e72),primaryContainer=Color(0xff4f378b),
        secondary=Color(0xff9fd6c1),surface=Color(0xff141218),surfaceContainer=Color(0xff211e28),surfaceContainerHigh=Color(0xff2c2833)
    ) else lightColorScheme(
        primary=Color(0xff6250b7),onPrimary=Color.White,primaryContainer=Color(0xffe9ddff),onPrimaryContainer=Color(0xff24124c),
        secondary=Color(0xff306a58),secondaryContainer=Color(0xffb8eed7),surface=Color(0xfffcf8ff),surfaceContainer=Color(0xfff1ebf8),surfaceContainerHigh=Color(0xffeae4f0)
    )
    MaterialExpressiveTheme(colorScheme=colors, motionScheme=MotionScheme.expressive(),
        shapes=Shapes(extraLarge=RoundedCornerShape(32.dp),large=RoundedCornerShape(24.dp)), content=content)
}

private fun timeLabel(ms:Long):String {
    val seconds=ms/1000
    return String.format(Locale.ROOT,"%02d:%02d.%d",seconds/60,seconds%60,(ms%1000)/100)
}
@Composable private fun AppIcon(id:Int,size:Int=22) { Icon(painterResource(id),contentDescription=null,modifier=Modifier.size(size.dp)) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable private fun PreviewScreen(model:PreviewViewModel) {
    val s=model.state
    val context=LocalContext.current
    var about by remember { mutableStateOf(false) }
    var playing by remember(s.video?.uri) { mutableStateOf(false) }
    var player by remember { mutableStateOf<VideoView?>(null) }
    var comparison by remember(s.restored) { mutableFloatStateOf(.5f) }
    val videoPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            model.openVideo(uri)
        }
    }
    val savePicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri -> if(uri!=null)model.save(uri) }
    val chooseVideo={ playing=false; videoPicker.launch(arrayOf("video/*")) }
    if(s.live.visible){RealtimePlayer(model);return}
    Scaffold(
        topBar={ Surface(color=MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=22.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.size(48.dp)) {
                    Box(contentAlignment=Alignment.Center) { AppIcon(R.drawable.ic_video,27) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lada",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("视频修复预览",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick={about=true}) { AppIcon(R.drawable.ic_info) }
            }
        } },
        bottomBar={ Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=2.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=20.dp,vertical=12.dp)) {
                if(s.busy) Row(Modifier.padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    Spacer(Modifier.width(10.dp)); Text(s.stage,style=MaterialTheme.typography.bodyMedium)
                }
                Button(onClick={playing=false;model.restore()},enabled=s.frame!=null&&!s.busy&&!playing,modifier=Modifier.fillMaxWidth().height(58.dp),shape=RoundedCornerShape(22.dp)) {
                    AppIcon(R.drawable.ic_magic);Spacer(Modifier.width(8.dp));Text(if(s.manualMode)"修复所选区域" else "自动识别并修复",fontWeight=FontWeight.SemiBold)
                }
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Column(Modifier.padding(top=8.dp,bottom=2.dp)) {
                Text(if(s.video==null) "从一段视频开始" else "选好这一帧，看看变化",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("本地处理 · 框选区域 · 前后对比",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(s.video==null) {
                Surface(shape=RoundedCornerShape(32.dp),color=MaterialTheme.colorScheme.primaryContainer,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(12.dp)); AppIcon(R.drawable.ic_video,52); Spacer(Modifier.height(18.dp))
                        Text("导入你想查看的视频",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                        Text("先预览当前帧的修复效果",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=8.dp,bottom=22.dp))
                        Button(onClick=chooseVideo,enabled=!s.busy,modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("选择视频") }
                        TextButton(onClick={model.loadDemo()},enabled=!s.busy) { Text("先试用演示视频") }
                    }
                }
            } else {
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,top=8.dp,bottom=8.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_video)
                        Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
                            Text(s.video.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                            Text("${s.video.width} × ${s.video.height} · ${timeLabel(s.video.durationMs)}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick=chooseVideo,enabled=!s.busy) { Text("更换") }
                    }
                }
                Surface(shape=RoundedCornerShape(30.dp),color=Color(0xff16131c),modifier=Modifier.fillMaxWidth()) {
                    if(playing) {
                        AndroidView(
                            factory={ c -> VideoView(c).also { view ->
                                player=view
                                val controller=MediaController(c);controller.setAnchorView(view);view.setMediaController(controller)
                                view.setVideoURI(s.video.uri)
                                view.setOnPreparedListener { view.seekTo(s.positionMs.toInt());view.start() }
                                view.setOnCompletionListener { playing=false;model.seek(view.currentPosition.toLong()) }
                            } },
                            modifier=Modifier.fillMaxWidth().height(270.dp),
                            onRelease={it.stopPlayback();player=null}
                        )
                    } else {
                        FrameViewport(s,comparison,{comparison=it},model::setRegion)
                    }
                }
                var seek by remember(s.video.uri,s.positionMs) { mutableFloatStateOf(s.positionMs.toFloat()) }
                Column {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(timeLabel(if(playing)s.positionMs else seek.toLong()),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        Text(" / ${timeLabel(s.video.durationMs)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(onClick={
                            if(playing) { val position=player?.currentPosition?.toLong() ?: s.positionMs;player?.pause();playing=false;model.seek(position) }
                            else playing=true
                        },enabled=!s.busy) { AppIcon(if(playing)R.drawable.ic_pause else R.drawable.ic_play) }
                    }
                    Slider(value=seek,onValueChange={seek=it},onValueChangeFinished={model.seek(seek.toLong())},valueRange=0f..max(1f,s.video.durationMs.toFloat()-1),enabled=!s.busy&&!playing,modifier=Modifier.fillMaxWidth())
                    Text(if(playing) "正在播放原视频，暂停后可框选修复" else if(s.restored!=null&&s.comparing) "左右拖动分界线，查看同一帧的变化" else if(s.manualMode) "在画面上拖动，重新框选修复区域" else "点击下方按钮，自动识别并修复这一帧",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    FilterChip(selected=!s.manualMode&&!s.comparing,onClick={model.setManualMode(false)},label={Text("自动识别")},enabled=!s.busy&&!playing)
                    FilterChip(selected=s.manualMode&&!s.comparing,onClick={model.setManualMode(true)},label={Text("手动框选")},enabled=!s.busy&&!playing)
                    FilterChip(selected=s.comparing&&s.restored!=null,onClick={model.setComparing(true)},label={Text("前后对比")},enabled=s.restored!=null&&!s.busy&&!playing)
                    Spacer(Modifier.weight(1f))
                    if(s.restored!=null) IconButton(onClick={savePicker.launch("Lada-${s.positionMs}ms.png")},enabled=!s.busy) { Icon(painterResource(R.drawable.ic_save),contentDescription="保存当前修复帧") }
                }
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("修复模式",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        LiveQualityOptions(model)
                    }
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick={playing=false;model.startLive()},enabled=!s.busy,modifier=Modifier.weight(1f)) {Text("实时识别与修复")}
                    OutlinedButton(onClick={playing=false;context.startActivity(Intent(context,FullscreenVideoActivity::class.java).putExtra("uri",s.video.uri.toString()).putExtra("position",s.positionMs.toInt()))},enabled=!s.busy) {Text("全屏原片")}
                }
                if(s.candidates.size>1) {
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        s.candidates.take(4).forEachIndexed { index,candidate ->
                            FilterChip(selected=s.region==candidate.region,onClick={model.setRegion(candidate.region)},label={Text("区域 ${index+1}")},enabled=!s.busy)
                        }
                    }
                }
                if(s.elapsedMs!=null) Surface(shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("这一帧已修复",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        Text("${s.resultBackend?.title} · ${String.format(Locale.ROOT,"%.2f",s.elapsedMs/1000.0)} 秒 · 可保存当前帧",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Surface(shape=RoundedCornerShape(26.dp),color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("WebGPU 加速",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            Text(if(s.backend==Backend.WEBGPU) "已选择 WebGPU · 实验功能" else "当前使用 CPU · 已验证",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked=s.backend==Backend.WEBGPU,onCheckedChange={model.setBackend(if(it)Backend.WEBGPU else Backend.CPU)},enabled=!s.busy)
                    }
                    Text("首次准备可能较慢；部分运算仍可能使用 CPU。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=6.dp))
                }
            }
            AnimatedVisibility(s.error!=null||s.notice!=null) {
                Surface(shape=RoundedCornerShape(22.dp),color=if(s.error!=null)MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(start=16.dp,top=10.dp,bottom=10.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(s.error?:s.notice.orEmpty(),modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                        TextButton(onClick=model::dismissMessage) { Text("关闭") }
                    }
                }
            }
            Text("可选帧对比或连续修复预览，不会改写原视频。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=18.dp))
        }
    }
    if(about) AlertDialog(onDismissRequest={about=false},confirmButton={TextButton(onClick={about=false}) {Text("知道了")}},title={Text("关于预览版")},text={Text("使用 Lada 原版视频修复权重，通过相邻三帧修复所选区域。\n\n支持选帧对比、自动识别与连续修复预览、全屏播放和保存图片；尚未导出修复视频文件。\n\nWebGPU 是实验选项，启用不代表所有运算都在 GPU 上执行。关闭开关可恢复 CPU。\n\nLada 模型遵循 AGPL-3.0，来源 ladaapp/lada。")})
}

@Composable private fun FrameViewport(state:PreviewState,fraction:Float,onFraction:(Float)->Unit,onRegion:(Region)->Unit) {
    val bitmap=state.frame
    val image=remember(bitmap) { bitmap?.asImageBitmap() }
    val repaired=remember(state.restored) { state.restored?.asImageBitmap() }
    var draft by remember(bitmap,state.region) { mutableStateOf<Region?>(null) }
    val selected=draft?:state.region
    val compare=repaired!=null&&state.comparing
    Box(Modifier.fillMaxWidth().height(270.dp)) {
        if(image==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
        else {
            Canvas(Modifier.fillMaxSize().semantics {contentDescription="视频当前帧，可拖动框选区域或移动对比分界线"}.pointerInput(bitmap,state.region,compare,state.busy,state.manualMode) {
                var start=Offset.Zero;var active=false
                fun imageRect():Rect {
                    val scale=min(size.width.toFloat()/image.width,size.height.toFloat()/image.height)
                    val w=image.width*scale;val h=image.height*scale
                    return Rect((size.width-w)/2,(size.height-h)/2,(size.width+w)/2,(size.height+h)/2)
                }
                fun normalized(point:Offset,r:Rect)=Offset(((point.x-r.left)/r.width).coerceIn(0f,1f),((point.y-r.top)/r.height).coerceIn(0f,1f))
                detectDragGestures(
                    onDragStart={ point -> val r=imageRect();active=!state.busy&&r.contains(point)&&(compare||state.manualMode);start=normalized(point,r);if(active&&compare)onFraction(start.x) },
                    onDrag={change,_ -> if(active) {
                        change.consume();val current=normalized(change.position,imageRect())
                        if(compare)onFraction(current.x) else draft=Region(min(start.x,current.x),min(start.y,current.y),max(start.x,current.x),max(start.y,current.y))
                    } },
                    onDragEnd={if(active&&!compare) {draft?.let(onRegion);draft=null};active=false},
                    onDragCancel={draft=null;active=false}
                )
            }) {
                val scale=min(size.width/image.width,size.height/image.height)
                val w=image.width*scale;val h=image.height*scale;val x=(size.width-w)/2;val y=(size.height-h)/2
                val offset=IntOffset(x.roundToInt(),y.roundToInt());val target=IntSize(w.roundToInt(),h.roundToInt())
                drawImage(image,dstOffset=offset,dstSize=target)
                if(compare&&repaired!=null) {
                    val divider=x+w*fraction
                    clipRect(left=divider,top=y,right=x+w,bottom=y+h) {drawImage(repaired,dstOffset=offset,dstSize=target)}
                    drawLine(Color.White,Offset(divider,y),Offset(divider,y+h),3.dp.toPx())
                    drawCircle(Color.White,13.dp.toPx(),Offset(divider,y+h/2))
                    drawLine(Color(0xff6250b7),Offset(divider-4.dp.toPx(),y+h/2-4.dp.toPx()),Offset(divider-4.dp.toPx(),y+h/2+4.dp.toPx()),2.dp.toPx())
                    drawLine(Color(0xff6250b7),Offset(divider+4.dp.toPx(),y+h/2-4.dp.toPx()),Offset(divider+4.dp.toPx(),y+h/2+4.dp.toPx()),2.dp.toPx())
                } else if(state.manualMode) {
                    val left=x+selected.left*w;val top=y+selected.top*h;val right=x+selected.right*w;val bottom=y+selected.bottom*h
                    drawRect(Color.Black.copy(alpha=.38f),Offset(x,y),Size(w,max(0f,top-y)))
                    drawRect(Color.Black.copy(alpha=.38f),Offset(x,bottom),Size(w,max(0f,y+h-bottom)))
                    drawRect(Color.Black.copy(alpha=.38f),Offset(x,top),Size(max(0f,left-x),max(0f,bottom-top)))
                    drawRect(Color.Black.copy(alpha=.38f),Offset(right,top),Size(max(0f,x+w-right),max(0f,bottom-top)))
                    drawRect(Color(0xffd0bcff),Offset(left,top),Size(max(1f,right-left),max(1f,bottom-top)),style=Stroke(2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(14f,7f))))
                } else {
                    for(candidate in state.candidates) {
                        val r=candidate.region
                        drawRect(Color(0xffb8eed7),Offset(x+r.left*w,y+r.top*h),Size((r.right-r.left)*w,(r.bottom-r.top)*h),style=Stroke(2.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                SmallLabel(if(compare) "原始" else "当前帧")
                SmallLabel(if(compare) "修复后" else if(state.manualMode) "拖动框选" else "自动识别")
            }
        }
    }
}
@Composable private fun SmallLabel(label:String) {
    Surface(color=Color.Black.copy(alpha=.55f),contentColor=Color.White,shape=RoundedCornerShape(10.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=10.dp,vertical=5.dp))
    }
}
