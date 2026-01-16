package com.rcbs.nexcam

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CaptureRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rcbs.nexcam.ui.theme.NexCamTheme
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

import androidx.compose.ui.graphics.Color as ComposeColor

enum class Protocol { HTTP_MJPEG }
enum class Resolution { R_180P, R_240P, R_480P, R_720P, R_960P, R_1080P }
enum class OrientationMode { PORTRAIT, LANDSCAPE }

data class CameraSettings(
    val watermark: Boolean = false,
    val timestamp: Boolean = true,
    val text: String = "NexCam",
    val res: Resolution = Resolution.R_480P,
    val ev: Int = 0,
    val hdr: Boolean = false,
    val night: Boolean = false,
    val fps: Int = 30,
    val protocol: Protocol = Protocol.HTTP_MJPEG,
    val orientation: OrientationMode = OrientationMode.PORTRAIT,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val saverTimeout: Int = 0,
    val serverPort: Int = 8080,
    val usePassword: Boolean = false,
    val serverPassword: String = "123456"
)

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {
    private var server: NettyApplicationEngine? = null
    private val frameFlow = MutableStateFlow<ByteArray?>(null)
    private val frameLock = Any()
    private val settingsState = mutableStateOf(CameraSettings())
    private val isLive = mutableStateOf(false)
    private val liveUrlDisplay = mutableStateOf("准备就绪")
    
    private val analysisExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(4))
    private val isProcessing = AtomicBoolean(false)

    private var renderBuffer: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private val bitmapPaint = Paint().apply { isFilterBitmap = true; isAntiAlias = false }
    
    private var previewSurfaceRef: PreviewSurface? = null
    private lateinit var sp: SharedPreferences

    private val currentFps = mutableIntStateOf(0)
    private var frameCount = 0
    private var lastFpsUpdateTime = 0L
    private var lastFrameTime = 0L

    private var cameraControl: CameraControl? = null
    private val zoomRatio = mutableFloatStateOf(1f)
    private val zoomRange = mutableStateOf(1f..10f)
    private val isFlashOn = mutableStateOf(false)

    private val isSaverActive = mutableStateOf(false)
    private var lastTouchTime = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sp = getSharedPreferences("pro_settings_vFinal_v3", MODE_PRIVATE)
        load()
        enableEdgeToEdge()
        setContent { NexCamTheme { Surface { MainContent() } } }
    }

    private fun load() {
        val oriStr = sp.getString("ori", "PORTRAIT") ?: "PORTRAIT"
        val ori = try { OrientationMode.valueOf(oriStr) } catch (e: Exception) { OrientationMode.PORTRAIT }
        val resStr = sp.getString("res", "R_480P") ?: "R_480P"
        val res = try { Resolution.valueOf(resStr) } catch (e: Exception) { Resolution.R_480P }
        
        settingsState.value = CameraSettings(
            sp.getBoolean("wm", false), sp.getBoolean("ts", true),
            sp.getString("txt", "NexCam") ?: "NexCam",
            res,
            sp.getInt("ev", 0), sp.getBoolean("hdr", false),
            sp.getBoolean("night", false), sp.getInt("fps", 30),
            Protocol.HTTP_MJPEG, ori,
            sp.getInt("lens", CameraSelector.LENS_FACING_BACK),
            sp.getInt("saver", 0),
            sp.getInt("port", 8080),
            sp.getBoolean("use_pwd", false),
            sp.getString("pwd", "123456") ?: "123456"
        )
    }

    private fun save(s: CameraSettings) {
        sp.edit().apply {
            putBoolean("wm", s.watermark); putBoolean("ts", s.timestamp)
            putString("txt", s.text); putString("res", s.res.name)
            putInt("ev", s.ev); putBoolean("hdr", s.hdr); putBoolean("night", s.night)
            putInt("fps", s.fps); putString("ori", s.orientation.name); putInt("lens", s.lensFacing)
            putInt("saver", s.saverTimeout); putInt("port", s.serverPort)
            putBoolean("use_pwd", s.usePassword); putString("pwd", s.serverPassword)
            apply()
        }
    }

    @Composable
    fun MainContent() {
        var screen by remember { mutableStateOf("cam") }
        val s = settingsState.value
        val view = LocalView.current
        val ctx = LocalContext.current
        
        // Permission Handling
        var hasCameraPermission by remember { 
            mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) 
        }
        var showPermissionDeniedDialog by remember { mutableStateOf(false) }
        
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasCameraPermission = granted
            if (!granted) showPermissionDeniedDialog = true
        }

        LaunchedEffect(Unit) {
            if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (showPermissionDeniedDialog) {
            AlertDialog(
                onDismissRequest = { (ctx as Activity).finish() },
                title = { Text("权限请求") },
                text = { Text("NexCam 需要相机权限才能正常工作。请在设置中开启权限后重新进入应用。") },
                confirmButton = { TextButton(onClick = { (ctx as Activity).finish() }) { Text("退出应用") } }
            )
        }

        if (hasCameraPermission) {
            LaunchedEffect(isSaverActive.value) {
                val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
                val controller = WindowCompat.getInsetsController(window, view)
                if (isSaverActive.value) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            LaunchedEffect(s.saverTimeout) {
                while (true) {
                    if (s.saverTimeout > 0 && !isSaverActive.value) {
                        if (System.currentTimeMillis() - lastTouchTime > s.saverTimeout * 1000L) {
                            isSaverActive.value = true
                        }
                    }
                    delay(1000)
                }
            }

            Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(onPress = { lastTouchTime = System.currentTimeMillis() })
            }) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = { (fadeIn(tween(400)) + scaleIn(initialScale = 0.95f)).togetherWith(fadeOut(tween(400))) },
                    label = "page"
                ) { target ->
                    if (target == "cam") CamScreen { screen = "set" }
                    else SetScreen { screen = "cam" }
                }
                
                AnimatedVisibility(visible = isSaverActive.value, enter = fadeIn(tween(800)), exit = fadeOut(tween(500))) {
                    val infiniteTransition = rememberInfiniteTransition(label = "saver")
                    val xBias by infiniteTransition.animateFloat(initialValue = -0.8f, targetValue = 0.8f, animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
                    val yBias by infiniteTransition.animateFloat(initialValue = -0.8f, targetValue = 0.8f, animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), label = "y")
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { isSaverActive.value = false; lastTouchTime = System.currentTimeMillis() }) {
                        Text(text = "正在后台运行，点击屏幕恢复。", color = Color.Gray.copy(alpha = 0.5f), fontSize = 14.sp, modifier = Modifier.align(BiasAlignment(xBias, yBias)))
                    }
                }
            }
        }
        BackHandler(screen == "set") { screen = "cam" }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CamScreen(onSet: () -> Unit) {
        val ctx = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val s = settingsState.value

        LaunchedEffect(s.res, s.hdr, s.night, s.lensFacing, s.fps) {
            try {
                val provider = awaitListenableFuture(ProcessCameraProvider.getInstance(ctx))
                val extManager = awaitListenableFuture(ExtensionsManager.getInstanceAsync(ctx, provider))
                val targetSize = when(s.res) { 
                    Resolution.R_180P -> Size(320, 180); Resolution.R_240P -> Size(320, 240)
                    Resolution.R_480P -> Size(640, 480); Resolution.R_720P -> Size(1280, 720)
                    Resolution.R_960P -> Size(1280, 960); Resolution.R_1080P -> Size(1920, 1080) 
                }
                val analysisBuilder = ImageAnalysis.Builder().setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                if (s.fps > 30) { Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(if (s.res == Resolution.R_1080P) 15 else 30, s.fps)) }
                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(analysisExecutor) { img -> processImage(img); img.close() }
                var sel = CameraSelector.Builder().requireLensFacing(s.lensFacing).build()
                if (s.hdr && extManager.isExtensionAvailable(sel, ExtensionMode.HDR)) sel = extManager.getExtensionEnabledCameraSelector(sel, ExtensionMode.HDR)
                else if (s.night && extManager.isExtensionAvailable(sel, ExtensionMode.NIGHT)) sel = extManager.getExtensionEnabledCameraSelector(sel, ExtensionMode.NIGHT)
                provider.unbindAll(); val cam = provider.bindToLifecycle(owner, sel, analysis)
                cameraControl = cam.cameraControl; cam.cameraInfo.zoomState.observe(owner) { zoomRatio.floatValue = it.zoomRatio; zoomRange.value = it.minZoomRatio..it.maxZoomRatio }
                cameraControl?.setExposureCompensationIndex(s.ev); cameraControl?.enableTorch(isFlashOn.value)
            } catch (e: Exception) { if (s.fps > 30) settingsState.value = s.copy(fps = 30) }
        }

        Scaffold(
            topBar = { 
                TopAppBar(
                    title = { Text("NexCam Pro") }, 
                    actions = {
                        val flashScale by animateFloatAsState(targetValue = if (isFlashOn.value) 1.2f else 1f, animationSpec = spring(), label = "f")
                        IconButton(onClick = { isFlashOn.value = !isFlashOn.value; cameraControl?.enableTorch(isFlashOn.value) }, modifier = Modifier.scale(flashScale)) { Icon(if (isFlashOn.value) Icons.Default.FlashOn else Icons.Default.FlashOff, null) }
                        IconButton(onClick = { val next = if (s.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK; val ns = s.copy(lensFacing = next); settingsState.value = ns; save(ns) }) { Icon(Icons.Default.Sync, null) }
                        IconButton(onClick = onSet) { Icon(Icons.Default.Settings, null) }
                    }
                ) 
            }
        ) { p ->
            Box(modifier = Modifier.fillMaxSize().padding(p)) {
                AndroidView(factory = { context -> PreviewSurface(context).also { previewSurfaceRef = it } }, modifier = Modifier.fillMaxSize())
                Surface(modifier = Modifier.padding(16.dp).align(Alignment.TopStart), color = ComposeColor.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(if (currentFps.intValue > 0) Color.Green else Color.Red, RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(6.dp)); Text("FPS: ${currentFps.intValue}", color = ComposeColor.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).width(200.dp).background(ComposeColor.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp)).padding(16.dp, 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("变焦: ${String.format("%.1fx", zoomRatio.floatValue)}", color = ComposeColor.White, fontSize = 10.sp)
                    Slider(value = zoomRatio.floatValue, onValueChange = { zoomRatio.floatValue = it; cameraControl?.setZoomRatio(it) }, valueRange = zoomRange.value, modifier = Modifier.height(20.dp))
                }
                Card(modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 100.dp).fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val ip = getIP(ctx)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLive.value) {
                                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                val alpha by infiniteTransition.animateFloat(initialValue = 1f, targetValue = 0.2f, animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "p")
                                Box(modifier = Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.background(Color.Red, RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp))
                            }
                            Text(if (isLive.value) "正在直播" else "IP: $ip", fontWeight = FontWeight.Bold)
                        }
                        if (isLive.value) {
                            val url = "http://$ip:${s.serverPort}/live" + if (s.usePassword) "?pwd=${s.serverPassword}" else ""
                            Text(url, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
                        val btnScale by animateFloatAsState(targetValue = if (isLive.value) 1.02f else 1f, animationSpec = spring(), label = "b")
                        Button(onClick = { if (!isLive.value) { if (ip != "0.0.0.0") { startServer(ip); isLive.value = true } } else { stopServer(); isLive.value = false } }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).scale(btnScale), colors = if (isLive.value) ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(if (isLive.value) "停止服务" else "启动服务") }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun SetScreen(onBack: () -> Unit) {
        var s by settingsState
        val scroll = rememberScrollState()
        val ctx = LocalContext.current
        var showConfirm by remember { mutableStateOf(false) }
        var pendingRes by remember { mutableStateOf<Resolution?>(null) }
        if (showConfirm) { AlertDialog(onDismissRequest = { showConfirm = false }, title = { Text("性能提示") }, text = { Text("1080p 模式对设备性能要求大，可能导致运行不稳定。是否继续？") }, confirmButton = { TextButton(onClick = { pendingRes?.let { r -> val next = if (r != Resolution.R_1080P && s.fps == 60) 30 else s.fps; val ns = s.copy(res = r, fps = next); s = ns; save(ns) }; showConfirm = false }) { Text("继续") } }, dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("取消") } }) }

        Scaffold(topBar = { TopAppBar(title = { Text("设置") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } }) }) { p ->
            Column(modifier = Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(scroll)) {
                val items = listOf<@Composable () -> Unit>(
                    { SettingRow("端口设置", Icons.Default.Hvac) { TextField(s.serverPort.toString(), { val p = it.toIntOrNull() ?: 8080; val ns = s.copy(serverPort = p); s = ns; save(ns) }, label = { Text("输出端口 (需重启服务生效)") }, modifier = Modifier.fillMaxWidth()) } },
                    { SettingRow("安全验证", Icons.Default.Security) { Column { Row(verticalAlignment = Alignment.CenterVertically) { Text("开启密码"); Switch(s.usePassword, { val ns = s.copy(usePassword = it); s = ns; save(ns) }); Spacer(Modifier.width(16.dp)) }; if (s.usePassword) TextField(s.serverPassword, { val ns = s.copy(serverPassword = it); s = ns; save(ns) }, label = { Text("访问密码") }, modifier = Modifier.fillMaxWidth()) } } },
                    { SettingRow("画面方向", Icons.Default.ScreenRotation) { Row { OrientationMode.entries.forEach { mode -> FilterChip(selected = s.orientation == mode, onClick = { val ns = s.copy(orientation = mode); s = ns; save(ns) }, label = { Text(if (mode == OrientationMode.PORTRAIT) "纵向" else "横向") }, modifier = Modifier.padding(end = 4.dp)) } } } },
                    { SettingRow("分辨率", Icons.Default.AspectRatio) { FlowRow(modifier = Modifier.fillMaxWidth()) { Resolution.entries.forEach { r -> FilterChip(selected = s.res == r, onClick = { if (r == Resolution.R_1080P && s.res != Resolution.R_1080P) { pendingRes = r; showConfirm = true } else { val next = if (r != Resolution.R_1080P && s.fps == 60) 30 else s.fps; val ns = s.copy(res = r, fps = next); s = ns; save(ns) } }, label = { Text(r.name.substring(2)) }, modifier = Modifier.padding(end = 4.dp)) } } } },
                    { SettingRow("帧率 (FPS)", Icons.Default.Speed) { Row { val opts = if (s.res == Resolution.R_1080P) listOf(15, 24, 30, 60) else listOf(15, 24, 30); opts.forEach { f -> FilterChip(selected = s.fps == f, onClick = { val ns = s.copy(fps = f); s = ns; save(ns) }, label = { Text("$f") }, modifier = Modifier.padding(end = 4.dp)) } } } },
                    { SettingRow("自动屏保", Icons.Default.DarkMode) { Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { val opts = listOf(0 to "关闭", 15 to "15s", 30 to "30s", 60 to "1m", 300 to "5m"); opts.forEach { (v, l) -> FilterChip(selected = s.saverTimeout == v, onClick = { val ns = s.copy(saverTimeout = v); s = ns; save(ns) }, label = { Text(l) }, modifier = Modifier.padding(end = 4.dp)) } } } },
                    { SettingRow("曝光补偿: ${s.ev}", Icons.Default.Exposure) { Slider(s.ev.toFloat(), { val ns = s.copy(ev = it.toInt()); s = ns; save(ns); cameraControl?.setExposureCompensationIndex(ns.ev) }, valueRange = -4f..4f, steps = 8) } },
                    { SettingToggle("HDR 模式", Icons.Default.AutoAwesome, s.hdr) { val ns = s.copy(hdr = it, night = false); s = ns; save(ns) } },
                    { SettingToggle("夜景模式", Icons.Default.Nightlight, s.night) { val ns = s.copy(night = it, hdr = false); s = ns; save(ns) } },
                    { SettingToggle("显示水印", Icons.Default.ClosedCaption, s.watermark) { val ns = s.copy(watermark = it); s = ns; save(ns) } },
                    { if (s.watermark) TextField(s.text, { val ns = s.copy(text = it); s = ns; save(ns) }, label = { Text("自定义水印文字") }, modifier = Modifier.fillMaxWidth().animateContentSize()) }
                )
                items.forEachIndexed { i, item -> val state = remember { MutableTransitionState(false) }.apply { targetState = true }; AnimatedVisibility(visibleState = state, enter = slideInHorizontally(tween(300, i * 50)) { -40 } + fadeIn(tween(300, i * 50))) { Column { item(); if (i < items.size - 1) HorizontalDivider(Modifier.padding(vertical = 8.dp)) } } }
                Spacer(Modifier.height(32.dp))
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Text("关于软件", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text("软件名称: NexCam Pro", fontSize = 12.sp)
                        val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (e: Exception) { "1.0.0" }
                        Text("版本: $version", fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun SettingRow(label: String, icon: ImageVector, content: @Composable () -> Unit) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text(label, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(4.dp)); content()
        }
    }

    @Composable
    fun SettingToggle(label: String, icon: ImageVector, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 16.sp) }
            Switch(checked, onCheckedChange)
        }
    }

    private fun processImage(img: ImageProxy) {
        val s = settingsState.value
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < (1000L / s.fps) - 2) return
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            frameCount++; if (now - lastFpsUpdateTime > 1000) { currentFps.intValue = frameCount; frameCount = 0; lastFpsUpdateTime = now }; lastFrameTime = now
            val src = img.toBitmap(); val rot = img.imageInfo.rotationDegrees.toFloat(); var finalRot = rot
            if (s.orientation == OrientationMode.PORTRAIT) { if (rot % 180 == 0f) finalRot += 90f } else if (s.orientation == OrientationMode.LANDSCAPE) { if (rot % 180 != 0f) finalRot += 90f }
            val tw = if (finalRot % 180 != 0f) img.height else img.width; val th = if (finalRot % 180 != 0f) img.width else img.height
            synchronized(frameLock) {
                if (renderBuffer == null || renderBuffer!!.width != tw || renderBuffer!!.height != th) { renderBuffer?.recycle(); renderBuffer = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888); renderCanvas = Canvas(renderBuffer!!) }
                renderCanvas?.apply { val m = Matrix().apply { postRotate(finalRot); val rect = RectF(0f, 0f, img.width.toFloat(), img.height.toFloat()); mapRect(rect); postTranslate(-rect.left, -rect.top) }; drawBitmap(src, m, bitmapPaint); if (s.watermark) { val tp = Paint().apply { color = android.graphics.Color.WHITE; textSize = tw / 20f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(3f, 2f, 2f, android.graphics.Color.BLACK) }
                        var y = th - 30f
                        if (s.timestamp) { drawText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()), 30f, y, tp); y -= (tp.textSize + 10f) }
                        if (s.text.isNotEmpty()) drawText(s.text, 30f, y, tp) } }
                src.recycle(); previewSurfaceRef?.update(renderBuffer)
                if (isLive.value) { val baos = ByteArrayOutputStream(); val quality = if (s.fps >= 60) 25 else 45; renderBuffer?.compress(Bitmap.CompressFormat.JPEG, quality, baos); frameFlow.value = baos.toByteArray() }
            }
        } catch (e: Exception) {} finally { isProcessing.set(false) }
    }

    private fun startServer(ip: String) {
        if (server != null) return
        val s = settingsState.value
        server = embeddedServer(Netty, port = s.serverPort, host = "0.0.0.0") {
            routing {
                route("/live", HttpMethod.Get) {
                    handle {
                        val currentSettings = settingsState.value
                        if (currentSettings.usePassword) {
                            val pwd = call.request.queryParameters["pwd"]
                            if (pwd != currentSettings.serverPassword) {
                                call.respond(HttpStatusCode.Unauthorized, "Password Required or Incorrect.")
                                return@handle
                            }
                        }
                        val body = object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=--frame")
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                try {
                                    frameFlow.collect { frame ->
                                        if (frame != null && isActive) {
                                            channel.writeStringUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
                                            channel.writeFully(frame); channel.writeStringUtf8("\r\n"); channel.flush()
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                        call.respond(body)
                    }
                }
            }
        }.start(false)
    }

    private fun stopServer() { server?.stop(200, 500); server = null }
    private fun getIP(ctx: Context): String { try { val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager; val ip = wm.connectionInfo.ipAddress; if (ip != 0) return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff) } catch (e: Exception) {}; return "0.0.0.0" }
    override fun onDestroy() { super.onDestroy(); stopServer(); analysisExecutor.shutdown() }
    private suspend fun <T> awaitListenableFuture(future: ListenableFuture<T>): T = suspendCancellableCoroutine { cont -> future.addListener({ try { cont.resume((future as ListenableFuture<T>).get()) } catch (e: Exception) { cont.resumeWithException(e) } }, ContextCompat.getMainExecutor(this)) }
}

class PreviewSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var isSurfaceAvailable = false
    init { holder.addCallback(this) }
    override fun surfaceCreated(h: SurfaceHolder) { isSurfaceAvailable = true }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { isSurfaceAvailable = false }
    fun update(bitmap: Bitmap?) {
        if (!isSurfaceAvailable) return
        val canvas = holder.lockCanvas() ?: return
        try { bitmap?.let { val scale = (canvas.width.toFloat() / it.width).coerceAtMost(canvas.height.toFloat() / it.height); val nw = (it.width * scale).toInt(); val nh = (it.height * scale).toInt(); canvas.drawColor(android.graphics.Color.BLACK); canvas.drawBitmap(it, null, Rect((canvas.width - nw) / 2, (canvas.height - nh) / 2, (canvas.width + nw) / 2, (canvas.height + nh) / 2), null) }
        } catch (e: Exception) {} finally { try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) {} }
    }
}
