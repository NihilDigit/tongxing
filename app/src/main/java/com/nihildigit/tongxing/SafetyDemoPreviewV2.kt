package com.nihildigit.tongxing

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nihildigit.safetycore.v2.types.DangerLevelV2
import com.nihildigit.safetycore.v2.types.FusionResultV2
import com.nihildigit.safetycore.v2.types.ThreatResultV2
import com.nihildigit.safetycore.v2.types.TrackedObstacleV2
import com.nihildigit.tongxing.audio.AudioPriorityManager
import com.nihildigit.tongxing.overlay.coordinator.CameraCoordinator
import com.nihildigit.tongxing.ui.components.GlassSurface
import com.nihildigit.tongxing.ui.components.MetricPod
import com.nihildigit.tongxing.ui.theme.TongXingColors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * V2 Safety Demo Screen with video file picker support.
 * Uses SafetyCore V2 pipeline with four-model fusion.
 */
@Composable
fun SafetyDemoScreenPreviewV2(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context as? Activity }
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Video selection state
    var selectedVideoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var selectedVideoName by rememberSaveable { mutableStateOf("") }
    var isLive by rememberSaveable { mutableStateOf(false) }

    // Frame state
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var frameResult by remember { mutableStateOf<FusionResultV2?>(null) }
    var latencyMs by remember { mutableStateOf<Float?>(null) }
    val latestFrameRef = remember { AtomicReference<Bitmap?>(null) }
    val latestResultRef = remember { AtomicReference<FusionResultV2?>(null) }
    val latestLatencyRef = remember { AtomicReference<Float?>(null) }
    val audioManager = remember { AudioPriorityManager(context.applicationContext) }
    val lastSpokenAtMs = remember { mutableMapOf<String, Long>() }
    val pendingDirections = remember { mutableMapOf<String, String>() }
    var pendingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    // V2 processor
    val videoProcessor = remember {
        SafetyDemoVideoProcessorV2(context).also { it.init() }
    }
    val liveCameraCoordinator = remember(videoProcessor) {
        CameraCoordinator(
            context = context.applicationContext,
            targetResolution = android.util.Size(1280, 720),
            targetFpsRange = android.util.Range(30, 30)
        ) { bitmap, _ ->
            videoProcessor.submitLiveFrame(bitmap, inferenceInterval = 3) { frame, result, lat ->
                latestFrameRef.set(frame)
                if (result != null) {
                    latestResultRef.set(result)
                    if (lat != null) latestLatencyRef.set(lat)
                }
            }
        }
    }

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            selectedVideoName = uri.lastPathSegment ?: "video"
            isLive = false
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isLive = true
            selectedVideoUri = null
        }
    }

    // Run video when URI changes
    LaunchedEffect(selectedVideoUri) {
        frameBitmap = null
        frameResult = null
        latencyMs = null
        videoProcessor.reset()

        val uri = selectedVideoUri ?: return@LaunchedEffect
        videoProcessor.runVideoUri(uri, playbackFps = 30, inferenceFps = 15) { bitmap, result, lat ->
            latestFrameRef.set(bitmap)
            if (result != null) {
                latestResultRef.set(result)
                if (lat != null) latestLatencyRef.set(lat)
            }
        }
    }

    LaunchedEffect(selectedVideoUri, isLive) {
        if (!isLive && selectedVideoUri == null) return@LaunchedEffect
        var lastTickNs = SystemClock.elapsedRealtimeNanos()
        var tickCount = 0
        var sumMs = 0.0
        var maxMs = 0.0
        var lastRenderedId = 0
        var lastRenderNs = 0L
        var renderCount = 0
        var sumRenderMs = 0.0
        var maxRenderMs = 0.0
        while (isActive && (isLive || selectedVideoUri != null)) {
            latestFrameRef.get()?.let {
                frameBitmap = it
                val currentId = System.identityHashCode(it)
                if (currentId != lastRenderedId) {
                    val nowRenderNs = SystemClock.elapsedRealtimeNanos()
                    if (lastRenderNs != 0L) {
                        val renderDeltaMs = (nowRenderNs - lastRenderNs) / 1_000_000.0
                        renderCount += 1
                        sumRenderMs += renderDeltaMs
                        if (renderDeltaMs > maxRenderMs) maxRenderMs = renderDeltaMs
                        if (renderCount % 30 == 0) {
                            val avgRenderMs = sumRenderMs / renderCount
                            Log.d(
                                "V2PreviewUI",
                                "render avg=${"%.2f".format(avgRenderMs)}ms max=${"%.2f".format(maxRenderMs)}ms"
                            )
                        }
                    }
                    lastRenderNs = nowRenderNs
                    lastRenderedId = currentId
                }
            }
            latestResultRef.get()?.let { frameResult = it }
            latestLatencyRef.get()?.let { latencyMs = it }
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val deltaMs = (nowNs - lastTickNs) / 1_000_000.0
            lastTickNs = nowNs
            tickCount += 1
            sumMs += deltaMs
            if (deltaMs > maxMs) maxMs = deltaMs
            if (tickCount % 30 == 0) {
                val avgMs = sumMs / tickCount
                Log.d("V2PreviewUI", "tick avg=${"%.2f".format(avgMs)}ms max=${"%.2f".format(maxMs)}ms")
            }
            delay(33)
        }
    }

    // Orientation lock for live camera
    LaunchedEffect(isLive) {
        if (isLive) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            liveCameraCoordinator.start()
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            liveCameraCoordinator.stop()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            liveCameraCoordinator.shutdown()
            videoProcessor.close()
            audioManager.close()
        }
    }

    // Extract display data
    val tracks = frameResult?.tracks.orEmpty()
    val threats = frameResult?.threats.orEmpty()
    val visualThreats = threats.filter { it.level >= DangerLevelV2.MEDIUM }
    val topThreat = visualThreats.maxByOrNull { it.level.ordinal }
    val isAlert = topThreat?.level?.ordinal ?: -1 >= DangerLevelV2.HIGH.ordinal
    val scaleState = frameResult?.scaleState
    val timings = frameResult?.timings

    val fpsText = timings?.totalMs?.takeIf { it > 0 }?.let {
        String.format("%.1f", 1000f / it)
    } ?: "--"
    val latencyText = latencyMs?.let { String.format("%.0f", it) } ?: "--"

    fun threatDirectionLabel(track: TrackedObstacleV2?): String {
        val width = frameResult?.trackSpaceWidth?.takeIf { it > 0 }
            ?: frameResult?.inputFrameWidth?.takeIf { it > 0 }
            ?: frameBitmap?.width
            ?: return "前方"
        val centerX = track?.centerX ?: return "前方"
        val ratio = centerX / width.toFloat()
        return when {
            ratio < 0.33f -> "左侧"
            ratio > 0.66f -> "右侧"
            else -> "前方"
        }
    }

    fun threatClassLabel(track: TrackedObstacleV2?): String {
        if (track == null) return "障碍"
        if (track.classSource != 0) return "障碍"
        return when (track.classId) {
            0 -> "人"
            1, 2, 3, 5, 7 -> "车"
            else -> "障碍"
        }
    }

    LaunchedEffect(topThreat?.trackId, topThreat?.level, topThreat?.distanceMeters) {
        val threat = topThreat ?: return@LaunchedEffect
        if (threat.level < DangerLevelV2.HIGH) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        val track = tracks.firstOrNull { it.trackId == threat.trackId }
        val direction = threatDirectionLabel(track)
        val objLabel = threatClassLabel(track)
        val key = "$direction:$objLabel"
        val cooldownMs = 5_000L
        val prevAt = lastSpokenAtMs[key] ?: 0L
        if (now - prevAt < cooldownMs) return@LaunchedEffect

        val existing = pendingDirections[direction]
        val picked = when {
            existing == "人" || objLabel == "人" -> "人"
            existing == "车" || objLabel == "车" -> "车"
            else -> "障碍"
        }
        pendingDirections[direction] = picked
        lastSpokenAtMs[key] = now

        if (pendingJob == null) {
            pendingJob = scope.launch {
                delay(1000L)
                val snapshot = pendingDirections.toMap()
                pendingDirections.clear()
                pendingJob = null

                if (snapshot.isEmpty()) return@launch
                val directions = snapshot.keys.toList()
                val objSet = snapshot.values.toSet()
                val label = if (objSet.size == 1) objSet.first() else "障碍"
                val directionText = when (directions.size) {
                    1 -> directions[0]
                    2 -> "${directions[0]}和${directions[1]}"
                    else -> "多个方向"
                }

                val message = "${directionText}有${label}"
                Log.d("V2DemoTTS", "speak merged dir=$directionText obj=$label keys=${snapshot.keys}")
                audioManager.interruptForSafety()
                audioManager.speak(message, AudioPriorityManager.Priority.SAFETY)
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF020202)) {
        if (isLandscape) {
            V2DemoLandscape(
                onBack = onBack,
                selectedVideoName = selectedVideoName,
                isLive = isLive,
                frameBitmap = frameBitmap,
                tracks = tracks,
                threats = visualThreats,
                topThreat = topThreat,
                isAlert = isAlert,
                fpsText = fpsText,
                latencyText = latencyText,
                scaleState = scaleState,
                trackSpaceSize = frameResult?.let { IntSize(it.trackSpaceWidth, it.trackSpaceHeight) },
                onSelectVideo = { videoPickerLauncher.launch("video/*") },
                onSelectLive = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        isLive = true
                        selectedVideoUri = null
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        } else {
            V2DemoPortrait(
                onBack = onBack,
                selectedVideoName = selectedVideoName,
                isLive = isLive,
                frameBitmap = frameBitmap,
                tracks = tracks,
                threats = visualThreats,
                topThreat = topThreat,
                isAlert = isAlert,
                fpsText = fpsText,
                latencyText = latencyText,
                scaleState = scaleState,
                trackSpaceSize = frameResult?.let { IntSize(it.trackSpaceWidth, it.trackSpaceHeight) },
                onSelectVideo = { videoPickerLauncher.launch("video/*") },
                onSelectLive = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        isLive = true
                        selectedVideoUri = null
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        }
    }
}

@Composable
private fun V2DemoPortrait(
    onBack: () -> Unit,
    selectedVideoName: String,
    isLive: Boolean,
    frameBitmap: Bitmap?,
    tracks: List<TrackedObstacleV2>,
    threats: List<ThreatResultV2>,
    topThreat: ThreatResultV2?,
    isAlert: Boolean,
    fpsText: String,
    latencyText: String,
    scaleState: com.nihildigit.safetycore.v2.types.ScaleStateV2?,
    trackSpaceSize: IntSize?,
    onSelectVideo: () -> Unit,
    onSelectLive: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            // Header with back button and scene selectors
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DemoChip(Icons.Filled.FolderOpen, "选择视频", !isLive && selectedVideoName.isNotEmpty(), onSelectVideo)
                    DemoChip(Icons.Filled.Videocam, "相机", isLive, onSelectLive)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Video preview with detection overlay
            V2PreviewBox(
                isLive = isLive,
                frameBitmap = frameBitmap,
                tracks = tracks,
                threats = threats,
                topThreat = topThreat,
                isAlert = isAlert,
                fpsText = fpsText,
                latencyText = latencyText,
                scaleState = scaleState,
                trackSpaceSize = trackSpaceSize,
                showHUD = false
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status summary
            V2StatusRow(isAlert = isAlert, topThreat = topThreat, isLive = isLive)

            Spacer(modifier = Modifier.height(16.dp))

            // Threat details card
            if (threats.isNotEmpty()) {
                V2ThreatDetailsCard(threats = threats.take(3))
            }

            Spacer(modifier = Modifier.weight(1f))
        }

        // Bottom telemetry panel
        V2BottomPanel(
            fpsText = fpsText,
            latencyText = latencyText,
            scaleState = scaleState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun V2DemoLandscape(
    onBack: () -> Unit,
    selectedVideoName: String,
    isLive: Boolean,
    frameBitmap: Bitmap?,
    tracks: List<TrackedObstacleV2>,
    threats: List<ThreatResultV2>,
    topThreat: ThreatResultV2?,
    isAlert: Boolean,
    fpsText: String,
    latencyText: String,
    scaleState: com.nihildigit.safetycore.v2.types.ScaleStateV2?,
    trackSpaceSize: IntSize?,
    onSelectVideo: () -> Unit,
    onSelectLive: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        // Left: Preview with HUD
        Box(
            modifier = Modifier
                .weight(1.5f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            V2PreviewBox(
                isLive = isLive,
                frameBitmap = frameBitmap,
                tracks = tracks,
                threats = threats,
                topThreat = topThreat,
                isAlert = isAlert,
                fpsText = fpsText,
                latencyText = latencyText,
                scaleState = scaleState,
                trackSpaceSize = trackSpaceSize,
                showHUD = true
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.width(20.dp))

        // Right: Control panel
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DemoChip(Icons.Filled.FolderOpen, "选择视频", !isLive && selectedVideoName.isNotEmpty(), onSelectVideo)
                DemoChip(Icons.Filled.Videocam, "相机", isLive, onSelectLive)
            }

            V2StatusRow(isAlert = isAlert, topThreat = topThreat, isLive = isLive)

            if (threats.isNotEmpty()) {
                V2ThreatDetailsCard(threats = threats.take(3))
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun V2PreviewBox(
    isLive: Boolean,
    frameBitmap: Bitmap?,
    tracks: List<TrackedObstacleV2>,
    threats: List<ThreatResultV2>,
    topThreat: ThreatResultV2?,
    isAlert: Boolean,
    fpsText: String,
    latencyText: String,
    scaleState: com.nihildigit.safetycore.v2.types.ScaleStateV2?,
    trackSpaceSize: IntSize?,
    showHUD: Boolean
) {
    val threatMap = remember(threats) { threats.associateBy { it.trackId } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLive) Color.Black else Color(0xFF1A1A1A))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height

            // Draw frame
            frameBitmap?.let { bitmap ->
                val image = bitmap.asImageBitmap()
                drawImage(image, dstSize = IntSize(cw.toInt(), ch.toInt()))
            }

            // Draw detection boxes for confirmed tracks
            if (frameBitmap != null) {
                val fw = (trackSpaceSize?.width ?: frameBitmap.width).coerceAtLeast(1).toFloat()
                val fh = (trackSpaceSize?.height ?: frameBitmap.height).coerceAtLeast(1).toFloat()
                val tracksToDraw = tracks
                    .filter { it.confirmed }
                    .filter { threatMap[it.trackId] != null }

                tracksToDraw.forEach { track ->
                    val bbox = track.bbox
                    val boxLeft = (bbox[0] / fw) * cw
                    val boxTop = (bbox[1] / fh) * ch
                    val boxWidth = (bbox[2] / fw) * cw
                    val boxHeight = (bbox[3] / fh) * ch

                    val threat = threatMap[track.trackId]
                    val boxColor = dangerLevelColorV2(threat?.level)

                    // Draw box
                    drawRect(
                        boxColor.copy(alpha = 0.8f),
                        Offset(boxLeft, boxTop),
                        Size(boxWidth, boxHeight),
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // Draw label
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = boxColor.toArgb()
                            textSize = 12.dp.toPx()
                            isFakeBoldText = true
                        }
                        val label = buildString {
                            append("#${track.trackId}${if (track.confirmed) "" else " (u)"}")
                            threat?.let {
                                append(" ${String.format("%.1f", it.distanceMeters)}m")
                                if (it.ttcSeconds.isFinite() && it.ttcSeconds < 10f) {
                                    append(" TTC:${String.format("%.1f", it.ttcSeconds)}s")
                                }
                            }
                        }
                        canvas.nativeCanvas.drawText(label, boxLeft, boxTop - 6.dp.toPx(), paint)
                    }
                }
            } else if (!isLive) {
                // Placeholder text
                drawIntoCanvas { canvas ->
                    val paint = android.graphics.Paint().apply {
                        color = Color.Gray.toArgb()
                        textSize = 16.dp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText("点击\"选择视频\"开始", cw / 2, ch / 2, paint)
                }
            }
        }

        // HUD overlay
        if (showHUD) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricPod("推理", fpsText, "FPS", TongXingColors.Safe)
                    MetricPod("延迟", latencyText, "ms", TongXingColors.Safe)
                }

                // Scale indicator
                scaleState?.let {
                    Text(
                        text = "Scale: ${String.format("%.2f", it.scaleFactor)} ${if (it.stable) "✓" else "..."}",
                        color = if (it.stable) TongXingColors.Safe else TongXingColors.Caution,
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }
    }
}

@Composable
private fun V2StatusRow(
    isAlert: Boolean,
    topThreat: ThreatResultV2?,
    isLive: Boolean
) {
    val summaryText = when {
        topThreat == null && isLive -> "实时相机分析中..."
        topThreat == null -> "安全：建议通行"
        topThreat.level >= DangerLevelV2.HIGH -> "风险：前方 ${String.format("%.1f", topThreat.distanceMeters)}m 障碍"
        else -> "注意：检测到障碍物"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isAlert) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            null,
            tint = if (isAlert) TongXingColors.Critical else TongXingColors.Safe,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(summaryText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun V2ThreatDetailsCard(threats: List<ThreatResultV2>) {
    Surface(
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("威胁详情 (V2)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            threats.forEach { threat ->
                val color = dangerLevelColorV2(threat.level)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color, CircleShape)
                                .align(Alignment.CenterVertically)
                        )
                        Text("#${threat.trackId}", color = Color.White, fontSize = 12.sp)
                    }
                    Text(
                        "${threat.level} ${String.format("%.1f", threat.distanceMeters)}m",
                        color = color,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun V2BottomPanel(
    fpsText: String,
    latencyText: String,
    scaleState: com.nihildigit.safetycore.v2.types.ScaleStateV2?,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp)
    ) {
        GlassSurface(
            containerColor = Color.Black,
            alpha = 0.8f,
            cornerRadius = 28.dp,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier
                    .padding(vertical = 12.dp, horizontal = 16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text("V2 Pipeline", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricPod("推理效能", fpsText, "FPS", TongXingColors.Safe)
                    MetricPod("处理延迟", latencyText, "ms", TongXingColors.Safe)
                }
                scaleState?.let {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Scale: ${String.format("%.2f", it.scaleFactor)}",
                        color = if (it.stable) TongXingColors.Safe else TongXingColors.Caution,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

private fun dangerLevelColorV2(level: DangerLevelV2?): Color {
    return when (level) {
        DangerLevelV2.CRITICAL -> Color(0xFFFF4D4F)  // Red
        DangerLevelV2.HIGH -> Color(0xFFFFA726)      // Orange
        DangerLevelV2.MEDIUM -> Color(0xFFFFEB3B)   // Yellow
        DangerLevelV2.LOW -> Color(0xFF4CAF50)      // Green
        DangerLevelV2.NONE -> Color(0xFF4CAF50)
        null -> Color(0xFF4CAF50)
    }
}

@Composable
fun DemoChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) Color.White else Color.Transparent)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = if (selected) Color.White else Color.Gray, modifier = Modifier.size(16.dp))
            Text(label, color = if (selected) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
