package com.rcbs.nexcam

import android.content.*
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
import java.io.ByteArrayOutputStream
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Explicitly avoid name conflicts by using type aliases
import androidx.compose.ui.graphics.Color as ComposeColor

enum class Protocol { HTTP_MJPEG, RTSP_PREVIEW, RTMP_PREVIEW }
enum class Resolution { R_480P, R_720P, R_1080P }
enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

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
    val orientation: OrientationMode = OrientationMode.AUTO,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK
)

class MainActivity : ComponentActivity() {
    private var server: NettyApplicationEngine? = null
    private var frameData: ByteArray? = null
    private val frameLock = Any()
    private val settingsState = mutableStateOf(CameraSettings())
    private val isLive = mutableStateOf(false)
    private val liveUrlDisplay = mutableStateOf("Ready")
    
    private var renderBuffer: Bitmap? = null
    private var renderCanvas: Canvas? = null
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    
    private var previewSurfaceRef: PreviewSurface? = null
    private lateinit var sp: SharedPreferences

    // Real-time FPS state
    private val currentFps = mutableIntStateOf(0)
    private var lastFrameTime = 0L

    // Zoom state
    private var cameraControl: CameraControl? = null
    private val zoomRatio = mutableFloatStateOf(1f)
    private val zoomRange = mutableStateOf(1f..10f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sp = getSharedPreferences("pro_settings_vFinal", MODE_PRIVATE)
        load()
        enableEdgeToEdge()
        setContent { NexCamTheme { Surface { MainContent() } } }
    }

    private fun load() {
        settingsState.value = CameraSettings(
            sp.getBoolean("wm", false), sp.getBoolean("ts", true),
            sp.getString("txt", "NexCam") ?: "NexCam",
            Resolution.valueOf(sp.getString("res", "R_480P") ?: "R_480P"),
            sp.getInt("ev", 0), sp.getBoolean("hdr", false),
            sp.getBoolean("night", false), sp.getInt("fps", 30),
            Protocol.valueOf(sp.getString("proto", "HTTP_MJPEG") ?: "HTTP_MJPEG"),
            OrientationMode.valueOf(sp.getString("ori", "AUTO") ?: "AUTO"),
            sp.getInt("lens", CameraSelector.LENS_FACING_BACK)
        )
    }

    private fun save(s: CameraSettings) {
        sp.edit().apply {
            putBoolean("wm", s.watermark)
            putBoolean("ts", s.timestamp)
            putString("txt", s.text)
            putString("res", s.res.name)
            putInt("ev", s.ev)
            putBoolean("hdr", s.hdr)
            putBoolean("night", s.night)
            putInt("fps", s.fps)
            putString("proto", s.protocol.name)
            putString("ori", s.orientation.name)
            putInt("lens", s.lensFacing)
            apply()
        }
    }

    @Composable
    fun MainContent() {
        var screen by remember { mutableStateOf("cam") }
        if (screen == "cam") CamScreen { screen = "set" }
        else SetScreen { screen = "cam" }
        BackHandler(screen == "set") { screen = "cam" }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun CamScreen(onSet: () -> Unit) {
        val ctx = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val s = settingsState.value
        val executor = remember { Executors.newSingleThreadExecutor() }

        LaunchedEffect(s.res, s.hdr, s.night, s.lensFacing) {
            try {
                val provider = awaitListenableFuture(ProcessCameraProvider.getInstance(ctx))
                val extManager = awaitListenableFuture(ExtensionsManager.getInstanceAsync(ctx, provider))
                
                val targetSize = when(s.res) { 
                    Resolution.R_480P -> Size(640, 480)
                    Resolution.R_720P -> Size(1280, 720)
                    Resolution.R_1080P -> Size(1920, 1080)
                }

                val resSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()

                synchronized(frameLock) {
                    renderBuffer?.recycle()
                    renderBuffer = null
                }

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                
                analysis.setAnalyzer(executor) { img -> processImage(img); img.close() }
                
                var sel = CameraSelector.Builder().requireLensFacing(s.lensFacing).build()
                
                if (s.hdr && extManager.isExtensionAvailable(sel, ExtensionMode.HDR)) {
                    sel = extManager.getExtensionEnabledCameraSelector(sel, ExtensionMode.HDR)
                } else if (s.night && extManager.isExtensionAvailable(sel, ExtensionMode.NIGHT)) {
                    sel = extManager.getExtensionEnabledCameraSelector(sel, ExtensionMode.NIGHT)
                }
                
                provider.unbindAll()
                val cam = provider.bindToLifecycle(owner, sel, analysis)
                cameraControl = cam.cameraControl
                
                cam.cameraInfo.zoomState.observe(owner) { state ->
                    zoomRatio.floatValue = state.zoomRatio
                    zoomRange.value = state.minZoomRatio..state.maxZoomRatio
                }
                
                cameraControl?.setExposureCompensationIndex(s.ev)
            } catch (e: Exception) { Log.e("NexCam", "Camera Error", e) }
        }

        Scaffold(
            topBar = { 
                TopAppBar(
                    title = { Text("NexCam Pro") }, 
                    actions = {
                        IconButton(onClick = {
                            val newFacing = if (s.lensFacing == CameraSelector.LENS_FACING_BACK) 
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            val ns = s.copy(lensFacing = newFacing)
                            settingsState.value = ns
                            save(ns)
                        }) {
                            Icon(Icons.Default.Sync, contentDescription = "Switch Camera")
                        }
                        IconButton(onClick = onSet) { Icon(Icons.Default.Settings, null) }
                    }
                ) 
            }
        ) { p ->
            Box(modifier = Modifier.fillMaxSize().padding(p)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().background(ComposeColor.Black)) {
                        AndroidView(factory = { context -> PreviewSurface(context).also { previewSurfaceRef = it } }, modifier = Modifier.fillMaxSize())
                        
                        // Floating FPS Overlay
                        Surface(
                            modifier = Modifier.padding(16.dp).align(Alignment.TopStart),
                            color = ComposeColor.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "FPS: ${currentFps.intValue}",
                                color = ComposeColor.Green,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Zoom Slider
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                                .width(200.dp)
                                .background(ComposeColor.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = "Zoom: ${String.format("%.1fx", zoomRatio.floatValue)}", color = ComposeColor.White, fontSize = 10.sp)
                            Slider(
                                value = zoomRatio.floatValue,
                                onValueChange = { 
                                    zoomRatio.floatValue = it
                                    cameraControl?.setZoomRatio(it)
                                },
                                valueRange = zoomRange.value,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                    Card(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val ip = getIP(ctx)
                            Text(if (isLive.value) "LIVE: ${s.protocol}" else "IP: $ip", fontWeight = FontWeight.Bold)
                            if (isLive.value) Text(liveUrlDisplay.value, color = MaterialTheme.colorScheme.primary)
                            Button(onClick = {
                                if (!isLive.value) {
                                    if (ip != "0.0.0.0") { 
                                        startServer(ip)
                                        isLive.value = true
                                        val prefix = when(s.protocol) {
                                            Protocol.HTTP_MJPEG -> "http"
                                            Protocol.RTSP_PREVIEW -> "rtsp"
                                            Protocol.RTMP_PREVIEW -> "rtmp"
                                        }
                                        liveUrlDisplay.value = "$prefix://$ip:8080/live"
                                    }
                                } else { stopServer(); isLive.value = false }
                            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (isLive.value) "Stop Monitoring" else "Start Server") }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SetScreen(onBack: () -> Unit) {
        var s by settingsState
        val scroll = rememberScrollState()
        Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Text("←") } }) }) { p ->
            Column(modifier = Modifier.fillMaxSize().padding(p).padding(16.dp).verticalScroll(scroll)) {
                Text("Output Orientation", color = MaterialTheme.colorScheme.primary)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OrientationMode.entries.forEach { mode ->
                        FilterChip(selected = s.orientation == mode, onClick = { val ns = s.copy(orientation = mode); s = ns; save(ns) }, label = { Text(mode.name) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Protocol", color = MaterialTheme.colorScheme.primary); Protocol.entries.forEach { proto -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(s.protocol == proto, { val ns = s.copy(protocol = proto); s = ns; save(ns) }); Text(proto.name) } }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Resolution", color = MaterialTheme.colorScheme.primary); Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { Resolution.entries.forEach { r -> FilterChip(selected = s.res == r, onClick = { val ns = s.copy(res = r); s = ns; save(ns) }, label = { Text(r.name.substring(2)) }, modifier = Modifier.padding(end = 4.dp)) } }
                Spacer(modifier = Modifier.height(8.dp)); Text("FPS"); Row(modifier = Modifier.horizontalScroll(rememberScrollState())) { listOf(15, 24, 30, 60).forEach { f -> FilterChip(selected = s.fps == f, onClick = { val ns = s.copy(fps = f); s = ns; save(ns) }, label = { Text("$f") }, modifier = Modifier.padding(end = 4.dp)) } }
                Spacer(modifier = Modifier.height(8.dp)); Text("Exposure: ${s.ev}"); Slider(s.ev.toFloat(), { val ns = s.copy(ev = it.toInt()); s = ns; save(ns) }, valueRange = -4f..4f, steps = 8)
                Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("HDR Mode"); Switch(s.hdr, { val ns = s.copy(hdr = it, night = false); s = ns; save(ns) }) }
                Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Night Mode"); Switch(s.night, { val ns = s.copy(night = it, hdr = false); s = ns; save(ns) }) }
                Row(modifier = Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Watermark"); Switch(s.watermark, { val ns = s.copy(watermark = it); s = ns; save(ns) }) }
                if (s.watermark) TextField(s.text, { val ns = s.copy(text = it); s = ns; save(ns) }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    private fun processImage(img: ImageProxy) {
        try {
            // Update FPS Calculation
            val now = System.currentTimeMillis()
            if (lastFrameTime > 0) {
                val diff = now - lastFrameTime
                if (diff > 0) currentFps.intValue = (1000 / diff).toInt()
            }
            lastFrameTime = now

            val src = img.toBitmap()
            val rot = img.imageInfo.rotationDegrees.toFloat()
            val s = settingsState.value
            
            var finalRot = rot
            if (s.orientation == OrientationMode.PORTRAIT) { if (rot % 180 == 0f) finalRot += 90f } 
            else if (s.orientation == OrientationMode.LANDSCAPE) { if (rot % 180 != 0f) finalRot += 90f }
            val tw = if (finalRot % 180 != 0f) img.height else img.width
            val th = if (finalRot % 180 != 0f) img.width else img.height
            synchronized(frameLock) {
                if (renderBuffer == null || renderBuffer!!.width != tw || renderBuffer!!.height != th) {
                    renderBuffer?.recycle(); renderBuffer = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    renderCanvas = Canvas(renderBuffer!!)
                }
                renderCanvas?.apply {
                    val m = Matrix().apply { 
                        postRotate(finalRot)
                        val rect = RectF(0f, 0f, img.width.toFloat(), img.height.toFloat())
                        mapRect(rect); postTranslate(-rect.left, -rect.top)
                    }
                    drawBitmap(src, m, bitmapPaint)
                    if (s.watermark) {
                        val tp = Paint().apply { color = android.graphics.Color.WHITE; textSize = tw / 20f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(3f, 2f, 2f, android.graphics.Color.BLACK) }
                        var y = th - 30f
                        if (s.timestamp) { drawText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()), 30f, y, tp); y -= (tp.textSize + 10f) }
                        if (s.text.isNotEmpty()) drawText(s.text, 30f, y, tp)
                    }
                }
                src.recycle(); previewSurfaceRef?.update(renderBuffer)
                val baos = ByteArrayOutputStream()
                val quality = if (s.res == Resolution.R_1080P) 70 else 40
                renderBuffer?.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                frameData = baos.toByteArray()
            }
        } catch (e: OutOfMemoryError) { System.gc() }
    }

    private fun startServer(ip: String) {
        if (server != null) return
        val currentSettings = settingsState.value
        server = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
            routing {
                route("/live", HttpMethod.Get) {
                    handle {
                        val body: OutgoingContent = object : OutgoingContent.WriteChannelContent() {
                            override val contentType = ContentType.parse("multipart/x-mixed-replace; boundary=--frame")
                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                try {
                                    while (isActive) {
                                        val frame = synchronized(frameLock) { frameData }
                                        if (frame != null) {
                                            channel.writeStringUtf8("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n")
                                            channel.writeFully(frame)
                                            channel.writeStringUtf8("\r\n"); channel.flush()
                                        }
                                        delay(1000L / currentSettings.fps)
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

    private fun getIP(ctx: Context): String {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val itf = en.nextElement()
                if (itf.name.contains("wlan") || itf.name.contains("ap") || itf.name.contains("eth")) {
                    val addrs = itf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val a = addrs.nextElement()
                        if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {}
        return "0.0.0.0"
    }

    override fun onDestroy() { super.onDestroy(); stopServer() }

    private suspend fun <T> awaitListenableFuture(future: ListenableFuture<T>): T = suspendCancellableCoroutine { cont ->
        future.addListener({
            try {
                val result = (future as ListenableFuture<T>).get()
                cont.resume(result)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, ContextCompat.getMainExecutor(this@MainActivity))
    }
}

class PreviewSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    init { holder.addCallback(this) }
    override fun surfaceCreated(h: SurfaceHolder) {}
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {}
    fun update(bitmap: Bitmap?) {
        val canvas = holder.lockCanvas() ?: return
        try {
            bitmap?.let {
                val src = Rect(0, 0, it.width, it.height)
                val scale = (canvas.width.toFloat() / it.width).coerceAtMost(canvas.height.toFloat() / it.height)
                val nw = (it.width * scale).toInt(); val nh = (it.height * scale).toInt()
                val left = (canvas.width - nw) / 2; val top = (canvas.height - nh) / 2
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(it, src, Rect(left, top, left + nw, top + nh), null)
            }
        } finally { holder.unlockCanvasAndPost(canvas) }
    }
}
