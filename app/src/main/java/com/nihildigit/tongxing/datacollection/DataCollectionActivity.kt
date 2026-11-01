package com.nihildigit.tongxing.datacollection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.nihildigit.tongxing.ui.theme.TongXingTheme
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据采集Activity
 * 同步录制视频 + IMU传感器数据(陀螺仪、加速度计)
 *
 * 输出格式:
 * - video.mp4: CameraX录制的视频
 * - sensors.jsonl: JSON Lines格式的传感器数据
 * - metadata.json: 录制元信息
 */
class DataCollectionActivity : ComponentActivity(), SensorEventListener {

    companion object {
        private const val TAG = "DataCollection"
        private const val SENSOR_DELAY = SensorManager.SENSOR_DELAY_GAME  // ~50Hz
    }

    // Sensor
    private lateinit var sensorManager: SensorManager
    private var gyroscope: Sensor? = null
    private var accelerometer: Sensor? = null

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var previewView: PreviewView? = null

    // Recording state
    private val _isRecording = mutableStateOf(false)
    private val _gyroCount = mutableIntStateOf(0)
    private val _accelCount = mutableIntStateOf(0)
    private val _recordingDuration = mutableIntStateOf(0)

    private var sessionDir: File? = null
    private var sensorWriter: BufferedWriter? = null
    private var recordingStartTimeMs = 0L
    private var durationUpdateJob: Job? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initCamera()
        } else {
            Toast.makeText(this, "需要相机权限才能录制", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (gyroscope == null || accelerometer == null) {
            Toast.makeText(this, "设备不支持陀螺仪或加速度计", Toast.LENGTH_LONG).show()
        }

        // Check permissions
        checkAndRequestPermissions()

        setContent {
            TongXingTheme {
                DataCollectionScreen(
                    onBack = {
                        if (_isRecording.value) stopRecording()
                        finish()
                    },
                    onToggleRecording = { toggleRecording() },
                    isRecording = _isRecording.value,
                    gyroCount = _gyroCount.intValue,
                    accelCount = _accelCount.intValue,
                    duration = _recordingDuration.intValue,
                    previewViewProvider = { getOrCreatePreviewView(it) }
                )
            }
        }
    }

    private fun getOrCreatePreviewView(context: Context): PreviewView {
        return previewView ?: PreviewView(context).also {
            previewView = it
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            initCamera()
        }
    }

    private fun initCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed", e)
                Toast.makeText(this, "相机初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val pv = previewView ?: return

        // Preview
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(pv.surfaceProvider)

        // Video capture - HD quality for clear visual inspection
        // Post-process with ffmpeg to scale to 640x480 for SafetyCore testing
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                videoCapture
            )
            Log.i(TAG, "Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun toggleRecording() {
        if (_isRecording.value) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (_isRecording.value) return
        val vc = videoCapture ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }

        // Create session directory
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "TongXing/DataCollection"
        )
        sessionDir = File(baseDir, "session_$timestamp").apply { mkdirs() }

        Log.i(TAG, "Starting recording to: ${sessionDir?.absolutePath}")

        // Open sensor file
        try {
            val sensorFile = File(sessionDir, "sensors.jsonl")
            sensorWriter = BufferedWriter(FileWriter(sensorFile))
            Log.i(TAG, "Sensor file opened: ${sensorFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open sensor file", e)
            Toast.makeText(this, "无法创建传感器文件: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // Start video recording
        val videoFile = File(sessionDir, "video.mp4")
        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        try {
            activeRecording = vc.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            Log.i(TAG, "Video recording started")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Log.e(TAG, "Video recording error: ${event.error}")
                            } else {
                                Log.i(TAG, "Video saved: ${videoFile.absolutePath}")
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            Toast.makeText(this, "视频录制启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            sensorWriter?.close()
            sensorWriter = null
            return
        }

        // Register sensor listeners
        gyroscope?.let { sensorManager.registerListener(this, it, SENSOR_DELAY) }
        accelerometer?.let { sensorManager.registerListener(this, it, SENSOR_DELAY) }

        // Reset stats
        _gyroCount.intValue = 0
        _accelCount.intValue = 0
        _recordingDuration.intValue = 0
        recordingStartTimeMs = SystemClock.elapsedRealtime()
        _isRecording.value = true

        // Start duration update
        durationUpdateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && _isRecording.value) {
                _recordingDuration.intValue = ((SystemClock.elapsedRealtime() - recordingStartTimeMs) / 1000).toInt()
                delay(500)
            }
        }

        // Write metadata
        writeMetadata()

        Toast.makeText(this, "开始录制: ${sessionDir?.name}", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        if (!_isRecording.value) return

        Log.i(TAG, "Stopping recording...")

        // Stop duration update
        durationUpdateJob?.cancel()
        durationUpdateJob = null

        // Unregister sensors
        sensorManager.unregisterListener(this)

        // Close sensor file
        try {
            sensorWriter?.flush()
            sensorWriter?.close()
            sensorWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close sensor file", e)
        }

        // Stop video recording
        activeRecording?.stop()
        activeRecording = null

        _isRecording.value = false

        val duration = _recordingDuration.intValue
        val gyro = _gyroCount.intValue
        val accel = _accelCount.intValue
        Toast.makeText(
            this,
            "录制完成: ${duration}s\ngyro=$gyro, accel=$accel\n${sessionDir?.absolutePath}",
            Toast.LENGTH_LONG
        ).show()

        Log.i(TAG, "Recording stopped. Duration: ${duration}s, gyro: $gyro, accel: $accel")
    }

    private fun writeMetadata() {
        try {
            val metadataFile = File(sessionDir, "metadata.json")
            val metadata = """
{
    "device": "${android.os.Build.MODEL}",
    "sdk": ${android.os.Build.VERSION.SDK_INT},
    "timestamp": "${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())}",
    "gyroscope": ${gyroscope != null},
    "accelerometer": ${accelerometer != null},
    "sensor_delay": "SENSOR_DELAY_GAME (~50Hz)",
    "video": {
        "quality": "HD",
        "resolution": "1280x720",
        "orientation": "landscape",
        "fps": 30,
        "note": "Post-process with: ffmpeg -i video.mp4 -vf scale=640:480 video_640x480.mp4"
    },
    "safetycore_input": {
        "resolution": "640x480",
        "fps": 5
    },
    "output_files": ["video.mp4", "sensors.jsonl", "metadata.json"]
}
            """.trimIndent()
            metadataFile.writeText(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write metadata", e)
        }
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent) {
        if (!_isRecording.value || sensorWriter == null) return

        val type = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                _gyroCount.intValue++
                "gyro"
            }
            Sensor.TYPE_ACCELEROMETER -> {
                _accelCount.intValue++
                "accel"
            }
            else -> return
        }

        // Use event.timestamp (nanoseconds, same clock as SystemClock.elapsedRealtimeNanos)
        val line = """{"ts":${event.timestamp},"type":"$type","x":${event.values[0]},"y":${event.values[1]},"z":${event.values[2]}}"""

        try {
            sensorWriter?.appendLine(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sensor data", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_isRecording.value) {
            stopRecording()
        }
        cameraProvider?.unbindAll()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCollectionScreen(
    onBack: () -> Unit,
    onToggleRecording: () -> Unit,
    isRecording: Boolean,
    gyroCount: Int,
    accelCount: Int,
    duration: Int,
    previewViewProvider: (Context) -> PreviewView
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据采集", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Camera preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { previewViewProvider(it) },
                    modifier = Modifier.fillMaxSize()
                )

                // Recording indicator
                if (isRecording) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(16.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "REC ${duration}s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Controls
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("传感器状态", fontWeight = FontWeight.SemiBold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("陀螺仪采样")
                        Text("$gyroCount", fontWeight = FontWeight.Medium)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("加速度计采样")
                        Text("$accelCount", fontWeight = FontWeight.Medium)
                    }

                    if (isRecording) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("采样率")
                            val rate = if (duration > 0) (gyroCount + accelCount) / duration else 0
                            Text("~${rate} Hz", fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Record button
                    Button(
                        onClick = onToggleRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRecording) "停止录制" else "开始录制",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "输出: Documents/TongXing/DataCollection/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
