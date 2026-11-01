package com.nihildigit.qnninferencesdk.core

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Pair
import android.util.Log
import com.qualcomm.qti.QnnDelegate
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.gpu.GpuDelegateFactory
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.DigestInputStream
import java.security.MessageDigest

object TFLiteHelper {
    enum class BackendMode { DEFAULT, QNN_ONLY, GPU_ONLY, CPU_ONLY }
    @Volatile var backendMode: BackendMode = BackendMode.DEFAULT

    fun loadModelFromAssets(context: Context, assetPath: String): Pair<MappedByteBuffer, String> {
        val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
        val startOffset = afd.startOffset
        val declaredLength = afd.length

        // Map the model slice
        val inputStream = FileInputStream(afd.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val mapped: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

        // Compute MD5 on the same slice [startOffset, startOffset+declaredLength)
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(afd.fileDescriptor).use { fis ->
            // Skip to slice start
            fis.skip(startOffset)
            DigestInputStream(fis, digest).use { dis ->
                val buf = ByteArray(8192)
                var remaining = declaredLength
                while (remaining > 0) {
                    val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
                    val read = dis.read(buf, 0, toRead)
                    if (read <= 0) break
                    remaining -= read
                }
            }
        }
        val hash = digest.digest().joinToString("") { b -> "%02x".format(b) }
        return Pair(mapped, hash)
    }

    data class InterpreterResult(
        val interpreter: Interpreter,
        val delegates: Map<String, Delegate>
    )

    fun createInterpreterWithDelegates(
        context: Context,
        model: MappedByteBuffer,
        modelToken: String,
        numThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
    ): InterpreterResult {
        fun makeOptionsAndCreate(delegates: LinkedHashMap<String, Delegate>): Interpreter {
            val options = Interpreter.Options().apply {
                setUseNNAPI(false)
                setUseXNNPACK(true)
                setNumThreads(numThreads)
                setAllowBufferHandleOutput(true)
                // Set runtime to FROM_APPLICATION_ONLY when available (via reflection for compatibility)
                try { setRuntimeFromApplicationOnly(this) } catch (_: Throwable) {}
                delegates.values.forEach { addDelegate(it) }
            }
            return Interpreter(model, options).also { it.allocateTensors() }
        }

        fun closeDelegates(map: Map<String, Delegate>) {
            map.values.forEach {
                try { it.close() } catch (_: Throwable) {}
            }
        }

        when (backendMode) {
            BackendMode.DEFAULT -> {
                // Preference order: QNN -> CPU（默认关闭 GPU，避免跨线程 GPU delegate 崩溃）
                // Try QNN only
                val attempt1 = LinkedHashMap<String, Delegate>()
                createQnnDelegate(context, modelToken)?.let { attempt1["QNN_NPU"] = it }
                if (attempt1.isNotEmpty()) {
                    try {
                        val interp = makeOptionsAndCreate(attempt1)
                        return InterpreterResult(interp, attempt1)
                    } catch (_: Throwable) {
                        closeDelegates(attempt1)
                    }
                }
                // Fallback CPU
                val interp = makeOptionsAndCreate(LinkedHashMap())
                return InterpreterResult(interp, emptyMap())
            }
            BackendMode.QNN_ONLY -> {
                val delegates = LinkedHashMap<String, Delegate>()
                val qnn = createQnnDelegate(context, modelToken)
                if (qnn != null) delegates["QNN_NPU"] = qnn else throw IllegalStateException("QNN delegate is not available on this device")
                val interp = makeOptionsAndCreate(delegates)
                return InterpreterResult(interp, delegates)
            }
            BackendMode.GPU_ONLY -> {
                val delegates = LinkedHashMap<String, Delegate>()
                val gpu = createGpuDelegate(context.cacheDir.absolutePath, modelToken)
                if (gpu != null) delegates["GPUv2"] = gpu else throw IllegalStateException("GPU delegate is not available on this device")
                val interp = makeOptionsAndCreate(delegates)
                return InterpreterResult(interp, delegates)
            }
            BackendMode.CPU_ONLY -> {
                val interp = makeOptionsAndCreate(LinkedHashMap())
                return InterpreterResult(interp, emptyMap())
            }
        }
    }

    private fun createQnnDelegate(context: Context, modelToken: String): QnnDelegate? {
        return try {
            val opts = QnnDelegate.Options()
            // Required paths and caching
            opts.setSkelLibraryDir(context.applicationInfo.nativeLibraryDir)
            opts.setCacheDir(context.cacheDir.absolutePath)
            opts.setModelToken(modelToken)
            // Backend and performance (prefer DSP if available, else HTP)
            val hasDsp = try { QnnDelegate.checkCapability(QnnDelegate.Capability.DSP_RUNTIME) } catch (_: Throwable) { false }
            if (hasDsp) {
                opts.setBackendType(QnnDelegate.Options.BackendType.DSP_BACKEND)
                opts.setDspOptions(
                    QnnDelegate.Options.DspPerformanceMode.DSP_PERFORMANCE_BURST,
                    QnnDelegate.Options.DspPdSession.DSP_PD_SESSION_ADAPTIVE
                )
            } else {
                opts.setBackendType(QnnDelegate.Options.BackendType.HTP_BACKEND)
                opts.setHtpUseConvHmx(QnnDelegate.Options.HtpUseConvHmx.HTP_CONV_HMX_ON)
                opts.setHtpPerformanceMode(QnnDelegate.Options.HtpPerformanceMode.HTP_PERFORMANCE_BURST)
            }
            // 提升日志等级用于诊断
            opts.setLogLevel(QnnDelegate.Options.LogLevel.LOG_LEVEL_INFO)

            val hasQuant = try { QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_QUANTIZED) } catch (_: Throwable) { false }
            val hasFp16 = try { QnnDelegate.checkCapability(QnnDelegate.Capability.HTP_RUNTIME_FP16) } catch (_: Throwable) { false }
            Log.i(TAG, "QNN caps: dsp=$hasDsp quant=$hasQuant fp16=$hasFp16 skelDir=${context.applicationInfo.nativeLibraryDir}")
            if (!hasDsp && !hasQuant && !hasFp16) {
                Log.w(TAG, "QNN not supported on this device/runtime")
                null
            } else {
                if (!hasDsp && hasFp16) {
                    try { opts.setHtpPrecision(QnnDelegate.Options.HtpPrecision.HTP_PRECISION_FP16) } catch (_: Throwable) {}
                }
                val d = QnnDelegate(opts)
                Log.i(TAG, "QNN delegate created (backend=" + (if (hasDsp) "DSP" else "HTP") + ")")
                d
            }
        } catch (t: Throwable) {
            Log.e(TAG, "QNN delegate creation failed", t)
            null
        }
    }

    private fun createGpuDelegate(cacheDir: String, modelToken: String): GpuDelegate? {
        return try {
            val gpuOptions = org.tensorflow.lite.gpu.GpuDelegateFactory.Options()
            gpuOptions.setInferencePreference(org.tensorflow.lite.gpu.GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            gpuOptions.setPrecisionLossAllowed(true)
            // Enable serialization to speed up subsequent loads
            gpuOptions.setSerializationParams(cacheDir, modelToken)
            org.tensorflow.lite.gpu.GpuDelegate(gpuOptions)
        } catch (_: Throwable) {
            null
        }
    }
}

private const val TAG = "QnnTFLiteHelper"

@Suppress("UNCHECKED_CAST")
private fun setRuntimeFromApplicationOnly(options: Interpreter.Options) {
    try {
        val optionsClass = Class.forName("org.tensorflow.lite.Interpreter\$Options")
        val runtimeEnumClass = Class.forName("org.tensorflow.lite.Interpreter\$Options\$TfLiteRuntime")
        val fromAppOnly = java.lang.Enum.valueOf(runtimeEnumClass as Class<out Enum<*>>, "FROM_APPLICATION_ONLY")
        val setRuntimeMethod = optionsClass.getMethod("setRuntime", runtimeEnumClass)
        setRuntimeMethod.invoke(options, fromAppOnly)
    } catch (_: Throwable) {
        // Silently ignore if this TFLite version doesn't support runtime selection
    }
}
