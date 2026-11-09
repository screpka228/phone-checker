package com.phonechecker.checks

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.phonechecker.data.CheckResult
import com.phonechecker.data.Item
import com.phonechecker.data.Status
import com.phonechecker.ui.C
import com.phonechecker.ui.Header
import com.phonechecker.ui.Page
import com.phonechecker.ui.PrimaryButton
import com.phonechecker.ui.SecondaryButton
import com.phonechecker.ui.T
import com.phonechecker.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File

data class Clip(val file: File, val lens: String, val sec: Long, val micPeak: Double)

private val camPerms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
private fun camGranted(ctx: Context) = camPerms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun CameraCheck(onResult: (CheckResult) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(camGranted(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = camGranted(ctx) }
    val clips = remember { mutableStateListOf<Clip>() }
    var reviewing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { clips.forEach { it.file.delete() } }
    }

    if (!granted) {
        Page(bottom = {
            PrimaryButton("Разрешить камеру и микрофон") { launcher.launch(camPerms) }
            SecondaryButton("Пропустить", onClick = onSkip)
        }) {
            Header("Дополнительная проверка", "Камеры", "Снимаем короткое видео со звуком каждой камерой, потом смотрим его на весь экран. Микрофон проверяется записью, динамик — воспроизведением.")
        }
        return
    }

    if (reviewing && clips.isNotEmpty()) {
        Review(
            clips,
            verdict = { st -> onResult(cameraResult(clips, st)) },
            retake = { clips.forEach { it.file.delete() }; clips.clear(); reviewing = false },
        )
    } else {
        Immersive {
            RecordView(onClip = { clips += it }, clipsCount = clips.size, review = { reviewing = true }, onSkip = onSkip)
        }
    }
}

private fun cameraResult(clips: List<Clip>, verdict: Status): CheckResult {
    val mic = clips.maxOf { it.micPeak }
    val items = clips.map { it.lens }.distinct().map { Item("Камера: $it", verdict, verdict.label) } +
        Item("Микрофон", if (mic > 0.02) Status.OK else Status.WARN, if (mic > 0.02) "есть звук" else "тихо") +
        Item("Динамик", verdict, verdict.label)
    val st = when {
        verdict == Status.DEFECT -> Status.DEFECT
        items.any { it.status == Status.WARN } -> Status.WARN
        else -> verdict
    }
    return CheckResult(st, if (st == Status.WARN) "микрофон тихий" else st.label, items = items)
}

@SuppressLint("MissingPermission")
@Composable
private fun RecordView(onClip: (Clip) -> Unit, clipsCount: Int, review: () -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var back by remember { mutableStateOf(true) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoom by remember { mutableStateOf<ZoomState?>(null) }
    var torch by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val levels = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f) }
    var micPeak by remember { mutableStateOf(0.0) }
    val previewView = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val capture = remember {
        VideoCapture.withOutput(
            Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build(),
        )
    }

    LaunchedEffect(back) {
        val provider = cameraProvider(ctx)
        val selector = if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!provider.hasCamera(selector)) { back = !back; return@LaunchedEffect }
        provider.unbindAll()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val cam = provider.bindToLifecycle(owner, selector, preview, capture)
        camera = cam
        torch = false
        cam.cameraInfo.zoomState.observe(owner) { zoom = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            recording?.stop()
            runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() }
        }
    }

    fun start() {
        val f = File(ctx.cacheDir, "cam_${System.currentTimeMillis()}.mp4")
        val lens = if (back) "основная" else "фронтальная"
        micPeak = 0.0
        recording = capture.output.prepareRecording(ctx, FileOutputOptions.Builder(f).build())
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(ctx)) { e ->
                when (e) {
                    is VideoRecordEvent.Status -> {
                        seconds = (e.recordingStats.recordedDurationNanos / 1_000_000_000).toInt()
                        val a = e.recordingStats.audioStats.audioAmplitude
                        if (a > micPeak) micPeak = a
                        levels.removeAt(0); levels += a.toFloat()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!e.hasError() || f.length() > 0) onClip(Clip(f, lens, e.recordingStats.recordedDurationNanos / 1_000_000_000, micPeak))
                        recording = null
                        seconds = 0
                    }
                }
            }
    }

    val isRec = recording != null
    val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2B3340), Color(0xFF14171D))))) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // Верхняя панель: таймер записи и состояние микрофона.
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Pill(if (isRec) "● %02d:%02d".format(seconds / 60, seconds % 60) else "● запись", if (isRec) Color(0xFFD93025) else Color(0x66000000), bold = true)
            val loud = levels.max() > 0.02f
            Pill("🎙 микрофон: " + if (!isRec) "ждёт записи" else if (loud) "есть звук" else "тихо", Color(0x66000000))
        }
        // Индикатор уровня микрофона.
        Row(Modifier.align(Alignment.TopEnd).padding(top = 60.dp, end = 16.dp).height(28.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            levels.forEach { l ->
                val h = (l * 4f).coerceIn(0.08f, 1f)
                Box(Modifier.width(4.dp).fillMaxHeight(h).background(if (h > 0.9f) Color(0xFFFDD663) else Color(0xFF81C995)))
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val z = zoom
            if (z != null) {
                val stops = buildList {
                    if (z.minZoomRatio < 0.95f) add(z.minZoomRatio)
                    add(1f)
                    if (z.maxZoomRatio >= 2f) add(2f)
                    if (z.maxZoomRatio > 2.05f) add(z.maxZoomRatio)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stops.forEach { s ->
                        val on = kotlin.math.abs(z.zoomRatio - s) < 0.05f
                        Box(
                            Modifier.clip(CircleShape).background(if (on) Color.White else Color(0x73000000))
                                .clickable { camera?.cameraControl?.setZoomRatio(s) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { T(zoomLabel(s), 13.sp, if (on) Color.Black else Color.White, if (on) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
                // Ползунок плавного зума.
                Box(Modifier.padding(horizontal = 40.dp, vertical = 18.dp).fillMaxWidth().height(24.dp).pointerInput(camera) {
                    fun set(x: Float) { camera?.cameraControl?.setLinearZoom((x / size.width).coerceIn(0f, 1f)) }
                    detectDragGestures(onDragStart = { set(it.x) }) { ch, _ -> set(ch.position.x) }
                }, contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0x4DFFFFFF)))
                    Box(Modifier.fillMaxWidth(z.linearZoom.coerceIn(0.02f, 1f)).height(4.dp).clip(CircleShape).background(Color.White))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                T((if (back) "Основная" else "Фронтальная") + if (hasFlash) " · вспышка ${if (torch) "вкл" else "выкл"}" else "", 12.sp, Color.White.copy(alpha = 0.8f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                // Слева: вспышка, либо переход к просмотру снятого.
                RoundBtn(if (clipsCount > 0 && !isRec) "▶ $clipsCount" else if (torch) "⚡" else "ϟ", enabled = hasFlash || clipsCount > 0, square = true) {
                    if (clipsCount > 0 && !isRec) review()
                    else { torch = !torch; camera?.cameraControl?.enableTorch(torch) }
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .clickable { if (isRec) recording?.stop() else start() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRec) Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFD93025)))
                    else Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFFD93025)))
                }
                RoundBtn("⟲", enabled = !isRec) { back = !back }
            }
            if (clipsCount > 0 && !isRec) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (hasFlash) Pill(if (torch) "вспышка вкл" else "вспышка выкл", Color(0x66000000), Modifier.clickable { torch = !torch; camera?.cameraControl?.enableTorch(torch) })
                    Pill("Пропустить", Color(0x66000000), Modifier.clickable(onClick = onSkip))
                }
            } else if (!isRec) Pill("Пропустить", Color(0x66000000), Modifier.padding(top = 10.dp).clickable(onClick = onSkip))
        }
    }
}

private suspend fun cameraProvider(ctx: Context): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
    val f = ProcessCameraProvider.getInstance(ctx)
    f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
}

private fun zoomLabel(z: Float) = (if (z % 1f == 0f) z.toInt().toString() else fmt(z)) + "×"

@Composable
private fun Pill(text: String, bg: Color, modifier: Modifier = Modifier, bold: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 4.dp)) {
        T(text, 13.sp, Color.White, if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun RoundBtn(text: String, enabled: Boolean = true, square: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(if (square) RoundedCornerShape(12.dp) else CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.2f else 0.08f)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 16.sp, Color.White.copy(alpha = if (enabled) 1f else 0.4f)) }
}

@Composable
private fun Review(clips: List<Clip>, verdict: (Status) -> Unit, retake: () -> Unit) {
    var sel by remember { mutableIntStateOf(clips.lastIndex) }
    var playing by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val clip = clips[sel]
    val thumbs = remember { mutableStateListOf<Bitmap?>().apply { repeat(clips.size) { add(null) } } }
    LaunchedEffect(clips.size) {
        withContext(Dispatchers.IO) {
            clips.forEachIndexed { i, c ->
                val bmp = runCatching {
                    MediaMetadataRetriever().run { setDataSource(c.file.path); getFrameAtTime(0).also { release() } }
                }.getOrNull()
                withContext(Dispatchers.Main) { if (i < thumbs.size) thumbs[i] = bmp }
            }
        }
    }
    var player by remember { mutableStateOf<VideoView?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> VideoView(c).also { v -> v.setOnCompletionListener { playing = false }; player = v } },
            update = { v -> if (v.tag != clip.file.path) { v.tag = clip.file.path; v.setVideoURI(Uri.fromFile(clip.file)); v.seekTo(1); playing = false } },
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }, onTap = {
                        player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
                    })
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        Box(Modifier.padding(16.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x73000000)).padding(horizontal = 8.dp, vertical = 4.dp)) {
            T("Видео · ${clip.lens} · 0:%02d · двумя пальцами приблизить".format(clip.sec), 12.sp, Color.White)
        }
        if (!playing) Box(
            Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.85f))
                .clickable { player?.start(); playing = true },
            contentAlignment = Alignment.Center,
        ) { T("▶", 22.sp, Color.Black) }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                clips.forEachIndexed { i, _ ->
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2B3340))
                            .then(if (i == sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { sel = i; scale = 1f; offset = Offset.Zero },
                    ) {
                        thumbs.getOrNull(i)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                repeat((4 - clips.size).coerceAtLeast(0)) { Box(Modifier.weight(1f)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewBtn("Норм", Tone(Color(0xFF81C995), Color(0xFF1E3A2A)), Modifier.weight(1f)) { verdict(Status.OK) }
                ReviewBtn("Дефект", Tone(Color(0xFFF28B82), Color(0xFF3A2020)), Modifier.weight(1f)) { verdict(Status.DEFECT) }
                ReviewBtn("Переснять", Tone(Color(0xFFE8EAED), Color(0xFF22262E)), Modifier.weight(1f), retake)
            }
        }
    }
}

@Composable
private fun ReviewBtn(text: String, tone: Tone, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp)).background(tone.bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(text, 16.sp, tone.fg, FontWeight.SemiBold) }
}
