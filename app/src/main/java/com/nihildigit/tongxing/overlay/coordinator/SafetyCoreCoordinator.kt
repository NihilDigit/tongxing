package com.nihildigit.tongxing.overlay.coordinator

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.Bitmap
import android.util.Log
import com.nihildigit.safetycore.SafetyCoreEngine
import com.nihildigit.safetycore.orchestrator.FrameProcessor
import com.nihildigit.safetycore.orchestrator.FrameResult
import com.nihildigit.safetycore.sensors.ImuSample
import com.nihildigit.safetycore.sensors.ImuSampleType
import com.nihildigit.tongxing.config.SafetyCoreDefaults
import com.nihildigit.tongxing.state.AppState

/**
 * SafetyCore + IMU 协调器。
 * 管理 FrameProcessor 生命周期和 IMU 传感器注册。
 */
class SafetyCoreCoordinator(
    private val context: Context,
    private val onFrameResultCallback: (FrameResult) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "SafetyCoreCoordinator"
    }

    private var frameProcessor: FrameProcessor? = null
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var imuStarted = false

    val isActive: Boolean get() = frameProcessor != null

    fun init() {
        Log.d(TAG, "Starting initialization with tuned defaults...")
        AppState.setSafetyCoreHealth(AppState.ComponentHealth.INITIALIZING)

        val config = SafetyCoreDefaults.createSafetyCoreConfig()

        try {
            frameProcessor = SafetyCoreEngine.createFrameProcessor(
                context, config
            ).apply {
                uiListener = object : FrameProcessor.UiListener {
                    override fun onFrameResult(result: FrameResult) {
                        onFrameResultCallback(result)
                    }
                }
                start()
            }
            startImu()
            AppState.setSafetyCoreHealth(AppState.ComponentHealth.READY)
            Log.i(TAG, "Initialized (hysteresis=${SafetyCoreDefaults.Heuristic.ENABLE_LEVEL_HYSTERESIS})")
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            AppState.setSafetyCoreHealth(AppState.ComponentHealth.FAILED)
        }
    }

    fun start() {
        if (frameProcessor == null) {
            init()
        } else {
            Log.i(TAG, "Explicit start() called")
            frameProcessor?.start()
            startImu()
        }
    }

    fun stop() {
        Log.i(TAG, "stop() called")
        stopImu()
        frameProcessor?.close()
        frameProcessor = null
        AppState.setSafetyLevel(AppState.SafetyLevel.SAFE)
    }

    fun release() {
        stop()
    }

    /** 供 Camera 帧回调调用 */
    fun onFrame(bitmap: Bitmap, timestampNanos: Long) {
        frameProcessor?.onFrame(bitmap, timestampNanos)
    }

    // --- IMU ---

    private fun startImu() {
        if (imuStarted) return
        if (gyroSensor == null && accelSensor == null) {
            Log.w(TAG, "IMU: Gyro/Accel not available on device")
            return
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        imuStarted = true
        Log.i(TAG, "IMU: Sensor listeners registered")
    }

    private fun stopImu() {
        if (!imuStarted) return
        sensorManager.unregisterListener(this)
        imuStarted = false
        Log.i(TAG, "IMU: Sensor listeners unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        val fp = frameProcessor ?: return
        val type = when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> ImuSampleType.GYRO
            Sensor.TYPE_ACCELEROMETER -> ImuSampleType.ACCEL
            else -> return
        }
        fp.onImuSample(
            ImuSample(
                tsNanos = event.timestamp,
                type = type,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2]
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }
}
